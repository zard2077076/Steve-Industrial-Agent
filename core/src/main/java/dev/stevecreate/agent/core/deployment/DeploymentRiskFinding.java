package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record DeploymentRiskFinding(
        DeploymentRiskCategory category,
        RiskSeverity severity,
        String subject,
        String reason,
        String mitigation,
        boolean blocksApproval) {
    public DeploymentRiskFinding {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        subject = text(subject, "subject");
        reason = text(reason, "reason");
        mitigation = text(mitigation, "mitigation");
        if (blocksApproval != (severity == RiskSeverity.CRITICAL)) {
            throw new IllegalArgumentException("only and every CRITICAL finding blocks approval");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 4_096) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
