package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.List;
import java.util.Objects;

/** Explicit scaled quantities selected for one candidate recipe step. */
public record CandidateQuantityConversion(
        ResourceId stepId,
        ResourceId recipeId,
        long executions,
        List<ProcessResource> inputs,
        List<ProcessResource> outputs,
        List<ProcessResource> byproducts) {
    public CandidateQuantityConversion {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(recipeId, "recipeId");
        if (executions <= 0 || executions > ProductionGoal.MAX_TARGET_QUANTITY) {
            throw new IllegalArgumentException("executions must be positive and bounded");
        }
        inputs = copy(inputs, "inputs", true);
        outputs = copy(outputs, "outputs", true);
        byproducts = copy(byproducts, "byproducts", false);
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
