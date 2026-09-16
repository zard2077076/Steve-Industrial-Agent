package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Exact two-corner selection; both corners are permanently bound to one world/dimension/player. */
public record RegionCornerSelection(
        String worldIdentity,
        ResourceId dimension,
        BlockPos3i pos1,
        BlockPos3i pos2,
        DeploymentBoundingBox bounds,
        String playerIdentity,
        Instant selectedAt,
        String selectionHash) {
    public RegionCornerSelection {
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos1, "pos1");
        Objects.requireNonNull(pos2, "pos2");
        Objects.requireNonNull(bounds, "bounds");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(selectedAt, "selectedAt");
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
        if (!bounds.contains(pos1) || !bounds.contains(pos2)) {
            throw new IllegalArgumentException("bounds do not contain both corners");
        }
    }
}
