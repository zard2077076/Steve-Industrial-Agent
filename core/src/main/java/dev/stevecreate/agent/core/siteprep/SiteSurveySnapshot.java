package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record SiteSurveySnapshot(
        String worldIdentity,
        ResourceId dimension,
        DeploymentBoundingBox scannedBounds,
        String selectionHash,
        String planHash,
        Instant scannedAt,
        List<ObstacleFinding> findings,
        String siteSnapshotHash) {
    public static final int MAX_FINDINGS = 4_096;

    public SiteSurveySnapshot {
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(scannedBounds, "scannedBounds");
        selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        Objects.requireNonNull(scannedAt, "scannedAt");
        Objects.requireNonNull(findings, "findings");
        if (findings.size() > MAX_FINDINGS) {
            throw new IllegalArgumentException("findings exceed " + MAX_FINDINGS);
        }
        findings = findings.stream()
                .map(value -> Objects.requireNonNull(value, "finding"))
                .sorted(Comparator.comparing((ObstacleFinding value) -> value.position().x())
                        .thenComparing(value -> value.position().y())
                        .thenComparing(value -> value.position().z())
                        .thenComparing(ObstacleFinding::obstacleId))
                .toList();
        if (findings.stream().anyMatch(value -> !scannedBounds.contains(value.position()))) {
            throw new IllegalArgumentException("finding is outside scanned bounds");
        }
        if (findings.stream().map(ObstacleFinding::obstacleId).distinct().count() != findings.size()) {
            throw new IllegalArgumentException("duplicate obstacle ID");
        }
        siteSnapshotHash = SitePreparationHashes.hash(siteSnapshotHash, "siteSnapshotHash");
    }

    public static SiteSurveySnapshot create(
            String worldIdentity,
            ResourceId dimension,
            DeploymentBoundingBox bounds,
            String selectionHash,
            String planHash,
            Instant scannedAt,
            List<ObstacleFinding> findings) {
        String canonical = worldIdentity + "\n" + dimension + "\n" + bounds + "\n"
                + selectionHash + "\n" + planHash + "\n" + findings.stream()
                .sorted(Comparator.comparing(ObstacleFinding::obstacleId))
                .map(value -> value.obstacleId() + "|" + value.blockStateFingerprint() + "|"
                        + value.classification())
                .reduce("", (left, right) -> left + "\n" + right);
        return new SiteSurveySnapshot(worldIdentity, dimension, bounds, selectionHash, planHash,
                scannedAt, findings, SitePreparationHashes.sha256(canonical));
    }
}
