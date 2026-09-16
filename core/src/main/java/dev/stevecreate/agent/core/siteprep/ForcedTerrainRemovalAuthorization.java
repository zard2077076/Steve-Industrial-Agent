package dev.stevecreate.agent.core.siteprep;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Snapshot-bound, expiring second confirmation for destructive terrain removal. */
public record ForcedTerrainRemovalAuthorization(
        String authorizationIdentity,
        String warningHash,
        String gradingSpecificationHash,
        String siteSnapshotHash,
        String playerIdentity,
        Instant confirmedAt,
        Instant expiresAt,
        List<ForcedTerrainRemoval> removals) {
    public ForcedTerrainRemovalAuthorization {
        authorizationIdentity = SitePreparationHashes.text(
                authorizationIdentity, "authorizationIdentity");
        warningHash = SitePreparationHashes.hash(warningHash, "warningHash");
        gradingSpecificationHash = SitePreparationHashes.hash(
                gradingSpecificationHash, "gradingSpecificationHash");
        siteSnapshotHash = SitePreparationHashes.hash(siteSnapshotHash, "siteSnapshotHash");
        playerIdentity = SitePreparationHashes.text(playerIdentity, "playerIdentity");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        removals = List.copyOf(Objects.requireNonNull(removals, "removals")).stream()
                .sorted(Comparator.comparing((ForcedTerrainRemoval value) -> value.position().x())
                        .thenComparing(value -> value.position().y())
                        .thenComparing(value -> value.position().z()))
                .toList();
        if (!confirmedAt.isBefore(expiresAt) || removals.isEmpty() || removals.size() > 4_096
                || removals.stream().map(ForcedTerrainRemoval::position).distinct().count()
                != removals.size()) {
            throw new IllegalArgumentException("forced terrain authorization is invalid");
        }
        String expected = SitePreparationHashes.sha256(gradingSpecificationHash + "\n"
                + siteSnapshotHash + "\n" + playerIdentity + "\n" + removals + "\n"
                + expiresAt);
        if (!warningHash.equals(expected)) {
            throw new IllegalArgumentException("forced terrain warning hash mismatch");
        }
    }

    public static ForcedTerrainRemovalAuthorization confirm(
            String gradingSpecificationHash,
            String siteSnapshotHash,
            String playerIdentity,
            Instant confirmedAt,
            Instant expiresAt,
            List<ForcedTerrainRemoval> removals) {
        List<ForcedTerrainRemoval> ordered = removals.stream()
                .sorted(Comparator.comparing((ForcedTerrainRemoval value) -> value.position().x())
                        .thenComparing(value -> value.position().y())
                        .thenComparing(value -> value.position().z())).toList();
        String warningHash = SitePreparationHashes.sha256(gradingSpecificationHash + "\n"
                + siteSnapshotHash + "\n" + playerIdentity + "\n" + ordered + "\n"
                + expiresAt);
        return new ForcedTerrainRemovalAuthorization(
                "forced-terrain:" + warningHash, warningHash, gradingSpecificationHash,
                siteSnapshotHash, playerIdentity, confirmedAt, expiresAt, ordered);
    }
}
