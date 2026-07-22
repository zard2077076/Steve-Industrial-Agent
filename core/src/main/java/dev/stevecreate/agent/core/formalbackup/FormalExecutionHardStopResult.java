package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

public record FormalExecutionHardStopResult(
        FormalBackupFailure failure,
        boolean executionAllowed,
        boolean formalWorldWriteAllowed) {
    public FormalExecutionHardStopResult {
        Objects.requireNonNull(failure, "failure");
        if (failure.code() != FormalBackupFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN
                || executionAllowed || formalWorldWriteAllowed) {
            throw new IllegalArgumentException("formal execution hard stop is invalid");
        }
    }
}
