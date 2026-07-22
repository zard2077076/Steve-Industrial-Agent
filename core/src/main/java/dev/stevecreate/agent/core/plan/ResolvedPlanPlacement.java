package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** An immutable absolute placement produced from a validated plan and typed origin. */
public record ResolvedPlanPlacement(
        int order,
        WaterWheelMillstoneRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis axis) {

    public ResolvedPlanPlacement {
        if (order < 1) {
            throw new IllegalArgumentException("Placement order must be positive");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(axis, "axis");
    }
}
