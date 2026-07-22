package dev.stevecreate.agent.core.placement;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

/** Loader-neutral read-only state for one proposed final placement position. */
public record PlacementSiteObservation(
        BlockPos3i position,
        boolean loaded,
        boolean replaceable,
        boolean protectedPosition) {

    public PlacementSiteObservation {
        Objects.requireNonNull(position, "position");
        if (!loaded && replaceable) {
            throw new IllegalArgumentException("An unloaded position cannot be known replaceable");
        }
    }
}
