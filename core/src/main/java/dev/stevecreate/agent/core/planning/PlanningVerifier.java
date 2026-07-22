package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Read-only gate from a mapped candidate to a non-executable verified logical plan. */
public final class PlanningVerifier {
    public PlanningVerificationResult verify(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            MachineCapabilityCatalog capabilityCatalog,
            PlanningContext context) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(capabilityCatalog, "capabilityCatalog");
        Objects.requireNonNull(context, "context");

        LogicalGraphMappingResult remapped = new CandidateLogicalGraphMapper().map(
                candidate, capabilityCatalog, context);
        if (remapped instanceof LogicalGraphMappingFailureResult failed) {
            return new PlanningVerificationFailureResult(translate(
                    candidate, graph, failed.failure()));
        }
        LogicalMachineGraph expected = ((LogicalGraphMappingSuccess) remapped).graph();

        PlanningVerificationFailure failure = verifyIdentityAndCapabilities(
                candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyRuntimeDeclarations(
                candidate, graph, capabilityCatalog, context);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyEdgeResources(candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyAcyclic(candidate, graph);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyDependencies(candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyTarget(candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifySourcesAndQuantities(candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }
        failure = verifyExactShape(candidate, graph, expected);
        if (failure != null) {
            return new PlanningVerificationFailureResult(failure);
        }

        return new PlanningVerificationSuccess(new VerifiedLogicalPlan(
                candidate, graph, successEvidence(candidate, graph, context)));
    }

    private static PlanningVerificationFailure verifyIdentityAndCapabilities(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        if (!graph.id().equals(expected.id())) {
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.CANDIDATE_GRAPH_MISMATCH,
                    PlanningVerificationCheck.DEPENDENCIES_TRACEABLE,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                    "Logical graph identity is not derived from the candidate identity");
        }
        Map<ResourceId, LogicalGraphNode> expectedProcesses = processNodes(expected);
        Map<ResourceId, LogicalGraphNode> actualProcesses = processNodes(graph);
        if (!expectedProcesses.keySet().equals(actualProcesses.keySet())) {
            ResourceId subject = firstDifference(
                    expectedProcesses.keySet(), actualProcesses.keySet()).orElse(graph.id());
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED,
                    PlanningVerificationCheck.CAPABILITIES_DECLARED,
                    Optional.of(subject), Optional.empty(), Optional.empty(), List.of(),
                    "Logical process nodes do not cover the candidate processing steps exactly");
        }
        for (Map.Entry<ResourceId, LogicalGraphNode> entry : expectedProcesses.entrySet()) {
            LogicalGraphNode actual = actualProcesses.get(entry.getKey());
            LogicalGraphNode expectedNode = entry.getValue();
            if (!actual.stepId().equals(expectedNode.stepId())
                    || !actual.requiredCapabilities().equals(expectedNode.requiredCapabilities())
                    || !actual.requiredResources().equals(expectedNode.requiredResources())) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED,
                        PlanningVerificationCheck.CAPABILITIES_DECLARED,
                        Optional.of(actual.id()), Optional.empty(), Optional.empty(), List.of(),
                        "Process capability or power/resource requirements differ from the candidate"
                                + " and capability catalog");
            }
        }
        return null;
    }

    private static PlanningVerificationFailure verifyRuntimeDeclarations(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            MachineCapabilityCatalog capabilityCatalog,
            PlanningContext context) {
        Map<ResourceId, CatalogRecipe> recipeByStep = new LinkedHashMap<>();
        for (int index = 0; index < candidate.processingOrder().size(); index++) {
            ResourceId stepId = candidate.processingOrder().get(index);
            CatalogRecipe recipe = candidate.selectedRecipes().get(index);
            recipeByStep.put(stepId, recipe);
            if (!context.availableAdapterIds().contains(recipe.source().adapterId())
                    || !context.availableModIds().contains(recipe.source().sourceModId())) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.ADAPTER_UNAVAILABLE,
                        PlanningVerificationCheck.ADAPTERS_SUPPORTED,
                        Optional.empty(), Optional.empty(),
                        Optional.of(recipe.source().adapterId()), List.of(stepId),
                        "Recipe source Adapter or source mod is not available in the context");
            }
        }

        for (LogicalGraphNode node : processNodes(graph).values()) {
            ResourceId stepId = node.stepId().orElseThrow();
            CatalogRecipe recipe = recipeByStep.get(stepId);
            if (recipe == null) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED,
                        PlanningVerificationCheck.CAPABILITIES_DECLARED,
                        Optional.of(node.id()), Optional.empty(), Optional.empty(), List.of(stepId),
                        "Process node step has no selected recipe declaration");
            }
            for (ResourceId capabilityId : node.requiredCapabilities()) {
                Optional<MachineCapability> declared = capabilityCatalog.find(capabilityId);
                if (declared.isEmpty() || !declared.get().isCompatibleWith(recipe)) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED,
                            PlanningVerificationCheck.CAPABILITIES_DECLARED,
                            Optional.of(node.id()), Optional.empty(),
                            Optional.of(capabilityId), List.of(stepId),
                            "Process node capability is absent or incompatible with its recipe");
                }
                if (!context.availableAdapterIds().contains(declared.get().adapterId())) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.ADAPTER_UNAVAILABLE,
                            PlanningVerificationCheck.ADAPTERS_SUPPORTED,
                            Optional.of(node.id()), Optional.empty(),
                            Optional.of(declared.get().adapterId()), List.of(stepId),
                            "Capability Adapter is not available in the planning context");
                }
            }
            for (CapabilityResourceRequirement requirement : node.requiredResources()) {
                if (!context.supportedResourceTypes().contains(requirement.resourceType())) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                            PlanningVerificationCheck.EDGE_TYPES_MATCH,
                            Optional.of(node.id()), Optional.empty(), Optional.empty(), List.of(stepId),
                            "Capability resource type is unsupported: "
                                    + requirement.resourceType().serializedName());
                }
            }
        }
        for (LogicalPortRequirement port : graph.ports().values()) {
            if (!context.supportedResourceTypes().contains(port.resourceType())) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                        PlanningVerificationCheck.EDGE_TYPES_MATCH,
                        Optional.of(port.nodeId()), Optional.empty(),
                        Optional.of(port.resourceId()), List.of(port.id()),
                        "Logical port resource type is unsupported: "
                                + port.resourceType().serializedName());
            }
        }
        return null;
    }

    private static PlanningVerificationFailure verifyEdgeResources(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        for (LogicalPortRequirement actual : graph.ports().values()) {
            LogicalPortRequirement expectedPort = expected.ports().get(actual.id());
            if (expectedPort != null
                    && (!actual.resourceId().equals(expectedPort.resourceId())
                    || actual.resourceType() != expectedPort.resourceType()
                    || actual.mode() != expectedPort.mode()
                    || actual.role() != expectedPort.role())) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.EDGE_RESOURCE_MISMATCH,
                        PlanningVerificationCheck.EDGE_TYPES_MATCH,
                        Optional.of(actual.nodeId()), Optional.empty(),
                        Optional.of(actual.resourceId()), List.of(actual.id()),
                        "Logical port resource identity, type, mode, or role differs from the candidate");
            }
        }
        for (LogicalResourceEdge actual : graph.edges().values()) {
            if (actual.resourceType() != GenericResourceType.ITEM) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.EDGE_RESOURCE_MISMATCH,
                        PlanningVerificationCheck.EDGE_TYPES_MATCH,
                        Optional.empty(), Optional.of(actual.id()),
                        Optional.of(actual.resourceId()), List.of(),
                        "P-10 logical production edges must carry ITEM resources");
            }
            LogicalResourceEdge expectedEdge = expected.edges().get(actual.id());
            if (expectedEdge != null
                    && (!actual.resourceId().equals(expectedEdge.resourceId())
                    || actual.resourceType() != expectedEdge.resourceType()
                    || actual.kind() != expectedEdge.kind())) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.EDGE_RESOURCE_MISMATCH,
                        PlanningVerificationCheck.EDGE_TYPES_MATCH,
                        Optional.empty(), Optional.of(actual.id()),
                        Optional.of(actual.resourceId()), List.of(),
                        "Logical edge resource identity, type, or provenance differs from the candidate");
            }
        }
        return null;
    }

    private static PlanningVerificationFailure verifyAcyclic(
            CandidatePlan candidate,
            LogicalMachineGraph graph) {
        Map<ResourceId, LogicalGraphNode> processes = processNodes(graph);
        Map<ResourceId, Integer> indegree = new LinkedHashMap<>();
        Map<ResourceId, List<ResourceId>> adjacency = new LinkedHashMap<>();
        processes.keySet().forEach(id -> {
            indegree.put(id, 0);
            adjacency.put(id, new ArrayList<>());
        });
        for (LogicalResourceEdge edge : graph.edges().values()) {
            if (edge.kind() != LogicalEdgeKind.INTERMEDIATE) {
                continue;
            }
            ResourceId source = graph.port(edge.sourcePortId()).nodeId();
            ResourceId target = graph.port(edge.targetPortId()).nodeId();
            adjacency.get(source).add(target);
            indegree.put(target, indegree.get(target) + 1);
        }
        ArrayDeque<ResourceId> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            ResourceId node = ready.removeFirst();
            visited++;
            for (ResourceId target : adjacency.get(node)) {
                int next = indegree.get(target) - 1;
                indegree.put(target, next);
                if (next == 0) {
                    ready.addLast(target);
                }
            }
        }
        if (visited != processes.size()) {
            List<ResourceId> cycleTrace = indegree.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.DEPENDENCY_CYCLE,
                    PlanningVerificationCheck.ACYCLIC,
                    cycleTrace.isEmpty() ? Optional.empty() : Optional.of(cycleTrace.get(0)),
                    Optional.empty(), Optional.empty(), cycleTrace,
                    "Logical intermediate edges contain an unexplained process cycle");
        }
        return null;
    }

    private static PlanningVerificationFailure verifyDependencies(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        Map<ResourceId, LogicalResourceEdge> expectedEdges = edgesOfKind(
                expected, LogicalEdgeKind.INTERMEDIATE);
        Map<ResourceId, LogicalResourceEdge> actualEdges = edgesOfKind(
                graph, LogicalEdgeKind.INTERMEDIATE);
        if (!expectedEdges.keySet().equals(actualEdges.keySet())) {
            ResourceId edgeId = firstDifference(
                    expectedEdges.keySet(), actualEdges.keySet()).orElse(graph.id());
            LogicalResourceEdge subject = expectedEdges.containsKey(edgeId)
                    ? expectedEdges.get(edgeId)
                    : actualEdges.get(edgeId);
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.DEPENDENCY_UNTRACEABLE,
                    PlanningVerificationCheck.DEPENDENCIES_TRACEABLE,
                    Optional.empty(), Optional.of(edgeId),
                    subject == null ? Optional.empty() : Optional.of(subject.resourceId()),
                    List.of(),
                    "Intermediate edges do not cover candidate dependencies exactly");
        }
        for (Map.Entry<ResourceId, LogicalResourceEdge> entry : expectedEdges.entrySet()) {
            LogicalResourceEdge expectedEdge = entry.getValue();
            LogicalResourceEdge actual = actualEdges.get(entry.getKey());
            if (!actual.sourcePortId().equals(expectedEdge.sourcePortId())
                    || !actual.targetPortId().equals(expectedEdge.targetPortId())
                    || !actual.resourceId().equals(expectedEdge.resourceId())
                    || actual.resourceType() != expectedEdge.resourceType()) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.DEPENDENCY_UNTRACEABLE,
                        PlanningVerificationCheck.DEPENDENCIES_TRACEABLE,
                        Optional.empty(), Optional.of(actual.id()),
                        Optional.of(actual.resourceId()), List.of(),
                        "Intermediate edge cannot be traced to the declared producer and consumer");
            }
        }
        return null;
    }

    private static PlanningVerificationFailure verifyTarget(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        List<LogicalPortRequirement> demands = graph.ports().values().stream()
                .filter(port -> port.role() == LogicalPortRole.TARGET_DEMAND)
                .toList();
        List<LogicalResourceEdge> targetEdges = graph.edges().values().stream()
                .filter(edge -> edge.kind() == LogicalEdgeKind.TARGET_OUTPUT)
                .toList();
        List<LogicalPortRequirement> expectedDemands = expected.ports().values().stream()
                .filter(port -> port.role() == LogicalPortRole.TARGET_DEMAND)
                .toList();
        List<LogicalResourceEdge> expectedTargets = expected.edges().values().stream()
                .filter(edge -> edge.kind() == LogicalEdgeKind.TARGET_OUTPUT)
                .toList();
        if (demands.size() != 1 || targetEdges.size() != 1
                || expectedDemands.size() != 1 || expectedTargets.size() != 1) {
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.TARGET_UNSATISFIED,
                    PlanningVerificationCheck.TARGET_SATISFIED,
                    Optional.empty(), Optional.empty(), Optional.of(candidate.goal().target()),
                    List.of(),
                    "Verified planning requires exactly one target demand and production edge");
        }
        LogicalPortRequirement demand = demands.get(0);
        LogicalPortRequirement expectedDemand = expectedDemands.get(0);
        LogicalResourceEdge target = targetEdges.get(0);
        LogicalResourceEdge expectedTarget = expectedTargets.get(0);
        if (!demand.equals(expectedDemand)
                || !target.sourcePortId().equals(expectedTarget.sourcePortId())
                || !target.targetPortId().equals(demand.id())
                || !target.resourceId().equals(candidate.goal().target())
                || target.resourceType() != candidate.goal().targetResourceType()
                || target.amount() != candidate.goal().quantity()) {
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.TARGET_UNSATISFIED,
                    PlanningVerificationCheck.TARGET_SATISFIED,
                    Optional.of(demand.nodeId()), Optional.of(target.id()),
                    Optional.of(candidate.goal().target()), List.of(),
                    "Target resource, quantity, demand, or unique production source is incorrect");
        }
        return null;
    }

    private static PlanningVerificationFailure verifySourcesAndQuantities(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        for (LogicalPortRequirement expectedPort : expected.ports().values()) {
            LogicalPortRequirement actual = graph.ports().get(expectedPort.id());
            if (actual == null) {
                PlanningVerificationFailureCode code =
                        expectedPort.role() == LogicalPortRole.PROCESS_INPUT
                                ? PlanningVerificationFailureCode.PRODUCTION_SOURCE_MISSING
                                : PlanningVerificationFailureCode.CANDIDATE_GRAPH_MISMATCH;
                PlanningVerificationCheck check =
                        expectedPort.role() == LogicalPortRole.PROCESS_INPUT
                                ? PlanningVerificationCheck.PRODUCTION_SOURCES_PRESENT
                                : PlanningVerificationCheck.QUANTITIES_CONSISTENT;
                return failure(
                        candidate, graph, code, check,
                        Optional.of(expectedPort.nodeId()), Optional.empty(),
                        Optional.of(expectedPort.resourceId()), List.of(expectedPort.id()),
                        "Expected logical port is missing from the submitted graph");
            }
            if (actual.amount() != expectedPort.amount()) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.QUANTITY_INCONSISTENT,
                        PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                        Optional.of(actual.nodeId()), Optional.empty(),
                        Optional.of(actual.resourceId()), List.of(actual.id()),
                        "Logical port amount differs from the scaled candidate quantity");
            }
        }

        for (LogicalPortRequirement port : graph.ports().values()) {
            if (port.role() == LogicalPortRole.PROCESS_INPUT) {
                long incoming = sumIncoming(graph, port.id());
                if (incoming == 0) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.PRODUCTION_SOURCE_MISSING,
                            PlanningVerificationCheck.PRODUCTION_SOURCES_PRESENT,
                            Optional.of(port.nodeId()), Optional.empty(),
                            Optional.of(port.resourceId()), List.of(port.id()),
                            "Non-leaf process input has no raw, owned, or process production source");
                }
                if (incoming != port.amount()) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.QUANTITY_INCONSISTENT,
                            PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                            Optional.of(port.nodeId()), Optional.empty(),
                            Optional.of(port.resourceId()), List.of(port.id()),
                            "Incoming resource amount does not equal the process input requirement");
                }
            } else if (port.role() == LogicalPortRole.EXTERNAL_SUPPLY) {
                long outgoing = sumOutgoing(graph, port.id());
                if (outgoing == 0) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.PRODUCTION_SOURCE_MISSING,
                            PlanningVerificationCheck.PRODUCTION_SOURCES_PRESENT,
                            Optional.of(port.nodeId()), Optional.empty(),
                            Optional.of(port.resourceId()), List.of(port.id()),
                            "Declared raw or owned leaf is not connected to a consumer");
                }
                if (outgoing != port.amount()) {
                    return failure(
                            candidate, graph,
                            PlanningVerificationFailureCode.QUANTITY_INCONSISTENT,
                            PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                            Optional.of(port.nodeId()), Optional.empty(),
                            Optional.of(port.resourceId()), List.of(port.id()),
                            "External leaf quantity does not equal its allocated outgoing flow");
                }
            } else if (port.role() == LogicalPortRole.PROCESS_OUTPUT
                    && sumOutgoing(graph, port.id()) > port.amount()) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.QUANTITY_INCONSISTENT,
                        PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                        Optional.of(port.nodeId()), Optional.empty(),
                        Optional.of(port.resourceId()), List.of(port.id()),
                        "Outgoing resource amount exceeds the process output quantity");
            }
        }

        for (LogicalResourceEdge expectedEdge : expected.edges().values()) {
            LogicalResourceEdge actual = graph.edges().get(expectedEdge.id());
            if (actual != null && actual.amount() != expectedEdge.amount()) {
                return failure(
                        candidate, graph,
                        PlanningVerificationFailureCode.QUANTITY_INCONSISTENT,
                        PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                        Optional.empty(), Optional.of(actual.id()),
                        Optional.of(actual.resourceId()), List.of(),
                        "Logical edge amount differs from the candidate allocation");
            }
        }
        return null;
    }

    private static PlanningVerificationFailure verifyExactShape(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalMachineGraph expected) {
        if (!graph.nodes().equals(expected.nodes())
                || !graph.ports().equals(expected.ports())
                || !graph.edges().equals(expected.edges())) {
            return failure(
                    candidate, graph,
                    PlanningVerificationFailureCode.CANDIDATE_GRAPH_MISMATCH,
                    PlanningVerificationCheck.DEPENDENCIES_TRACEABLE,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                    "Submitted logical graph contains unexplained nodes, ports, edges, or values");
        }
        return null;
    }

    private static List<PlanningVerificationEvidence> successEvidence(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            PlanningContext context) {
        return List.of(
                evidence(
                        PlanningVerificationCheck.DEPENDENCIES_TRACEABLE,
                        graph.id(),
                        "dependencies=" + candidate.dependencies().size()),
                evidence(
                        PlanningVerificationCheck.QUANTITIES_CONSISTENT,
                        graph.id(),
                        "ports=" + graph.ports().size() + ",edges=" + graph.edges().size()),
                evidence(
                        PlanningVerificationCheck.PRODUCTION_SOURCES_PRESENT,
                        graph.id(),
                        "processInputs=" + graph.ports().values().stream()
                                .filter(port -> port.role() == LogicalPortRole.PROCESS_INPUT)
                                .count()),
                evidence(
                        PlanningVerificationCheck.CAPABILITIES_DECLARED,
                        graph.id(),
                        "capabilities=" + candidate.requiredMachineCapabilities().size()),
                evidence(
                        PlanningVerificationCheck.EDGE_TYPES_MATCH,
                        graph.id(),
                        "itemEdges=" + graph.edges().size()),
                evidence(
                        PlanningVerificationCheck.ACYCLIC,
                        graph.id(),
                        "processNodes=" + processNodes(graph).size()),
                evidence(
                        PlanningVerificationCheck.TARGET_SATISFIED,
                        candidate.goal().target(),
                        "quantity=" + candidate.goal().quantity()),
                evidence(
                        PlanningVerificationCheck.ADAPTERS_SUPPORTED,
                        graph.id(),
                        "availableAdapters=" + context.availableAdapterIds().size()));
    }

    private static PlanningVerificationEvidence evidence(
            PlanningVerificationCheck check,
            ResourceId subject,
            String detail) {
        return new PlanningVerificationEvidence(check, subject, detail);
    }

    private static PlanningVerificationFailure translate(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            LogicalGraphMappingFailure mappingFailure) {
        PlanningVerificationFailureCode code;
        PlanningVerificationCheck check;
        switch (mappingFailure.code()) {
            case CANDIDATE_INCONSISTENT -> {
                code = PlanningVerificationFailureCode.CANDIDATE_GRAPH_MISMATCH;
                check = PlanningVerificationCheck.DEPENDENCIES_TRACEABLE;
            }
            case MACHINE_CAPABILITY_MISSING, MACHINE_CAPABILITY_INCOMPATIBLE -> {
                code = PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED;
                check = PlanningVerificationCheck.CAPABILITIES_DECLARED;
            }
            case CAPABILITY_ADAPTER_UNAVAILABLE -> {
                code = PlanningVerificationFailureCode.ADAPTER_UNAVAILABLE;
                check = PlanningVerificationCheck.ADAPTERS_SUPPORTED;
            }
            case RESOURCE_TYPE_UNSUPPORTED -> {
                code = PlanningVerificationFailureCode.RESOURCE_TYPE_UNSUPPORTED;
                check = PlanningVerificationCheck.EDGE_TYPES_MATCH;
            }
            case INPUT_ALLOCATION_UNRESOLVED -> {
                code = PlanningVerificationFailureCode.PRODUCTION_SOURCE_MISSING;
                check = PlanningVerificationCheck.PRODUCTION_SOURCES_PRESENT;
            }
            case TARGET_OUTPUT_MISSING, TARGET_OUTPUT_AMBIGUOUS -> {
                code = PlanningVerificationFailureCode.TARGET_UNSATISFIED;
                check = PlanningVerificationCheck.TARGET_SATISFIED;
            }
            case QUANTITY_OVERFLOW -> {
                code = PlanningVerificationFailureCode.QUANTITY_INCONSISTENT;
                check = PlanningVerificationCheck.QUANTITIES_CONSISTENT;
            }
            default -> throw new IllegalStateException(
                    "Unhandled logical graph mapping failure " + mappingFailure.code());
        }
        return failure(
                candidate, graph, code, check,
                mappingFailure.nodeId(), Optional.empty(), mappingFailure.resourceId(),
                mappingFailure.trace(), mappingFailure.detail());
    }

    private static PlanningVerificationFailure failure(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            PlanningVerificationFailureCode code,
            PlanningVerificationCheck check,
            Optional<ResourceId> nodeId,
            Optional<ResourceId> edgeId,
            Optional<ResourceId> resourceId,
            List<ResourceId> additionalTrace,
            String detail) {
        LinkedHashSet<ResourceId> trace = new LinkedHashSet<>();
        trace.add(candidate.candidateId());
        trace.add(graph.id());
        trace.addAll(additionalTrace);
        nodeId.ifPresent(trace::add);
        edgeId.ifPresent(trace::add);
        resourceId.ifPresent(trace::add);
        return new PlanningVerificationFailure(
                code,
                check,
                candidate.candidateId(),
                graph.id(),
                nodeId,
                edgeId,
                resourceId,
                List.copyOf(trace),
                detail);
    }

    private static Map<ResourceId, LogicalGraphNode> processNodes(LogicalMachineGraph graph) {
        Map<ResourceId, LogicalGraphNode> processes = new LinkedHashMap<>();
        graph.nodes().forEach((id, node) -> {
            if (node.kind() == LogicalNodeKind.PROCESS) {
                processes.put(id, node);
            }
        });
        return processes;
    }

    private static Map<ResourceId, LogicalResourceEdge> edgesOfKind(
            LogicalMachineGraph graph,
            LogicalEdgeKind kind) {
        Map<ResourceId, LogicalResourceEdge> matching = new LinkedHashMap<>();
        graph.edges().forEach((id, edge) -> {
            if (edge.kind() == kind) {
                matching.put(id, edge);
            }
        });
        return matching;
    }

    private static long sumIncoming(LogicalMachineGraph graph, ResourceId portId) {
        return graph.edges().values().stream()
                .filter(edge -> edge.targetPortId().equals(portId))
                .mapToLong(LogicalResourceEdge::amount)
                .sum();
    }

    private static long sumOutgoing(LogicalMachineGraph graph, ResourceId portId) {
        return graph.edges().values().stream()
                .filter(edge -> edge.sourcePortId().equals(portId))
                .mapToLong(LogicalResourceEdge::amount)
                .sum();
    }

    private static Optional<ResourceId> firstDifference(
            Set<ResourceId> expected,
            Set<ResourceId> actual) {
        for (ResourceId value : expected) {
            if (!actual.contains(value)) {
                return Optional.of(value);
            }
        }
        for (ResourceId value : actual) {
            if (!expected.contains(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
