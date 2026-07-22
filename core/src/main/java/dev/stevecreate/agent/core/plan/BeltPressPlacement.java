package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One immutable final placement expected after the typed C-04 construction sequence. */
public record BeltPressPlacement(
        int order,
        BeltPressRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing) {

    public BeltPressPlacement {
        if (order < 1) {
            throw new IllegalArgumentException("Final-placement order must be positive");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
        if (role.isKinetic() && rotationAxis == PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("Kinetic final placements require a typed rotation axis");
        }
        if (!role.isKinetic() && rotationAxis != PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("Non-kinetic final placements cannot claim a rotation axis");
        }
    }
}
