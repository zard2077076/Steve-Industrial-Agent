package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Explicit failure branch; callers never infer failure from an empty graph list. */
public record PlanningFailureResult(PlanningFailure failure) implements PlanningResult {
    public PlanningFailureResult {
        Objects.requireNonNull(failure, "failure");
    }
}
