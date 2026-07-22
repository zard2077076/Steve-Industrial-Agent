package dev.stevecreate.agent.core.execution.readiness;

import java.util.Objects;

public record ExecutionReadinessEvidence(
        ExecutionReadinessVerificationCheck check,
        String detail) {
    public ExecutionReadinessEvidence {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > 2_048) {
            throw new IllegalArgumentException("Readiness evidence detail is blank or too long");
        }
    }
}
