package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Bounded machine-readable verification failure with an optional requirement identity. */
public record VerificationRuleFailure(
        VerificationRuleFailureCode code,
        Optional<ResourceId> requirementId,
        String detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public VerificationRuleFailure {
        Objects.requireNonNull(code, "code");
        requirementId = Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "detail must contain 1 to " + MAX_DETAIL_LENGTH + " characters");
        }
    }
}
