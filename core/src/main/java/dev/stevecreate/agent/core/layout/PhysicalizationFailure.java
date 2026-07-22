package dev.stevecreate.agent.core.layout;

import java.util.Objects;

public record PhysicalizationFailure(LayoutFailure failure) implements PhysicalizationResult {
    public PhysicalizationFailure { Objects.requireNonNull(failure, "failure"); }
}
