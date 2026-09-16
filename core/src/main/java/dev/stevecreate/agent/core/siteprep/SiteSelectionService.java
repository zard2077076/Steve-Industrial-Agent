package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Pure deterministic SP-01 selection and footprint projection service. */
public final class SiteSelectionService {
    public static final long MAX_REGION_VOLUME = 131_072L;

    public PlacementAnchor anchor(
            String worldIdentity,
            ResourceId dimension,
            BlockPos3i position,
            String playerIdentity,
            Instant selectedAt,
            AnchorSource source) {
        String canonical = worldIdentity + "\n" + dimension + "\n" + position.x() + ","
                + position.y() + "," + position.z() + "\n" + playerIdentity + "\n"
                + selectedAt + "\n" + source;
        return new PlacementAnchor(worldIdentity, dimension, position, playerIdentity, selectedAt,
                source, SitePreparationHashes.sha256(canonical));
    }

    public RegionCornerSelection corners(
            String worldIdentity,
            ResourceId dimension,
            BlockPos3i pos1,
            String pos1WorldIdentity,
            ResourceId pos1Dimension,
            BlockPos3i pos2,
            String pos2WorldIdentity,
            ResourceId pos2Dimension,
            String playerIdentity,
            Instant selectedAt) {
        if (!worldIdentity.equals(pos1WorldIdentity) || !worldIdentity.equals(pos2WorldIdentity)) {
            throw new IllegalArgumentException("SITE_WORLD_MISMATCH");
        }
        if (!dimension.equals(pos1Dimension) || !dimension.equals(pos2Dimension)) {
            throw new IllegalArgumentException("SITE_DIMENSION_MISMATCH");
        }
        DeploymentBoundingBox bounds = DeploymentBoundingBox.enclosing(List.of(pos1, pos2));
        if (bounds.volume() > MAX_REGION_VOLUME) {
            throw new IllegalArgumentException("SITE_OUTSIDE_AUTHORIZED_REGION");
        }
        String canonical = worldIdentity + "\n" + dimension + "\n" + pos1 + "\n" + pos2 + "\n"
                + playerIdentity + "\n" + selectedAt;
        return new RegionCornerSelection(worldIdentity, dimension, pos1, pos2, bounds,
                playerIdentity, selectedAt, SitePreparationHashes.sha256(canonical));
    }

    public ConfirmedSiteSelection confirm(
            PlacementAnchor anchor,
            SiteFacing facing,
            RegionCornerSelection region,
            String sessionIdentity,
            Instant confirmedAt,
            Instant expiresAt) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(region, "region");
        if (!anchor.worldIdentity().equals(region.worldIdentity())) {
            throw new IllegalArgumentException("SITE_WORLD_MISMATCH");
        }
        if (!anchor.dimension().equals(region.dimension())) {
            throw new IllegalArgumentException("SITE_DIMENSION_MISMATCH");
        }
        if (!anchor.playerIdentity().equals(region.playerIdentity())
                || !region.bounds().contains(anchor.position())) {
            throw new IllegalArgumentException("SITE_OUTSIDE_AUTHORIZED_REGION");
        }
        String hash = SitePreparationHashes.sha256(anchor.selectionHash() + "\n"
                + region.selectionHash() + "\n" + facing + "\n" + sessionIdentity + "\n" + expiresAt);
        return new ConfirmedSiteSelection(anchor, facing, region.bounds(), sessionIdentity,
                confirmedAt, expiresAt, hash);
    }

    public List<BlockPos3i> projectFootprint(
            ConfirmedSiteSelection selection,
            List<BlockPos3i> relativePositions) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(relativePositions, "relativePositions");
        if (relativePositions.isEmpty() || relativePositions.size() > 4_096) {
            throw new IllegalArgumentException("footprint must contain 1..4096 positions");
        }
        return relativePositions.stream()
                .map(position -> selection.facing().rotate(Objects.requireNonNull(position, "position")))
                .map(position -> selection.anchor().position().translate(
                        position.x(), position.y(), position.z()))
                .peek(position -> {
                    if (!selection.authorizedBounds().contains(position)) {
                        throw new IllegalArgumentException("SITE_OUTSIDE_AUTHORIZED_REGION");
                    }
                })
                .distinct()
                .sorted(java.util.Comparator.comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z))
                .toList();
    }
}
