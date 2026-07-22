package dev.stevecreate.agent.core.formalbackup;

import java.time.Duration;
import java.util.Objects;

/** Data-only retention policy; deletion is not implemented by FB-03. */
public record BackupRetentionPolicy(
        int maximumCompletedBackups,
        Duration retainFor,
        boolean deleteOnlyAfterFreshVerification,
        boolean formalSourceDeletionAllowed,
        boolean cloudUploadAllowed) {
    public BackupRetentionPolicy {
        if (maximumCompletedBackups < 1 || maximumCompletedBackups > 1_000) {
            throw new IllegalArgumentException("maximumCompletedBackups is invalid");
        }
        Objects.requireNonNull(retainFor, "retainFor");
        if (retainFor.isZero() || retainFor.isNegative()
                || retainFor.compareTo(Duration.ofDays(3_650)) > 0
                || !deleteOnlyAfterFreshVerification || formalSourceDeletionAllowed || cloudUploadAllowed) {
            throw new IllegalArgumentException("formal backup retention policy is unsafe");
        }
    }
}
