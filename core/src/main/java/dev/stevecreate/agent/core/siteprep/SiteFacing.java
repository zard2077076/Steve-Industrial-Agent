package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

public enum SiteFacing {
    NORTH,
    EAST,
    SOUTH,
    WEST;

    public SiteFacing rotateClockwise() {
        return values()[(ordinal() + 1) % values().length];
    }

    public BlockPos3i rotate(BlockPos3i relative) {
        Objects.requireNonNull(relative, "relative");
        return switch (this) {
            case NORTH -> relative;
            case EAST -> new BlockPos3i(-relative.z(), relative.y(), relative.x());
            case SOUTH -> new BlockPos3i(-relative.x(), relative.y(), -relative.z());
            case WEST -> new BlockPos3i(relative.z(), relative.y(), -relative.x());
        };
    }
}
