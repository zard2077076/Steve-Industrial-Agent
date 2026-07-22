package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

public record BackupVerification(
        List<BackupVerificationFailure> failures,
        boolean valid) {
    public BackupVerification {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        BackupVerificationFailure previous = null;
        for (BackupVerificationFailure failure : failures) {
            Objects.requireNonNull(failure, "failure");
            if (previous != null && previous.ordinal() >= failure.ordinal()) {
                throw new IllegalArgumentException("backup failures are duplicate or unordered");
            }
            previous = failure;
        }
        if (valid != failures.isEmpty()) {
            throw new IllegalArgumentException("valid disagrees with failures");
        }
    }
}
