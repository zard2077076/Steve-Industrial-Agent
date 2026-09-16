package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One immutable final placement expected from the typed C-05 construction sequence. */
public record CrushingWheelPlacement(
        int order,
        CrushingWheelRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing) {

    public CrushingWheelPlacement {
        if (order < 1) {
            throw new IllegalArgumentException("C-05 placement order must be positive");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
        if (role.isKinetic() && rotationAxis == PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("C-05 kinetic placements require a typed axis");
        }
        if (!role.isKinetic() && rotationAxis != PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("C-05 non-kinetic placements cannot claim an axis");
        }
    }
}
