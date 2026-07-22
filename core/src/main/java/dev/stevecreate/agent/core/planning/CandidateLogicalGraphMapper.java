package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministically maps a coordinate-free candidate to a logical resource-flow graph.
 *
 * <p>This mapper performs no implementation selection, layout, game access, or execution.</p>
 */
public final class CandidateLogicalGraphMapper {
    public LogicalGraphMappingResult map(
            CandidatePlan candidate,
            MachineCapabilityCatalog capabilityCatalog,
            PlanningContext context) {
        return new Mapping(
                Objects.requireNonNull(candidate, "candidate"),
                Objects.requireNonNull(capabilityCatalog, "capabilityCatalog"),
                Objects.requireNonNull(context, "context"))
                .map();
    }

    private static final class Mapping {
        private final CandidatePlan candidate;
        private final MachineCapabilityCatalog capabilityCatalog;
        private final PlanningContext context;
        private final List<LogicalGraphNode> nodes = new ArrayList<>();
        private final List<LogicalPortRequirement> ports = new ArrayList<>();
        private final List<LogicalResourceEdge> edges = new ArrayList<>();
        private final Map<StepResourceKey, LogicalPortRequirement> inputs = new LinkedHashMap<>();
        private final Map<StepResourceKey, LogicalPortRequirement> outputs = new LinkedHashMap<>();
        private final Map<ResourceId, Long> sourceAllocations = new LinkedHashMap<>();
        private final Map<ResourceId, Long> targetAllocations = new LinkedHashMap<>();
        private final Map<BoundaryKey, LogicalPortRequirement> boundarySupplies =
                new LinkedHashMap<>();
        private Map<ResourceKey, Long> rawTotals;
        private Map<ResourceKey, Long> rawRemaining;
        private Map<ResourceKey, Long> ownedTotals;
        private Map<ResourceKey, Long> ownedRemaining;
        private int portSequence;
        private int edgeSequence;
        private int rawSequence;
        private int ownedSequence;

        private Mapping(
                CandidatePlan candidate,
                MachineCapabilityCatalog capabilityCatalog,
                PlanningContext context) {
            this.candidate = candidate;
            this.capabilityCatalog = capabilityCatalog;
            this.context = context;
        }

        private LogicalGraphMappingResult map() {
            Optional<LogicalGraphMappingFailure> invalid = validateCandidate();
            if (invalid.isPresent()) {
                return failureResult(invalid.get());
            }
            if (candidate.goal().quantity() > ProcessResource.MAX_AMOUNT) {
                return failureResult(failure(
                        LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW,
                        LogicalGraphMappingStage.TARGET_RESOLUTION,
                        Optional.empty(), Optional.empty(), Optional.of(candidate.goal().target()),
                        "Target quantity exceeds the bounded logical port amount"));
            }

            rawTotals = resourceMap(candidate.rawMaterials());
            rawRemaining = new LinkedHashMap<>(rawTotals);
            ownedTotals = resourceMap(candidate.ownedResourcesUsed());
            ownedRemaining = new LinkedHashMap<>(ownedTotals);

            Optional<LogicalGraphMappingFailure> processFailure = buildProcesses();
            if (processFailure.isPresent()) {
                return failureResult(processFailure.get());
            }
            Optional<LogicalGraphMappingFailure> dependencyFailure = connectDependencies();
            if (dependencyFailure.isPresent()) {
                return failureResult(dependencyFailure.get());
            }
            Optional<LogicalGraphMappingFailure> allocationFailure = connectExternalInputs();
            if (allocationFailure.isPresent()) {
                return failureResult(allocationFailure.get());
            }
            Optional<LogicalGraphMappingFailure> targetFailure = connectTarget();
            if (targetFailure.isPresent()) {
                return failureResult(targetFailure.get());
            }
            Optional<LogicalGraphMappingFailure> leftovers = validateNoExternalLeftovers();
            if (leftovers.isPresent()) {
                return failureResult(leftovers.get());
            }
            if (nodes.size() > LogicalMachineGraph.MAX_NODES
                    || ports.size() > LogicalMachineGraph.MAX_PORTS
                    || edges.size() > LogicalMachineGraph.MAX_EDGES) {
                return failureResult(failure(
                        LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW,
                        LogicalGraphMappingStage.RESOURCE_ALLOCATION,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        "Mapped logical graph exceeds bounded node, port, or edge capacity"));
            }

            return new LogicalGraphMappingSuccess(new LogicalMachineGraph(
                    ResourceId.parse("planning:logical_" + candidate.candidateId().path()),
                    nodes,
                    ports,
                    edges));
        }

