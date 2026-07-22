package dev.stevecreate.agent.core.layout;

import java.util.Objects;

public record PhysicalizationVerificationEvidence(
        PhysicalizationVerificationCheck check,
        String detail) {
    public PhysicalizationVerificationEvidence {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("detail is blank");
    }
}
