package dev.stevecreate.agent.core.execution.readiness;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ExecutionReadinessFailure(
        ExecutionReadinessFailureCode code,
        ExecutionReadinessStage stage,
        ResourceId physicalPlanId,
        ResourceId sessionId,
        Optional<BlockPos3i> position,
        Optional<ResourceId> resourceId,
        String detail,
        Map<String, String> context,
        List<String> trace,
        String safeNextStep) {
    public ExecutionReadinessFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(physicalPlanId, "physicalPlanId");
        Objects.requireNonNull(sessionId, "sessionId");
        position = Objects.requireNonNull(position, "position");
        resourceId = Objects.requireNonNull(resourceId, "resourceId");
        detail = text(detail, "detail", 2_048);
        context = Map.copyOf(Objects.requireNonNull(context, "context"));
        if (context.isEmpty() || context.size() > 64
                || context.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || entry.getValue() == null || entry.getKey().isBlank()
                        || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("Readiness failure context is incomplete");
        }
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (trace.isEmpty() || trace.size() > 256
                || trace.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Readiness failure trace is incomplete");
        }
        safeNextStep = text(safeNextStep, "safeNextStep", 1_024);
    }

    private static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
