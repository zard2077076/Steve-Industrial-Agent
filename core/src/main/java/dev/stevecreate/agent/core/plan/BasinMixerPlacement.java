package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One exact owned C-08 placement. */
public record BasinMixerPlacement(
        int order,
        BasinMixerRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing) {
    public BasinMixerPlacement {
        if (order < 1) throw new IllegalArgumentException("order must be positive");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
    }
}
