package dev.stevecreate.agent.core.placement;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One typed role that intends to own one final world position. */
public record PlacementTarget(ResourceId roleId, BlockPos3i position) {
    public PlacementTarget {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(position, "position");
    }
}
