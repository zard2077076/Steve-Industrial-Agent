package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.Objects;

/** Typed world anchor plus one of the four supported whole-plan Y rotations. */
public record PlanAnchor(BlockPos3i position, QuarterTurn rotation) {
    public PlanAnchor {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
    }

    public static PlanAnchor at(BlockPos3i position) {
        return new PlanAnchor(position, QuarterTurn.ZERO);
    }
}
