package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.HashSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** Complete immutable coordinate-free candidate derived from one dependency graph. */
public record CandidatePlan(
        ResourceId candidateId,
        ProductionGoal goal,
        List<CatalogRecipe> selectedRecipes,
        Set<ResourceId> requiredMachineCapabilities,
        List<ProcessResource> rawMaterials,
        List<ProcessResource> intermediateResources,
        List<ProcessResource> ownedResourcesUsed,
        List<ResourceId> processingOrder,
        List<CandidateQuantityConversion> quantityConversions,
        List<ProcessDependencyEdge> dependencies,
        List<UnboundMachineNode> unboundMachineNodes,
        UnboundSpatialLayout unboundSpatialLayout,
        List<PlanningEvidence> planningEvidence,
        OptionalLong estimatedProcessingTicks) {
    public static final int MAX_EVIDENCE = 32_768;

    public CandidatePlan {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(goal, "goal");
        selectedRecipes = copy(selectedRecipes, "selectedRecipes", ProcessDependencyGraph.MAX_STEPS);
        Objects.requireNonNull(requiredMachineCapabilities, "requiredMachineCapabilities");
        TreeSet<ResourceId> capabilities = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ResourceId value : requiredMachineCapabilities) {
            capabilities.add(Objects.requireNonNull(value, "requiredMachineCapabilities element"));
        }
        requiredMachineCapabilities = Collections.unmodifiableSet(
                new LinkedHashSet<>(capabilities));
        rawMaterials = copy(
                rawMaterials, "rawMaterials", ProcessDependencyGraph.MAX_RESOURCES_PER_SECTION);
        intermediateResources = copy(
                intermediateResources,
                "intermediateResources",
                ProcessDependencyGraph.MAX_RESOURCES_PER_SECTION);
        ownedResourcesUsed = copy(
                ownedResourcesUsed,
                "ownedResourcesUsed",
                ProcessDependencyGraph.MAX_RESOURCES_PER_SECTION);
        processingOrder = copy(
                processingOrder, "processingOrder", ProcessDependencyGraph.MAX_STEPS);
        quantityConversions = copy(
                quantityConversions, "quantityConversions", ProcessDependencyGraph.MAX_STEPS);
        dependencies = copy(dependencies, "dependencies", ProcessDependencyGraph.MAX_EDGES);
        unboundMachineNodes = copy(
                unboundMachineNodes, "unboundMachineNodes", ProcessDependencyGraph.MAX_STEPS);
        unboundSpatialLayout = Objects.requireNonNull(
                unboundSpatialLayout, "unboundSpatialLayout");
        planningEvidence = copy(planningEvidence, "planningEvidence", MAX_EVIDENCE);
        estimatedProcessingTicks = Objects.requireNonNull(
                estimatedProcessingTicks, "estimatedProcessingTicks");

        if (selectedRecipes.size() != processingOrder.size()
                || quantityConversions.size() != processingOrder.size()
                || unboundMachineNodes.size() != processingOrder.size()) {
            throw new IllegalArgumentException(
                    "Candidate recipe/order/conversion/node counts must match");
        }
        Set<ResourceId> order = new HashSet<>(processingOrder);
        if (order.size() != processingOrder.size()) {
            throw new IllegalArgumentException("processingOrder contains duplicate step IDs");
        }
        List<ResourceId> nodeIds = unboundMachineNodes.stream()
                .map(UnboundMachineNode::nodeId)
                .toList();
        if (!nodeIds.equals(unboundSpatialLayout.nodeIds())) {
            throw new IllegalArgumentException("Unbound layout must cover candidate nodes exactly");
        }
        if (planningEvidence.isEmpty()) {
            throw new IllegalArgumentException("A candidate requires planning evidence");
        }
    }

    private static <T> List<T> copy(List<T> values, String name, int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        List<T> copy = List.copyOf(values);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " element");
        }
        return copy;
    }
}
