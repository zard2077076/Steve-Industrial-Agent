package dev.stevecreate.agent.core.deployment;

import java.time.Duration;
import java.util.Objects;

public record BackupRetentionPolicy(
        int maximumBackups,
        Duration retainFor,
        boolean deleteOnlyVerifiedBackups) {
    public BackupRetentionPolicy {
        if (maximumBackups < 1 || maximumBackups > 1_000) {
            throw new IllegalArgumentException("maximumBackups is outside its bound");
        }
        Objects.requireNonNull(retainFor, "retainFor");
        if (retainFor.isZero() || retainFor.isNegative()
                || retainFor.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("retainFor is outside its bound");
        }
    }
}
