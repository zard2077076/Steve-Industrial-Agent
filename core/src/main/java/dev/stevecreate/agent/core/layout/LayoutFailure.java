package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded diagnostic payload; it grants no execution or mutation authority. */
public record LayoutFailure(
        LayoutFailureCode code,
        LayoutStage stage,
        Optional<ResourceId> logicalNodeId,
        Optional<ResourceId> implementationId,
        String message,
        Map<String, String> context,
        List<String> trace,
        String nextStep) {
    public LayoutFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        logicalNodeId = Objects.requireNonNull(logicalNodeId, "logicalNodeId");
        implementationId = Objects.requireNonNull(implementationId, "implementationId");
        message = text(message, "message");
        Objects.requireNonNull(context, "context");
        if (context.size() > 32) throw new IllegalArgumentException("context exceeds 32 entries");
        context = Map.copyOf(context);
        Objects.requireNonNull(trace, "trace");
        if (trace.size() > 256) throw new IllegalArgumentException("trace exceeds 256 entries");
        trace = trace.stream().map(value -> text(value, "trace element")).toList();
        nextStep = text(nextStep, "nextStep");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
