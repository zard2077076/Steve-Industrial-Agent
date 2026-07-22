package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Typed, immutable, non-fatal warning attached to one mapped runtime recipe. */
public record RuntimeRecipeMappingWarning(
        ResourceId recipeId,
        RuntimeRecipeMappingWarningCode code,
        List<String> trace,
        String detail) {
    public RuntimeRecipeMappingWarning {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(trace, "trace");
        if (trace.isEmpty() || trace.size() > RuntimeKnowledgeFailure.MAX_TRACE_ENTRIES) {
            throw new IllegalArgumentException("warning trace count is outside its bound");
        }
        trace = trace.stream()
                .map(value -> requireText(
                        value, "trace element", RuntimeKnowledgeFailure.MAX_TRACE_ENTRY_LENGTH))
                .toList();
        detail = requireText(detail, "detail", RuntimeKnowledgeFailure.MAX_DETAIL_LENGTH);
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
