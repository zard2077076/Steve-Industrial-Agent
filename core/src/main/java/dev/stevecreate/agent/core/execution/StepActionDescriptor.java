package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Typed routing data for an adapter/action handler; it contains no executable callback or code. */
public record StepActionDescriptor(
        ResourceId handlerId,
        ResourceId operationId,
        Map<ResourceId, String> parameters) {
    public StepActionDescriptor {
        Objects.requireNonNull(handlerId, "handlerId");
        Objects.requireNonNull(operationId, "operationId");
        parameters = ExecutionModelValues.copyParameters(parameters, "action parameters");
    }
}
