package dev.stevecreate.agent.core.placement;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One typed conflict at one target position, retaining every affected plan role. */
public record PlacementConflict(
        BlockPos3i position,
        PlacementConflictKind kind,
        List<ResourceId> roleIds) {

    public PlacementConflict {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        roleIds = List.copyOf(Objects.requireNonNull(roleIds, "roleIds"));
        if (roleIds.isEmpty() || roleIds.size() > BasicPlacementFeasibility.MAX_TARGETS) {
            throw new IllegalArgumentException("A placement conflict requires bounded affected roles");
        }
        if (roleIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(roleIds).size() != roleIds.size()) {
            throw new IllegalArgumentException("Placement conflict roles must be non-null and unique");
        }
    }
}