        private Optional<LogicalGraphMappingFailure> validateCandidate() {
            if (hasDuplicateResourceKeys(candidate.rawMaterials())
                    || hasDuplicateResourceKeys(candidate.intermediateResources())
                    || hasDuplicateResourceKeys(candidate.ownedResourcesUsed())) {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                        LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        "Candidate resource sections must use unique resource identities"));
            }

            Set<ResourceId> requiredCapabilities = new TreeSet<>(
                    java.util.Comparator.comparing(ResourceId::toString));
            Map<ResourceId, UnboundMachineNode> nodeByStep = new LinkedHashMap<>();
            for (int index = 0; index < candidate.processingOrder().size(); index++) {
                ResourceId stepId = candidate.processingOrder().get(index);
                CatalogRecipe recipe = candidate.selectedRecipes().get(index);
                CandidateQuantityConversion conversion = candidate.quantityConversions().get(index);
                UnboundMachineNode node = candidate.unboundMachineNodes().get(index);
                if (!stepId.equals(conversion.stepId())
                        || !stepId.equals(node.stepId())
                        || !recipe.recipeId().equals(conversion.recipeId())
                        || !recipe.requiredMachineCapabilities().equals(node.requiredCapabilities())) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                            LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                            Optional.of(stepId), Optional.of(node.nodeId()), Optional.empty(),
                            "Recipe, processing order, quantity conversion, and logical node disagree"));
                }
                if (nodeByStep.putIfAbsent(stepId, node) != null) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                            LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                            Optional.of(stepId), Optional.of(node.nodeId()), Optional.empty(),
                            "Candidate contains a duplicate processing step"));
                }
                requiredCapabilities.addAll(recipe.requiredMachineCapabilities());

                ScaleStatus scaleStatus = scaleStatus(recipe.inputs(), conversion.inputs(),
                        conversion.executions());
                scaleStatus = scaleStatus == ScaleStatus.MATCH
                        ? scaleStatus(recipe.outputs(), conversion.outputs(), conversion.executions())
                        : scaleStatus;
                scaleStatus = scaleStatus == ScaleStatus.MATCH
                        ? scaleStatus(
                                recipe.optionalByproducts(),
                                conversion.byproducts(),
                                conversion.executions())
                        : scaleStatus;
                if (scaleStatus != ScaleStatus.MATCH) {
                    return Optional.of(failure(
                            scaleStatus == ScaleStatus.OVERFLOW
                                    ? LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW
                                    : LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                            LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                            Optional.of(stepId), Optional.of(node.nodeId()), Optional.empty(),
                            scaleStatus == ScaleStatus.OVERFLOW
                                    ? "Scaled recipe quantity exceeds the bounded logical amount"
                                    : "Candidate quantities do not equal recipe quantities times executions"));
                }
            }
            if (!requiredCapabilities.equals(candidate.requiredMachineCapabilities())) {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                        LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        "Candidate capability summary does not equal its recipe requirements"));
            }

            Map<ResourceKey, Long> dependencyTotals = new LinkedHashMap<>();
            for (ProcessDependencyEdge dependency : candidate.dependencies()) {
                if (!nodeByStep.containsKey(dependency.producerStepId())
                        || !nodeByStep.containsKey(dependency.consumerStepId())) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                            LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                            Optional.of(dependency.consumerStepId()), Optional.empty(),
                            Optional.of(dependency.resource().resourceId()),
                            "Dependency references a step outside the candidate"));
                }
                ResourceKey key = ResourceKey.of(dependency.resource());
                try {
                    dependencyTotals.merge(key, dependency.resource().amount(), Math::addExact);
                } catch (ArithmeticException exception) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW,
                            LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                            Optional.of(dependency.consumerStepId()), Optional.empty(),
                            Optional.of(dependency.resource().resourceId()),
                            "Intermediate dependency quantity overflowed"));
                }
            }
            if (!dependencyTotals.equals(resourceMap(candidate.intermediateResources()))) {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                        LogicalGraphMappingStage.CANDIDATE_VALIDATION,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        "Intermediate resource summary does not equal dependency quantities"));
            }
            return Optional.empty();
        }

        private Optional<LogicalGraphMappingFailure> buildProcesses() {
            for (int index = 0; index < candidate.processingOrder().size(); index++) {
                ResourceId stepId = candidate.processingOrder().get(index);
                CatalogRecipe recipe = candidate.selectedRecipes().get(index);
                CandidateQuantityConversion conversion = candidate.quantityConversions().get(index);
                UnboundMachineNode unbound = candidate.unboundMachineNodes().get(index);
                Map<GenericResourceType, RequirementAggregate> requirements = new EnumMap<>(
                        GenericResourceType.class);

                for (ResourceId capabilityId : recipe.requiredMachineCapabilities()) {
                    Optional<MachineCapability> found = capabilityCatalog.find(capabilityId);
                    if (found.isEmpty()) {
                        return Optional.of(failure(
                                LogicalGraphMappingFailureCode.MACHINE_CAPABILITY_MISSING,
                                LogicalGraphMappingStage.CAPABILITY_RESOLUTION,
                                Optional.of(stepId), Optional.of(unbound.nodeId()),
                                Optional.of(capabilityId),
                                "Required machine capability is absent from the capability catalog"));
                    }
                    MachineCapability capability = found.get();
                    if (!capability.isCompatibleWith(recipe)) {
                        return Optional.of(failure(
                                LogicalGraphMappingFailureCode.MACHINE_CAPABILITY_INCOMPATIBLE,
                                LogicalGraphMappingStage.CAPABILITY_RESOLUTION,
                                Optional.of(stepId), Optional.of(unbound.nodeId()),
                                Optional.of(capabilityId),
                                "Catalog capability does not satisfy the selected recipe contract"));
                    }
                    if (!context.availableAdapterIds().contains(capability.adapterId())) {
                        return Optional.of(failure(
                                LogicalGraphMappingFailureCode.CAPABILITY_ADAPTER_UNAVAILABLE,
                                LogicalGraphMappingStage.CAPABILITY_RESOLUTION,
                                Optional.of(stepId), Optional.of(unbound.nodeId()),
                                Optional.of(capability.adapterId()),
                                "Capability Adapter is not declared available in the planning context"));
                    }
                    for (CapabilityResourceRequirement requirement : capability.requiredResources()) {
                        RequirementAggregate aggregate = requirements.computeIfAbsent(
                                requirement.resourceType(), ignored -> new RequirementAggregate());
                        try {
                            aggregate.amount = Math.addExact(
                                    aggregate.amount, requirement.minimumAmount());
                        } catch (ArithmeticException exception) {
                            return Optional.of(quantityOverflow(
                                    stepId, unbound.nodeId(), requirement.resourceType()));
                        }
                        if (aggregate.amount > CapabilityResourceRequirement.MAXIMUM_AMOUNT) {
                            return Optional.of(quantityOverflow(
                                    stepId, unbound.nodeId(), requirement.resourceType()));
                        }
                        aggregate.continuous |= requirement.continuous();
                    }
                }
                for (GenericResourceType requiredType : recipe.requiredResourceTypes()) {
                    if (!context.supportedResourceTypes().contains(requiredType)) {
                        return Optional.of(failure(
                                LogicalGraphMappingFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                                LogicalGraphMappingStage.CAPABILITY_RESOLUTION,
                                Optional.of(stepId), Optional.of(unbound.nodeId()), Optional.empty(),
                                "Planning context does not support resource type "
                                        + requiredType.serializedName()));
                    }
                }

                Set<CapabilityResourceRequirement> logicalRequirements = new LinkedHashSet<>();
                requirements.forEach((type, aggregate) -> logicalRequirements.add(
                        new CapabilityResourceRequirement(
                                type, aggregate.amount, aggregate.continuous)));
                LogicalGraphNode process = new LogicalGraphNode(
                        unbound.nodeId(),
                        LogicalNodeKind.PROCESS,
                        Optional.of(stepId),
                        unbound.requiredCapabilities(),
                        logicalRequirements);
                nodes.add(process);
                for (ProcessResource input : conversion.inputs()) {
                    LogicalPortRequirement port = processPort(
                            process.id(), input, PortMode.INPUT, LogicalPortRole.PROCESS_INPUT);
                    ports.add(port);
                    inputs.put(new StepResourceKey(stepId, ResourceKey.of(input)), port);
                }
                for (ProcessResource output : conversion.outputs()) {
                    addProcessOutput(stepId, process.id(), output);
                }
                for (ProcessResource byproduct : conversion.byproducts()) {
                    addProcessOutput(stepId, process.id(), byproduct);
                }
            }
            return Optional.empty();
        }

        private void addProcessOutput(
                ResourceId stepId,
                ResourceId nodeId,
                ProcessResource resource) {
            LogicalPortRequirement port = processPort(
                    nodeId, resource, PortMode.OUTPUT, LogicalPortRole.PROCESS_OUTPUT);
            ports.add(port);
            outputs.put(new StepResourceKey(stepId, ResourceKey.of(resource)), port);
        }

        private Optional<LogicalGraphMappingFailure> connectDependencies() {
            for (ProcessDependencyEdge dependency : candidate.dependencies()) {
                ResourceKey resource = ResourceKey.of(dependency.resource());
                LogicalPortRequirement source = outputs.get(
                        new StepResourceKey(dependency.producerStepId(), resource));
                LogicalPortRequirement target = inputs.get(
                        new StepResourceKey(dependency.consumerStepId(), resource));
                if (source == null || target == null) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                            LogicalGraphMappingStage.RESOURCE_ALLOCATION,
                            Optional.of(dependency.consumerStepId()), Optional.empty(),
                            Optional.of(resource.resourceId()),
                            "Dependency resource is absent from a producer output or consumer input"));
                }
                long amount = dependency.resource().amount();
                if (!reserve(source, sourceAllocations, amount)
                        || !reserve(target, targetAllocations, amount)) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.INPUT_ALLOCATION_UNRESOLVED,
                            LogicalGraphMappingStage.RESOURCE_ALLOCATION,
                            Optional.of(dependency.consumerStepId()), Optional.of(target.nodeId()),
                            Optional.of(resource.resourceId()),
                            "Intermediate dependency exceeds producer output or consumer input"));
                }
                edges.add(edge(source, target, amount, LogicalEdgeKind.INTERMEDIATE));
            }
            return Optional.empty();
        }

        private Optional<LogicalGraphMappingFailure> connectExternalInputs() {
            for (Map.Entry<StepResourceKey, LogicalPortRequirement> entry : inputs.entrySet()) {
                LogicalPortRequirement target = entry.getValue();
                long remaining = target.amount()
                        - targetAllocations.getOrDefault(target.id(), 0L);
                remaining = allocateExternal(
                        target, entry.getKey().stepId(), remaining,
                        LogicalNodeKind.OWNED_RESOURCE_SOURCE,
                        LogicalEdgeKind.OWNED_INPUT,
                        ownedTotals,
                        ownedRemaining);
                remaining = allocateExternal(
                        target, entry.getKey().stepId(), remaining,
                        LogicalNodeKind.RAW_RESOURCE_SOURCE,
                        LogicalEdgeKind.RAW_INPUT,
                        rawTotals,
                        rawRemaining);
                if (remaining != 0) {
                    return Optional.of(failure(
                            LogicalGraphMappingFailureCode.INPUT_ALLOCATION_UNRESOLVED,
                            LogicalGraphMappingStage.RESOURCE_ALLOCATION,
                            Optional.of(entry.getKey().stepId()), Optional.of(target.nodeId()),
                            Optional.of(target.resourceId()),
                            "Raw, owned, and intermediate quantities do not cover the process input"));
                }
            }
            return Optional.empty();
        }

        private long allocateExternal(
                LogicalPortRequirement target,
                ResourceId stepId,
                long needed,
                LogicalNodeKind sourceKind,
                LogicalEdgeKind edgeKind,
                Map<ResourceKey, Long> totals,
                Map<ResourceKey, Long> remaining) {
            if (needed == 0) {
                return 0;
            }
            ResourceKey key = new ResourceKey(target.resourceId(), target.resourceType());
            long available = remaining.getOrDefault(key, 0L);
            long allocated = Math.min(available, needed);
            if (allocated == 0) {
                return needed;
            }
            LogicalPortRequirement source = boundarySupply(sourceKind, key, totals.get(key));
            if (!reserve(source, sourceAllocations, allocated)
                    || !reserve(target, targetAllocations, allocated)) {
                throw new IllegalStateException(
                        "Internal logical allocation exceeded a validated endpoint for step " + stepId);
            }
            edges.add(edge(source, target, allocated, edgeKind));
            remaining.put(key, available - allocated);
            return needed - allocated;
        }

        private Optional<LogicalGraphMappingFailure> connectTarget() {
            ResourceKey targetResource = new ResourceKey(
                    candidate.goal().target(), candidate.goal().targetResourceType());
            long quantity = candidate.goal().quantity();
            List<LogicalPortRequirement> matchingOutputs = outputs.values().stream()
                    .filter(port -> port.resourceId().equals(targetResource.resourceId())
                            && port.resourceType() == targetResource.resourceType())
                    .filter(port -> port.amount()
                            - sourceAllocations.getOrDefault(port.id(), 0L) >= quantity)
                    .toList();

            LogicalPortRequirement source;
            if (matchingOutputs.size() > 1) {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.TARGET_OUTPUT_AMBIGUOUS,
                        LogicalGraphMappingStage.TARGET_RESOLUTION,
                        Optional.empty(), Optional.empty(), Optional.of(targetResource.resourceId()),
                        "Multiple process outputs can independently satisfy the target quantity"));
            } else if (matchingOutputs.size() == 1) {
                source = matchingOutputs.get(0);
            } else if (candidate.processingOrder().isEmpty()
                    && ownedRemaining.getOrDefault(targetResource, 0L) >= quantity) {
                source = boundarySupply(
                        LogicalNodeKind.OWNED_RESOURCE_SOURCE,
                        targetResource,
                        ownedTotals.get(targetResource));
                ownedRemaining.put(
                        targetResource,
                        ownedRemaining.get(targetResource) - quantity);
            } else {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.TARGET_OUTPUT_MISSING,
                        LogicalGraphMappingStage.TARGET_RESOLUTION,
                        Optional.empty(), Optional.empty(), Optional.of(targetResource.resourceId()),
                        "No unique process output or fully owned resource can satisfy the target"));
            }

            LogicalGraphNode targetNode = new LogicalGraphNode(
                    ResourceId.parse("planning:logical_target"),
                    LogicalNodeKind.TARGET_SINK,
                    Optional.empty(),
                    Set.of(),
                    Set.of());
            nodes.add(targetNode);
            LogicalPortRequirement demand = new LogicalPortRequirement(
                    nextPortId(),
                    targetNode.id(),
                    targetResource.resourceId(),
                    targetResource.resourceType(),
                    PortMode.INPUT,
                    LogicalPortRole.TARGET_DEMAND,
                    quantity);
            ports.add(demand);
            if (!reserve(source, sourceAllocations, quantity)
                    || !reserve(demand, targetAllocations, quantity)) {
                throw new IllegalStateException(
                        "Internal target allocation exceeded a validated endpoint");
            }
            edges.add(edge(source, demand, quantity, LogicalEdgeKind.TARGET_OUTPUT));
            return Optional.empty();
        }

        private Optional<LogicalGraphMappingFailure> validateNoExternalLeftovers() {
            Optional<ResourceKey> raw = rawRemaining.entrySet().stream()
                    .filter(entry -> entry.getValue() != 0)
                    .map(Map.Entry::getKey)
                    .findFirst();
            Optional<ResourceKey> owned = ownedRemaining.entrySet().stream()
                    .filter(entry -> entry.getValue() != 0)
                    .map(Map.Entry::getKey)
                    .findFirst();
            Optional<ResourceKey> leftover = raw.isPresent() ? raw : owned;
            if (leftover.isPresent()) {
                return Optional.of(failure(
                        LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT,
                        LogicalGraphMappingStage.RESOURCE_ALLOCATION,
                        Optional.empty(), Optional.empty(),
                        Optional.of(leftover.get().resourceId()),
                        "Candidate declares an external resource quantity that no logical flow uses"));
            }
            return Optional.empty();
        }

        private LogicalPortRequirement boundarySupply(
                LogicalNodeKind kind,
                ResourceKey resource,
                long totalAmount) {
            BoundaryKey key = new BoundaryKey(kind, resource);
            LogicalPortRequirement existing = boundarySupplies.get(key);
            if (existing != null) {
                return existing;
            }
            int sequence = kind == LogicalNodeKind.RAW_RESOURCE_SOURCE
                    ? ++rawSequence
                    : ++ownedSequence;
            String prefix = kind == LogicalNodeKind.RAW_RESOURCE_SOURCE ? "raw" : "owned";
            LogicalGraphNode node = new LogicalGraphNode(
                    ResourceId.parse("planning:logical_" + prefix + "_"
                            + String.format("%04d", sequence)),
                    kind,
                    Optional.empty(),
                    Set.of(),
                    Set.of());
            nodes.add(node);
            LogicalPortRequirement port = new LogicalPortRequirement(
                    nextPortId(),
                    node.id(),
                    resource.resourceId(),
                    resource.resourceType(),
                    PortMode.OUTPUT,
                    LogicalPortRole.EXTERNAL_SUPPLY,
                    totalAmount);
            ports.add(port);
            boundarySupplies.put(key, port);
            return port;
        }

        private LogicalPortRequirement processPort(
                ResourceId nodeId,
                ProcessResource resource,
                PortMode mode,
                LogicalPortRole role) {
            return new LogicalPortRequirement(
                    nextPortId(),
                    nodeId,
                    resource.resourceId(),
                    resource.resourceType(),
                    mode,
                    role,
                    resource.amount());
        }

        private LogicalResourceEdge edge(
                LogicalPortRequirement source,
                LogicalPortRequirement target,
                long amount,
                LogicalEdgeKind kind) {
            return new LogicalResourceEdge(
                    ResourceId.parse("planning:logical_edge_"
                            + String.format("%04d", ++edgeSequence)),
                    source.id(),
                    target.id(),
                    source.resourceId(),
                    source.resourceType(),
                    amount,
                    kind);
        }

        private ResourceId nextPortId() {
            return ResourceId.parse("planning:logical_port_"
                    + String.format("%04d", ++portSequence));
        }

        private LogicalGraphMappingFailure quantityOverflow(
                ResourceId stepId,
                ResourceId nodeId,
                GenericResourceType type) {
            return failure(
                    LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW,
                    LogicalGraphMappingStage.CAPABILITY_RESOLUTION,
                    Optional.of(stepId), Optional.of(nodeId), Optional.empty(),
                    "Aggregated capability requirement exceeds the bounded amount for "
                            + type.serializedName());
        }

        private LogicalGraphMappingFailure failure(
                LogicalGraphMappingFailureCode code,
                LogicalGraphMappingStage stage,
                Optional<ResourceId> stepId,
                Optional<ResourceId> nodeId,
                Optional<ResourceId> resourceId,
                String detail) {
            LinkedHashSet<ResourceId> trace = new LinkedHashSet<>();
            trace.add(candidate.candidateId());
            stepId.ifPresent(trace::add);
            nodeId.ifPresent(trace::add);
            resourceId.ifPresent(trace::add);
            return new LogicalGraphMappingFailure(
                    candidate.candidateId(),
                    code,
                    stage,
                    stepId,
                    nodeId,
                    resourceId,
                    List.copyOf(trace),
                    detail);
        }

        private static boolean reserve(
                LogicalPortRequirement port,
                Map<ResourceId, Long> allocations,
                long amount) {
            long allocated = allocations.getOrDefault(port.id(), 0L);
            long next;
            try {
                next = Math.addExact(allocated, amount);
            } catch (ArithmeticException exception) {
                return false;
            }
            if (next > port.amount()) {
                return false;
            }
            allocations.put(port.id(), next);
            return true;
        }

        private static ScaleStatus scaleStatus(
                List<ProcessResource> recipeValues,
                List<ProcessResource> candidateValues,
                long executions) {
            if (recipeValues.size() != candidateValues.size()) {
                return ScaleStatus.MISMATCH;
            }
            for (int index = 0; index < recipeValues.size(); index++) {
                ProcessResource recipe = recipeValues.get(index);
                ProcessResource candidate = candidateValues.get(index);
                long expected;
                try {
                    expected = Math.multiplyExact(recipe.amount(), executions);
                } catch (ArithmeticException exception) {
                    return ScaleStatus.OVERFLOW;
                }
                if (expected > ProcessResource.MAX_AMOUNT) {
                    return ScaleStatus.OVERFLOW;
                }
                if (!recipe.resourceId().equals(candidate.resourceId())
                        || recipe.resourceType() != candidate.resourceType()
                        || expected != candidate.amount()) {
                    return ScaleStatus.MISMATCH;
                }
            }
            return ScaleStatus.MATCH;
        }

        private static boolean hasDuplicateResourceKeys(List<ProcessResource> values) {
            Set<ResourceKey> keys = new LinkedHashSet<>();
            for (ProcessResource value : values) {
                if (!keys.add(ResourceKey.of(value))) {
                    return true;
                }
            }
            return false;
        }

        private static Map<ResourceKey, Long> resourceMap(List<ProcessResource> values) {
            Map<ResourceKey, Long> resources = new LinkedHashMap<>();
            for (ProcessResource value : values) {
                resources.put(ResourceKey.of(value), value.amount());
            }
            return resources;
        }
    }

    private enum ScaleStatus {
        MATCH,
        MISMATCH,
        OVERFLOW
    }

    private static final class RequirementAggregate {
        private long amount;
        private boolean continuous;
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private ResourceKey {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(resourceType, "resourceType");
        }

        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }

    private record StepResourceKey(ResourceId stepId, ResourceKey resource) {}

    private record BoundaryKey(LogicalNodeKind kind, ResourceKey resource) {}

    private static LogicalGraphMappingFailureResult failureResult(
            LogicalGraphMappingFailure failure) {
        return new LogicalGraphMappingFailureResult(failure);
    }
}
