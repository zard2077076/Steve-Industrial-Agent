package dev.stevecreate.agent.core.siteprep;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DemolitionPreview(
        String worldIdentity,
        String planHash,
        String siteSnapshotHash,
        Map<ObstacleClassification, Integer> counts,
        List<ObstacleFinding> findings,
        int estimatedTicks,
        int mutationBudget,
        Instant expiresAt,
        String approvalHash) {
    public DemolitionPreview {
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        siteSnapshotHash = SitePreparationHashes.hash(siteSnapshotHash, "siteSnapshotHash");
        Objects.requireNonNull(counts, "counts");
        EnumMap<ObstacleClassification, Integer> copy = new EnumMap<>(ObstacleClassification.class);
        counts.forEach((key, value) -> {
            Objects.requireNonNull(key, "classification");
            if (value == null || value < 0) throw new IllegalArgumentException("count is invalid");
            copy.put(key, value);
        });
        counts = Map.copyOf(copy);
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (estimatedTicks < 0 || mutationBudget < 0 || mutationBudget > 4_096) {
            throw new IllegalArgumentException("preview budget is invalid");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        approvalHash = SitePreparationHashes.hash(approvalHash, "approvalHash");
    }

    public static DemolitionPreview create(
            SiteSurveySnapshot survey,
            int estimatedTicks,
            int mutationBudget,
            Instant expiresAt) {
        EnumMap<ObstacleClassification, Integer> counts =
                new EnumMap<>(ObstacleClassification.class);
        for (ObstacleFinding finding : survey.findings()) {
            counts.merge(finding.classification(), 1, Integer::sum);
        }
        String hash = SitePreparationHashes.sha256(survey.worldIdentity() + "\n"
                + survey.planHash() + "\n" + survey.siteSnapshotHash() + "\n" + counts + "\n"
                + mutationBudget + "\n" + expiresAt);
        return new DemolitionPreview(survey.worldIdentity(), survey.planHash(),
                survey.siteSnapshotHash(), counts, survey.findings(), estimatedTicks,
                mutationBudget, expiresAt, hash);
    }
}
