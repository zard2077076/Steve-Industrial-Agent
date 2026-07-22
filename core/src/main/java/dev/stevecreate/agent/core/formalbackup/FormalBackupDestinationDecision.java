package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;
import java.util.Optional;

public record FormalBackupDestinationDecision(
        Optional<FormalBackupDestinationPlan> plan,
        Optional<FormalBackupFailure> failure) {
    public FormalBackupDestinationDecision {
        plan = Objects.requireNonNull(plan, "plan");
        failure = Objects.requireNonNull(failure, "failure");
        if (plan.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("decision must contain exactly one outcome");
        }
    }

    public static FormalBackupDestinationDecision allow(FormalBackupDestinationPlan plan) {
        return new FormalBackupDestinationDecision(Optional.of(plan), Optional.empty());
    }

    public static FormalBackupDestinationDecision refuse(FormalBackupFailure failure) {
        return new FormalBackupDestinationDecision(Optional.empty(), Optional.of(failure));
    }
}
