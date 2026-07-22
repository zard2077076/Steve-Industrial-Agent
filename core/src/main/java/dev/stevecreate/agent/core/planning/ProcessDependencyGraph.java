package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable coordinate-free dependency graph for one recipe-selection alternative. */
public record ProcessDependencyGraph(
        ProductionGoal goal,
        List<ProcessStepDependency> steps,
        List<ProcessDependencyEdge> edges,
        List<ProcessResource> rawMaterials,
        List<ProcessResource> ownedResourcesUsed,
        List<ProcessResource> byproducts,
        Optional<ResourceId> targetProducerStepId) {
    public static final int MAX_STEPS = 256;
    public static final int MAX_EDGES = 512;
    public static final int MAX_RESOURCES_PER_SECTION = 256;

    public ProcessDependencyGraph {
        Objects.requireNonNull(goal, "goal");
        steps = copy(steps, "steps", MAX_STEPS);
        edges = copy(edges, "edges", MAX_EDGES);
        rawMaterials = copy(rawMaterials, "rawMaterials", MAX_RESOURCES_PER_SECTION);
        ownedResourcesUsed = copy(
                ownedResourcesUsed, "ownedResourcesUsed", MAX_RESOURCES_PER_SECTION);
        byproducts = copy(byproducts, "byproducts", MAX_RESOURCES_PER_SECTION);
        targetProducerStepId = Objects.requireNonNull(targetProducerStepId, "targetProducerStepId");

        Set<ResourceId> stepIds = new HashSet<>();
        for (ProcessStepDependency step : steps) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("Duplicate step ID: " + step.stepId());
            }
        }
        for (ProcessDependencyEdge edge : edges) {
            if (!stepIds.contains(edge.producerStepId()) || !stepIds.contains(edge.consumerStepId())) {
                throw new IllegalArgumentException("Dependency edge references an unknown step");
            }
        }
        if (targetProducerStepId.isPresent() && !stepIds.contains(targetProducerStepId.get())) {
            throw new IllegalArgumentException("Target producer references an unknown step");
        }
        if (steps.isEmpty() != targetProducerStepId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a fully owned target may have no producer step");
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
