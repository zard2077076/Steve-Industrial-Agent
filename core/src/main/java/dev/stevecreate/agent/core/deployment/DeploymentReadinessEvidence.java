package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record DeploymentReadinessEvidence(
        DeploymentReadinessCheck check,
        String detail) {
    public DeploymentReadinessEvidence {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > 2_048) {
            throw new IllegalArgumentException("deployment readiness evidence detail is invalid");
        }
    }
}
