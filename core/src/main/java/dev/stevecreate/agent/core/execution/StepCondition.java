package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Typed condition descriptor evaluated later by a registered handler, never as free-form code. */
public record StepCondition(
        ResourceId conditionId,
        ResourceId evaluatorId,
        ResourceId conditionType,
        Map<ResourceId, String> parameters) {
    public StepCondition {
        Objects.requireNonNull(conditionId, "conditionId");
        Objects.requireNonNull(evaluatorId, "evaluatorId");
        Objects.requireNonNull(conditionType, "conditionType");
        parameters = ExecutionModelValues.copyParameters(parameters, "condition parameters");
    }
}
