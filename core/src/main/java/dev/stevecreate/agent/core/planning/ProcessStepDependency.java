package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.List;
import java.util.Objects;

/** One topologically ordered scaled recipe step in a dependency graph. */
public record ProcessStepDependency(
        ResourceId stepId,
        CatalogRecipe recipe,
        long executions,
        int depth,
        List<ProcessResource> scaledInputs,
        List<ProcessResource> scaledOutputs,
        List<ProcessResource> scaledByproducts) {
    public ProcessStepDependency {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(recipe, "recipe");
        if (executions <= 0 || executions > ProductionGoal.MAX_TARGET_QUANTITY) {
            throw new IllegalArgumentException("executions must be positive and bounded");
        }
        if (depth <= 0 || depth > ProductionGoal.MAX_PROCESSING_DEPTH) {
            throw new IllegalArgumentException("step depth violates planning bounds");
        }
        scaledInputs = copy(scaledInputs, "scaledInputs", true);
        scaledOutputs = copy(scaledOutputs, "scaledOutputs", true);
        scaledByproducts = copy(scaledByproducts, "scaledByproducts", false);
    }

    private static List<ProcessResource> copy(
            List<ProcessResource> values,
            String name,
            boolean required) {
        Objects.requireNonNull(values, name);
        if (required && values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        List<ProcessResource> copy = List.copyOf(values);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " element");
        }
        return copy;
    }
}
