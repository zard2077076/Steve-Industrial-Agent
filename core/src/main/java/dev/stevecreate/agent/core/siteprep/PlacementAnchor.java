package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Immutable exact player-selected anchor. It grants no mutation authority. */
public record PlacementAnchor(
        String worldIdentity,
        ResourceId dimension,
        BlockPos3i position,
        String playerIdentity,
        Instant selectedAt,
        AnchorSource source,
        String selectionHash) {
    public PlacementAnchor {
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(selectedAt, "selectedAt");
        Objects.requireNonNull(source, "source");
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
    }
}
