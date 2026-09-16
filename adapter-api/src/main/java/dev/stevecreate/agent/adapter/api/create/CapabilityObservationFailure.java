package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Typed bounded diagnostic from a read-only capability observer. */
public record CapabilityObservationFailure(
        CapabilityObservationFailureCode code,
        CreateCapabilityId capability,
        ResourceId recipeId,
        ResourceId assignmentId,
        String field,
        String detail,
        boolean retryable,
        List<String> trace) {
    public CapabilityObservationFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        field = requireText(field, "field", 128);
        detail = requireText(detail, "detail", 1_024);
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (trace.isEmpty() || trace.size() > 32
                || trace.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 256)) {
            throw new IllegalArgumentException("trace must contain 1..32 bounded entries");
        }
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
