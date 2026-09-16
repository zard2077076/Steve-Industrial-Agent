package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import java.time.Instant;
import java.util.Objects;

/** Confirmed anchor, facing and authorized region evidence. It is not a write permit. */
public record ConfirmedSiteSelection(
        PlacementAnchor anchor,
        SiteFacing facing,
        DeploymentBoundingBox authorizedBounds,
        String sessionIdentity,
        Instant confirmedAt,
        Instant expiresAt,
        String selectionHash) {
    public ConfirmedSiteSelection {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(authorizedBounds, "authorizedBounds");
        sessionIdentity = SitePreparationHashes.text(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!confirmedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("selection expiry must be later than confirmation");
        }
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
        if (!authorizedBounds.contains(anchor.position())) {
            throw new IllegalArgumentException("authorized bounds do not contain anchor");
        }
    }
}
