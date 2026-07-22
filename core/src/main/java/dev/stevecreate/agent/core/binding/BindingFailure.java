package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable context for a normal implementation-binding refusal. */
public record BindingFailure(
        BindingFailureCode code,
        BindingStage stage,
        Optional<ResourceId> logicalNodeId,
        Optional<ResourceId> capabilityId,
        Optional<ResourceId> recipeId,
        List<ResourceId> candidateImplementationIds,
        Optional<ResourceId> adapterId,
        String runtimeFingerprint,
        String constraint,
        List<String> trace,
        String detail,
        boolean userInterventionRequired,
        String safeNextStep) {
    public static final int MAX_CANDIDATES = 256;
    public static final int MAX_TRACE = 256;
    public static final int MAX_TEXT_LENGTH = 4_096;

    public BindingFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        logicalNodeId = Objects.requireNonNull(logicalNodeId, "logicalNodeId");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        recipeId = Objects.requireNonNull(recipeId, "recipeId");
        adapterId = Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(candidateImplementationIds, "candidateImplementationIds");
        if (candidateImplementationIds.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidateImplementationIds exceeds its bound");
        }
        List<ResourceId> sorted = new ArrayList<>(candidateImplementationIds.size());
        for (ResourceId value : candidateImplementationIds) {
            sorted.add(Objects.requireNonNull(value, "candidateImplementationIds element"));
        }
        sorted.sort(Comparator.comparing(ResourceId::toString));
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("candidateImplementationIds contains duplicates");
        }
        candidateImplementationIds = List.copyOf(sorted);
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        constraint = requireText(constraint, "constraint");
        Objects.requireNonNull(trace, "trace");
        if (trace.isEmpty() || trace.size() > MAX_TRACE) {
            throw new IllegalArgumentException("trace must be nonempty and bounded");
        }
        trace = trace.stream().map(value -> requireText(value, "trace element")).toList();
        detail = requireText(detail, "detail");
        safeNextStep = requireText(safeNextStep, "safeNextStep");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
