package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

public record RegionSelection(
        String worldIdentity,
        ResourceId dimension,
        DeploymentBoundingBox bounds,
        String playerIdentity,
        Instant selectedAt,
        String selectionHash) {
    public RegionSelection {
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(bounds, "bounds");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(selectedAt, "selectedAt");
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
    }
}
