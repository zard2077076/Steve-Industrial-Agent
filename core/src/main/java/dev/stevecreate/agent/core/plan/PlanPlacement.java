package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** A single ordered, loader-neutral placement relative to a validated plan origin. */
public record PlanPlacement(
        int order,
        WaterWheelMillstoneRole role,
        ResourceId blockId,
        BlockPos3i relativePosition,
        PlanBlockAxis axis) {

    public PlanPlacement {
        if (order < 1) {
            throw new IllegalArgumentException("Placement order must be positive");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(relativePosition, "relativePosition");
        Objects.requireNonNull(axis, "axis");
    }

    public ResolvedPlanPlacement resolve(BlockPos3i origin) {
        return resolve(new PlanTransform(PlanAnchor.at(origin)));
    }

    public ResolvedPlanPlacement resolve(PlanTransform transform) {
        Objects.requireNonNull(transform, "transform");
        return new ResolvedPlanPlacement(
                order,
                role,
                blockId,
                transform.resolve(relativePosition),
                transform.rotate(axis));
    }
}
