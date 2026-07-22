package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;
import java.util.Optional;

public record FormalBackupCopyDecision(
        Optional<FormalBackupCopyResult> result,
        Optional<FormalBackupFailure> failure) {
    public FormalBackupCopyDecision {
        result = Objects.requireNonNull(result, "result");
        failure = Objects.requireNonNull(failure, "failure");
        if (result.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one copy decision branch is required");
        }
    }

    public static FormalBackupCopyDecision success(FormalBackupCopyResult result) {
        return new FormalBackupCopyDecision(Optional.of(result), Optional.empty());
    }

    public static FormalBackupCopyDecision refuse(FormalBackupFailure failure) {
        return new FormalBackupCopyDecision(Optional.empty(), Optional.of(failure));
    }
}
