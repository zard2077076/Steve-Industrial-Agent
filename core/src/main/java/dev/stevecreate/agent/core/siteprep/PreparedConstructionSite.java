package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PreparedConstructionSite(
        String preparedSiteIdentity,
        String worldIdentity,
        ResourceId dimension,
        PlacementAnchor anchor,
        SiteFacing facing,
        String planHash,
        String cleanSiteSnapshotHash,
        String terrainPreparationGraphIdentity,
        SalvageLedger salvageLedger,
        List<ObstacleFinding> remainingProtectedFindings,
        List<TerrainMutationEvidence> mutationEvidence,
        Instant preparedAt,
        Instant expiresAt) {
    public PreparedConstructionSite {
        preparedSiteIdentity = SitePreparationHashes.text(
                preparedSiteIdentity, "preparedSiteIdentity");
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facing, "facing");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        cleanSiteSnapshotHash = SitePreparationHashes.hash(
                cleanSiteSnapshotHash, "cleanSiteSnapshotHash");
        terrainPreparationGraphIdentity = SitePreparationHashes.text(
                terrainPreparationGraphIdentity, "terrainPreparationGraphIdentity");
        Objects.requireNonNull(salvageLedger, "salvageLedger");
        remainingProtectedFindings = List.copyOf(remainingProtectedFindings);
        mutationEvidence = List.copyOf(mutationEvidence);
        if (remainingProtectedFindings.size() > 4_096 || mutationEvidence.size() > 4_096) {
            throw new IllegalArgumentException("prepared-site evidence is unbounded");
        }
        if (remainingProtectedFindings.stream().anyMatch(
                value -> value.classification().approvable())) {
            throw new IllegalArgumentException("remaining findings must be protected/hazard/unknown");
        }
        Objects.requireNonNull(preparedAt, "preparedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!preparedAt.isBefore(expiresAt)) throw new IllegalArgumentException("expiry is invalid");
        if (!anchor.worldIdentity().equals(worldIdentity) || !anchor.dimension().equals(dimension)) {
            throw new IllegalArgumentException("prepared site and anchor identity mismatch");
        }
    }
}
