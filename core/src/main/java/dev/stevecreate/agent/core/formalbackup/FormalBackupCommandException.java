package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

public final class FormalBackupCommandException extends Exception {
    private final FormalBackupFailure failure;

    public FormalBackupCommandException(FormalBackupFailure failure) {
        super(Objects.requireNonNull(failure, "failure").reason());
        this.failure = failure;
    }

    public FormalBackupFailure failure() {
        return failure;
    }
}
