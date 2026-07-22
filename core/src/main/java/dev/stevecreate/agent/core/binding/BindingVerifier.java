package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.LogicalGraphNode;
import dev.stevecreate.agent.core.planning.LogicalMachineGraph;
import dev.stevecreate.agent.core.planning.LogicalNodeKind;
import dev.stevecreate.agent.core.planning.LogicalPortRequirement;
import dev.stevecreate.agent.core.planning.LogicalPortRole;
import dev.stevecreate.agent.core.planning.LogicalResourceEdge;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Independently rechecks binding coverage and invariants before issuing a gate value. */
public final class BindingVerifier {
    public BindingVerificationResult verify(
            ImplementationBoundMachineGraph graph,
            MachineImplementationCatalog catalog,
            BindingConstraints constraints) {
        if (graph == null || catalog == null || constraints == null) {
            return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                    BindingStage.VERIFICATION, null, null, List.of(), constraints,
                    "verification_inputs=present");
        }
        if (!graph.id().path().equals("graph_" + graph.logicalPlan().id().path())) {
            return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                    BindingStage.VERIFICATION, null, null, List.of(), constraints,
                    "bound_graph_identity=logical_plan_identity");
        }
        LogicalMachineGraph logical = graph.logicalPlan().logicalGraph();
        CandidatePlan candidate = graph.logicalPlan().candidate();
        Map<ResourceId, StepData> steps = indexSteps(candidate);
        Set<ResourceId> expectedNodes = logical.nodes().values().stream()
                .filter(node -> node.kind() == LogicalNodeKind.PROCESS)
                .map(LogicalGraphNode::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expectedNodes.equals(graph.boundProcessNodes().keySet())) {
            ResourceId missing = expectedNodes.stream()
                    .filter(value -> !graph.boundProcessNodes().containsKey(value))
                    .findFirst().orElse(null);
            return failure(BindingFailureCode.LOGICAL_NODE_UNBOUND,
                    BindingStage.VERIFICATION, missing, null,
                    graph.boundProcessNodes().values().stream()
                            .map(BoundMachineNode::implementationId).toList(),
                    constraints, "process_node_coverage=exact");
        }
        if (!graph.runtimeFingerprint().equals(catalog.runtimeFingerprint())
                || !graph.runtimeFingerprint().equals(constraints.runtimeFingerprint())
                || graph.catalogReloadGeneration() != catalog.reloadGeneration()) {
            return failure(BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    BindingStage.VERIFICATION, null, null, List.of(), constraints,
                    "graph_catalog_constraint_runtime=equal");
        }

        for (Map.Entry<ResourceId, BoundMachineNode> entry : graph.boundProcessNodes().entrySet()) {
            LogicalGraphNode logicalNode = logical.nodes().get(entry.getKey());
            BoundMachineNode bound = entry.getValue();
            StepData step = steps.get(logicalNode.stepId().orElse(null));
            if (step == null || !bound.logicalNodeId().equals(logicalNode.id())
                    || !bound.stepId().equals(logicalNode.stepId().orElse(null))
                    || !bound.recipeId().equals(step.recipe().recipeId())
                    || !bound.recipeType().equals(step.recipe().recipeType())) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step == null ? null : step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "bound_node_identity=logical_step_recipe");
            }
            MachineImplementationDescriptor descriptor = catalog.find(bound.implementationId())
                    .orElse(null);
            if (descriptor == null) {
                return failure(BindingFailureCode.IMPLEMENTATION_NOT_FOUND,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "selected_implementation_in_catalog=true");
            }
            if (!bound.adapterId().equals(descriptor.adapterId())
                    || !bound.implementationFamily().equals(descriptor.implementationFamily())
                    || !bound.processingMode().equals(descriptor.processingMode())) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "bound_descriptor_identity=exact");
            }
            if (!descriptor.runtimeFingerprint().equals(graph.runtimeFingerprint())
                    || !bound.runtimeFingerprint().equals(graph.runtimeFingerprint())
                    || !step.recipe().source().runtimeFingerprint()
                            .equals(graph.runtimeFingerprint())) {
                return failure(BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "node_recipe_descriptor_runtime=graph_runtime");
            }
            if (!descriptor.capabilityIds().containsAll(logicalNode.requiredCapabilities())) {
                return failure(BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "implementation_capabilities_cover_node=true");
            }
            if (!descriptor.supportedRecipeTypes().contains(step.recipe().recipeType())) {
                return failure(BindingFailureCode.IMPLEMENTATION_RECIPE_TYPE_MISMATCH,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "implementation_recipe_type=selected_recipe_type");
            }
            if (!constraints.allowedAdapterIds().isEmpty()
                    && !constraints.allowedAdapterIds().contains(descriptor.adapterId())) {
                return failure(BindingFailureCode.IMPLEMENTATION_ADAPTER_UNAVAILABLE,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints, "adapter_allowed=true");
            }
            if (!Objects.equals(constraints.availableModVersions().get(descriptor.modId()),
                    descriptor.modVersion())) {
                return failure(BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "implementation_mod_version=runtime_mod_version");
            }
            if (!portsMatch(bound, logicalNode, logical, descriptor)) {
                return failure(BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "logical_port_binding=complete_and_compatible");
            }
            if (!CandidateImplementationGenerator.powerContractsMatch(descriptor, logicalNode)) {
                return failure(BindingFailureCode.IMPLEMENTATION_POWER_CONTRACT_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "power_contracts_cover_node=true");
            }
            if ((constraints.requirePhysicallyVerifiedExecution()
                    && descriptor.executionSupport()
                    != ImplementationExecutionSupport.PHYSICALLY_VERIFIED)
                    || !descriptor.verificationEvidence().containsAll(
                            constraints.requiredEvidence())) {
                return failure(BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "required_execution_evidence=present");
            }
            if ((constraints.requireBindingAllowed() && !descriptor.bindingAllowed())
                    || constraints.forbiddenImplementationIds().contains(
                            descriptor.implementationId())) {
                return failure(BindingFailureCode.IMPLEMENTATION_FORBIDDEN,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "implementation_policy=allowed");
            }
            if (!bound.quantityConversion().equals(step.conversion())) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "quantity_conversion=verified_candidate_conversion");
            }
            if (!ingredientsMatch(
                    bound.recipeInputs(), step.recipe(), graph.runtimeFingerprint())) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(bound.implementationId()), constraints,
                        "ingredient_identity_and_selection=preserved");
            }
            List<MachineImplementationDescriptor> eligible = independentlyEligible(
                    logicalNode, step.recipe(), logical, catalog, constraints);
            if (eligible.isEmpty()) {
                return failure(BindingFailureCode.IMPLEMENTATION_NOT_FOUND,
                        BindingStage.VERIFICATION, logicalNode.id(), step.recipe(),
                        List.of(), constraints, "eligible_implementation_count>0");
            }
            eligible = eligible.stream().sorted(Comparator
                    .comparing((MachineImplementationDescriptor value) ->
                            CandidateImplementationGenerator.scoreOf(value, constraints))
                    .thenComparing(value -> value.implementationId().toString())).toList();
            List<ResourceId> eligibleIds = eligible.stream()
                    .map(MachineImplementationDescriptor::implementationId).sorted(
                            Comparator.comparing(ResourceId::toString)).toList();
            if (!bound.implementationId().equals(eligible.get(0).implementationId())
                    || !bound.selectionScore().equals(CandidateImplementationGenerator.scoreOf(
                            eligible.get(0), constraints))
                    || !bound.consideredImplementationIds().equals(eligibleIds)) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.IMPLEMENTATION_SELECTION, logicalNode.id(), step.recipe(),
                        eligibleIds, constraints, "selection=deterministic_minimum_score");
            }
        }
        if (!isAcyclic(logical)) {
            return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                    BindingStage.VERIFICATION, null, null, List.of(), constraints,
                    "logical_resource_graph=acyclic");
        }

        List<BindingVerificationEvidence> evidence = new ArrayList<>();
        for (BindingVerificationCheck check : BindingVerificationCheck.values()) {
            evidence.add(new BindingVerificationEvidence(
                    check,
                    List.of(graph.id().toString(), "check:" + check.name().toLowerCase()),
                    "Independent implementation-binding check passed"));
        }
        return new BindingVerificationResult.Success(
                new VerifiedImplementationBoundPlan(graph, evidence));
    }

    private static boolean portsMatch(
            BoundMachineNode bound,
            LogicalGraphNode node,
            LogicalMachineGraph graph,
            MachineImplementationDescriptor descriptor) {
        List<LogicalPortRequirement> logicalPorts = graph.ports().values().stream()
                .filter(value -> value.nodeId().equals(node.id()))
                .filter(value -> value.role() == LogicalPortRole.PROCESS_INPUT
                        || value.role() == LogicalPortRole.PROCESS_OUTPUT)
                .toList();
        Set<ResourceId> expected = logicalPorts.stream().map(LogicalPortRequirement::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!expected.equals(bound.logicalToImplementationPorts().keySet())) {
            return false;
        }
        Map<ResourceId, Integer> uses = new HashMap<>();
        for (LogicalPortRequirement logical : logicalPorts) {
            ResourceId selectedId = bound.logicalToImplementationPorts().get(logical.id());
            ImplementationPortContract selected = descriptor.ports().stream()
                    .filter(value -> value.portId().equals(selectedId)).findFirst().orElse(null);
            if (selected == null || selected.resourceType().orElse(null) != logical.resourceType()
                    || (logical.mode().acceptsInput() && !selected.mode().acceptsInput())
                    || (logical.mode().providesOutput() && !selected.mode().providesOutput())) {
                return false;
            }
            int count = uses.merge(selectedId, 1, Integer::sum);
            if (count > 1 && !selected.multiplexable()) {
                return false;
            }
        }
        return true;
    }

    private static boolean ingredientsMatch(
            List<BoundRecipeInput> boundInputs,
            CatalogRecipe recipe,
            String runtimeFingerprint) {
        if (boundInputs.size() != recipe.inputs().size()) {
            return false;
        }
        Set<Integer> indexes = new HashSet<>();
        Map<ResourceKey, Long> actual = new LinkedHashMap<>();
        for (BoundRecipeInput input : boundInputs) {
            if (!input.recipeId().equals(recipe.recipeId()) || !indexes.add(input.inputIndex())
                    || !identityMatches(input, runtimeFingerprint)) {
                return false;
            }
            actual.put(new ResourceKey(input.selectedResource()), input.amount());
        }
        Map<ResourceKey, Long> expected = new LinkedHashMap<>();
        for (ProcessResource input : recipe.inputs()) {
            expected.put(new ResourceKey(input.resourceId()), input.amount());
        }
        return actual.equals(expected);
    }

    private static boolean identityMatches(
            BoundRecipeInput input,
            String runtimeFingerprint) {
        String identity = input.ingredientIdentity();
        String amount = "@" + input.amount();
        return switch (input.ingredientKind()) {
            case EXACT_RESOURCE -> identity.equals(
                    "exact:" + input.selectedResource() + amount);
            case ANY_OF_RESOURCES -> identity.startsWith("any_of:[")
                    && identity.endsWith("]" + amount)
                    && identity.contains(input.selectedResource().toString());
            case TAG_REFERENCE -> identity.startsWith("tag:")
                    && identity.contains("=[")
                    && identity.contains(input.selectedResource().toString())
                    && identity.endsWith(amount + "#" + runtimeFingerprint);
            case UNSUPPORTED_COMPLEX_INGREDIENT -> false;
        };
    }

    private static List<MachineImplementationDescriptor> independentlyEligible(
            LogicalGraphNode node,
            CatalogRecipe recipe,
            LogicalMachineGraph graph,
            MachineImplementationCatalog catalog,
            BindingConstraints constraints) {
        return catalog.implementations().stream()
                .filter(value -> value.capabilityIds().containsAll(node.requiredCapabilities()))
                .filter(value -> value.supportedRecipeTypes().contains(recipe.recipeType()))
                .filter(value -> constraints.allowedAdapterIds().isEmpty()
                        || constraints.allowedAdapterIds().contains(value.adapterId()))
                .filter(value -> value.runtimeFingerprint().equals(constraints.runtimeFingerprint()))
                .filter(value -> value.modVersion().equals(
                        constraints.availableModVersions().get(value.modId())))
                .filter(value -> CandidateImplementationGenerator.resourcePortsMatch(
                        value, node, graph))
                .filter(value -> CandidateImplementationGenerator.powerContractsMatch(value, node))
                .filter(value -> value.verificationEvidence().containsAll(
                        constraints.requiredEvidence()))
                .filter(value -> !constraints.requirePhysicallyVerifiedExecution()
                        || value.executionSupport()
                        == ImplementationExecutionSupport.PHYSICALLY_VERIFIED)
                .filter(value -> !constraints.requireBindingAllowed() || value.bindingAllowed())
                .filter(value -> !constraints.forbiddenImplementationIds().contains(
                        value.implementationId()))
                .toList();
    }

    private static boolean isAcyclic(LogicalMachineGraph graph) {
        Map<ResourceId, Integer> indegree = new LinkedHashMap<>();
        Map<ResourceId, Set<ResourceId>> adjacency = new LinkedHashMap<>();
        graph.nodes().keySet().forEach(id -> {
            indegree.put(id, 0);
            adjacency.put(id, new LinkedHashSet<>());
        });
        for (LogicalResourceEdge edge : graph.edges().values()) {
            ResourceId source = graph.port(edge.sourcePortId()).nodeId();
            ResourceId target = graph.port(edge.targetPortId()).nodeId();
            if (adjacency.get(source).add(target)) {
                indegree.merge(target, 1, Integer::sum);
            }
        }
        ArrayDeque<ResourceId> ready = new ArrayDeque<>();
        indegree.entrySet().stream().filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey).sorted(Comparator.comparing(ResourceId::toString))
                .forEach(ready::add);
        int visited = 0;
        while (!ready.isEmpty()) {
            ResourceId node = ready.removeFirst();
            visited++;
            adjacency.get(node).stream().sorted(Comparator.comparing(ResourceId::toString))
                    .forEach(target -> {
                        int next = indegree.merge(target, -1, Integer::sum);
                        if (next == 0) {
                            ready.addLast(target);
                        }
                    });
        }
        return visited == graph.nodes().size();
    }

    private static Map<ResourceId, StepData> indexSteps(CandidatePlan candidate) {
        Map<ResourceId, StepData> result = new LinkedHashMap<>();
        for (int index = 0; index < candidate.processingOrder().size(); index++) {
            result.put(candidate.processingOrder().get(index), new StepData(
                    candidate.selectedRecipes().get(index),
                    candidate.quantityConversions().get(index)));
        }
        return result;
    }

    private static BindingVerificationResult.Failure failure(
            BindingFailureCode code,
            BindingStage stage,
            ResourceId nodeId,
            CatalogRecipe recipe,
            List<ResourceId> candidates,
            BindingConstraints constraints,
            String constraint) {
        return new BindingVerificationResult.Failure(new BindingFailure(
                code,
                stage,
                Optional.ofNullable(nodeId),
                recipe == null ? Optional.empty()
                        : recipe.requiredMachineCapabilities().stream().findFirst(),
                recipe == null ? Optional.empty() : Optional.of(recipe.recipeId()),
                candidates,
                constraints == null ? Optional.empty() : constraints.preferredAdapterId(),
                constraints == null ? "runtime:unavailable" : constraints.runtimeFingerprint(),
                constraint,
                List.of("binding_verifier:" + code.name().toLowerCase()),
                "Independent binding verification rejected the graph",
                code == BindingFailureCode.IMPLEMENTATION_FORBIDDEN
                        || code == BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                "Rebuild the graph from the current verified plan and implementation snapshot"));
    }

    private record StepData(CatalogRecipe recipe, CandidateQuantityConversion conversion) {}

    private record ResourceKey(ResourceId id) {}
}
