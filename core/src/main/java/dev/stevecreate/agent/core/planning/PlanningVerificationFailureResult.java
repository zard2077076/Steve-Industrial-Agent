package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Explicit failure branch; it cannot carry a verified plan. */
public record PlanningVerificationFailureResult(PlanningVerificationFailure failure)
        implements PlanningVerificationResult {
    public PlanningVerificationFailureResult {
        Objects.requireNonNull(failure, "failure");
    }
}
