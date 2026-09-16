package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;

/** Direction, route and clearance facts that a later physical task must bind. */
public record DirectionalFlowRequirement(
        boolean required,
        RotationDirectionRequirement rotationDirection,
        boolean inputOutputOpposed,
        int minimumClearanceBlocks) {
    public DirectionalFlowRequirement {
        Objects.requireNonNull(rotationDirection, "rotationDirection");
        if (minimumClearanceBlocks < 0 || minimumClearanceBlocks > 64) {
            throw new IllegalArgumentException("minimumClearanceBlocks must be between 0 and 64");
        }
        if (!required && (inputOutputOpposed || minimumClearanceBlocks != 0)) {
            throw new IllegalArgumentException("A non-directional process cannot claim route clearance");
        }
    }

    public static DirectionalFlowRequirement none() {
        return new DirectionalFlowRequirement(
                false, RotationDirectionRequirement.ANY_NONZERO, false, 0);
    }
}
