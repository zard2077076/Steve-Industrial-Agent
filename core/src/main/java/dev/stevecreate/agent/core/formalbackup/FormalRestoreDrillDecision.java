package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;
import java.util.Optional;

public record FormalRestoreDrillDecision(
        Optional<RestoreDrillResult> result,
        Optional<FormalBackupFailure> failure) {
    public FormalRestoreDrillDecision {
        result = Objects.requireNonNull(result, "result");
        failure = Objects.requireNonNull(failure, "failure");
        if (result.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one restore drill branch is required");
        }
    }

    static FormalRestoreDrillDecision success(RestoreDrillResult result) {
        return new FormalRestoreDrillDecision(Optional.of(result), Optional.empty());
    }

    static FormalRestoreDrillDecision refuse(FormalBackupFailure failure) {
        return new FormalRestoreDrillDecision(Optional.empty(), Optional.of(failure));
    }
}
