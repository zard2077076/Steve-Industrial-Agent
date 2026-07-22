package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ApprovalUseRecord(
        String requestIdentity,
        FormalApprovalEnvironment environment,
        boolean used,
        Optional<Instant> usedAt,
        boolean formalWorldMutation) {
    public ApprovalUseRecord {
        Objects.requireNonNull(requestIdentity, "requestIdentity");
        Objects.requireNonNull(environment, "environment");
        usedAt = Objects.requireNonNull(usedAt, "usedAt");
        if (!requestIdentity.matches("formal-approval-request:[0-9a-f]{64}")
                || used != usedAt.isPresent() || formalWorldMutation
                || (environment == FormalApprovalEnvironment.FORMAL_SOURCE && used)) {
            throw new IllegalArgumentException("approval use record violates formal one-time scope");
        }
    }
}
