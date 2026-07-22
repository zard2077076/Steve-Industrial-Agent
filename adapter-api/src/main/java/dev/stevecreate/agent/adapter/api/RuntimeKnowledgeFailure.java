package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete loader-neutral failure or per-recipe limitation with deterministic trace evidence. */
public record RuntimeKnowledgeFailure(
        RuntimeKnowledgeFailureCode code,
        RuntimeKnowledgeStage stage,
        Optional<ResourceId> recipeId,
        Optional<ResourceId> targetResource,
        Optional<String> ingredientIdentity,
        ResourceId adapterId,
        String runtimeFingerprint,
        List<String> trace,
        String detail) {
    public static final int MAX_TRACE_ENTRIES = 64;
    public static final int MAX_TRACE_ENTRY_LENGTH = 512;
    public static final int MAX_DETAIL_LENGTH = 2_048;
    public static final int MAX_FINGERPRINT_LENGTH = 2_048;

    public RuntimeKnowledgeFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        recipeId = copyOptional(recipeId, "recipeId");
        targetResource = copyOptional(targetResource, "targetResource");
        ingredientIdentity = copyOptionalText(
                ingredientIdentity, "ingredientIdentity", MAX_TRACE_ENTRY_LENGTH);
        Objects.requireNonNull(adapterId, "adapterId");
        runtimeFingerprint = requireText(
                runtimeFingerprint, "runtimeFingerprint", MAX_FINGERPRINT_LENGTH);
        trace = copyTrace(trace);
        detail = requireText(detail, "detail", MAX_DETAIL_LENGTH);
    }

    private static <T> Optional<T> copyOptional(Optional<T> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(element -> Objects.requireNonNull(element, name + " value"));
        return value;
    }

    private static Optional<String> copyOptionalText(
            Optional<String> value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        return value.map(text -> requireText(text, name, maximumLength));
    }

    private static List<String> copyTrace(List<String> values) {
        Objects.requireNonNull(values, "trace");
        if (values.isEmpty() || values.size() > MAX_TRACE_ENTRIES) {
            throw new IllegalArgumentException(
                    "trace count must be between 1 and " + MAX_TRACE_ENTRIES);
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(requireText(value, "trace element", MAX_TRACE_ENTRY_LENGTH));
        }
        return List.copyOf(copy);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
