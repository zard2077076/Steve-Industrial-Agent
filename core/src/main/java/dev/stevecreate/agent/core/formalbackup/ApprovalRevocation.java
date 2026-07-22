package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.Objects;

public record ApprovalRevocation(
        String requestIdentity,
        Instant revokedAt,
        String reason,
        boolean formalExecutionAllowed) {
    public ApprovalRevocation {
        Objects.requireNonNull(requestIdentity, "requestIdentity");
        Objects.requireNonNull(revokedAt, "revokedAt");
        Objects.requireNonNull(reason, "reason");
        if (!requestIdentity.matches("formal-approval-request:[0-9a-f]{64}")
                || reason.isBlank() || formalExecutionAllowed) {
            throw new IllegalArgumentException("approval revocation is invalid");
        }
    }
}
