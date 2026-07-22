package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable rule result that retains the complete evidence records behind its status. */
public record GenericVerificationResult(
        GenericVerificationStatus status,
        List<VerificationEvidence> consideredEvidence,
        Set<ResourceId> satisfiedRequiredEvidence,
        Set<ResourceId> missingRequiredEvidence,
        Set<ResourceId> observedOptionalEvidence,
        Optional<VerificationRuleFailure> failure,
        long evaluatedTick) {
    public static final int MAX_CONSIDERED_EVIDENCE = GenericVerificationRule.MAX_OBSERVATIONS;

    public GenericVerificationResult {
        Objects.requireNonNull(status, "status");
        consideredEvidence = copyEvidence(consideredEvidence);
        satisfiedRequiredEvidence = copyIds(
                satisfiedRequiredEvidence, "satisfiedRequiredEvidence");
        missingRequiredEvidence = copyIds(
                missingRequiredEvidence, "missingRequiredEvidence");
        observedOptionalEvidence = copyIds(
                observedOptionalEvidence, "observedOptionalEvidence");
        failure = Objects.requireNonNull(failure, "failure");
        if (evaluatedTick < 0) {
            throw new IllegalArgumentException("evaluatedTick must not be negative");
        }
        validateStatus(status, missingRequiredEvidence, failure);
    }

    private static List<VerificationEvidence> copyEvidence(List<VerificationEvidence> values) {
        Objects.requireNonNull(values, "consideredEvidence");
        if (values.size() > MAX_CONSIDERED_EVIDENCE) {
            throw new IllegalArgumentException(
                    "consideredEvidence count exceeds " + MAX_CONSIDERED_EVIDENCE);
        }
        List<VerificationEvidence> copy = new ArrayList<>(values.size());
        for (VerificationEvidence value : values) {
            copy.add(Objects.requireNonNull(value, "consideredEvidence element"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Set<ResourceId> copyIds(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<ResourceId> copy = new LinkedHashSet<>();
        for (ResourceId value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void validateStatus(
            GenericVerificationStatus status,
            Set<ResourceId> missingRequiredEvidence,
            Optional<VerificationRuleFailure> failure) {
        switch (status) {
            case PASSED -> {
                if (!missingRequiredEvidence.isEmpty() || failure.isPresent()) {
                    throw new IllegalArgumentException(
                            "A passed result cannot have missing evidence or a failure");
                }
            }
            case PENDING -> {
                if (missingRequiredEvidence.isEmpty() || failure.isPresent()) {
                    throw new IllegalArgumentException(
                            "A pending result requires missing evidence and no failure");
                }
            }
            case FAILED, TIMED_OUT -> {
                if (failure.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A failed or timed-out result requires a typed failure");
                }
            }
        }
    }
}
