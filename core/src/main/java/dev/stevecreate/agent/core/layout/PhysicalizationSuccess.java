package dev.stevecreate.agent.core.layout;

import java.util.Objects;

public record PhysicalizationSuccess(VerifiedPhysicalPlan plan) implements PhysicalizationResult {
    public PhysicalizationSuccess { Objects.requireNonNull(plan, "plan"); }
}
