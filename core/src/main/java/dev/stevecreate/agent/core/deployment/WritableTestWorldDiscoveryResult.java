package dev.stevecreate.agent.core.deployment;

import java.util.Objects;
import java.util.Optional;

public record WritableTestWorldDiscoveryResult(
        Optional<WritableTestWorldIdentity> identity,
        Optional<WritableTestWorldFailure> failure) {
    public WritableTestWorldDiscoveryResult {
        identity = Objects.requireNonNull(identity, "identity");
        failure = Objects.requireNonNull(failure, "failure");
        if (identity.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("exactly one discovery outcome is required");
        }
    }

    public static WritableTestWorldDiscoveryResult success(WritableTestWorldIdentity identity) {
        return new WritableTestWorldDiscoveryResult(Optional.of(identity), Optional.empty());
    }

    public static WritableTestWorldDiscoveryResult refused(WritableTestWorldFailure failure) {
        return new WritableTestWorldDiscoveryResult(Optional.empty(), Optional.of(failure));
    }
}
