package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;
import java.util.Optional;

public record FormalBackupVerificationDecision(
        Optional<BackupVerificationResult> result,
        Optional<FormalBackupFailure> failure) {
    public FormalBackupVerificationDecision {
        result = Objects.requireNonNull(result, "result");
        failure = Objects.requireNonNull(failure, "failure");
        if (result.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one verification branch is required");
        }
    }

    static FormalBackupVerificationDecision success(BackupVerificationResult result) {
        return new FormalBackupVerificationDecision(Optional.of(result), Optional.empty());
    }

    static FormalBackupVerificationDecision refuse(FormalBackupFailure failure) {
        return new FormalBackupVerificationDecision(Optional.empty(), Optional.of(failure));
    }
}
