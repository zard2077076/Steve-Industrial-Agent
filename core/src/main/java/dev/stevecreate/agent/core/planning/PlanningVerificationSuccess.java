package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** The only public result branch that exposes a verified logical plan. */
public record PlanningVerificationSuccess(VerifiedLogicalPlan plan)
        implements PlanningVerificationResult {
    public PlanningVerificationSuccess {
        Objects.requireNonNull(plan, "plan");
    }
}
