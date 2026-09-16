package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Pure assembly boundary for bounded server-authoritative observations. */
public final class SiteSurvey {
    private final ObstacleClassifier classifier;

    public SiteSurvey(ObstacleClassifier classifier) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    public SiteSurveySnapshot assemble(
            String worldIdentity,
            ResourceId dimension,
            DeploymentBoundingBox bounds,
            String selectionHash,
            String planHash,
            Instant scannedAt,
            List<ObstacleObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.size() > SiteSurveySnapshot.MAX_FINDINGS) {
            throw new IllegalArgumentException("survey observations are unbounded");
        }
        if (observations.stream().anyMatch(value -> !bounds.contains(value.position()))) {
            throw new IllegalArgumentException("SITE_OUTSIDE_AUTHORIZED_REGION");
        }
        return SiteSurveySnapshot.create(worldIdentity, dimension, bounds, selectionHash, planHash,
                scannedAt, observations.stream().map(classifier::classify).toList());
    }

    public SiteSurveySnapshot assembleExplicitGrading(
            String worldIdentity,
            ResourceId dimension,
            DeploymentBoundingBox bounds,
            String selectionHash,
            String planHash,
            Instant scannedAt,
            List<ObstacleObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.size() > SiteSurveySnapshot.MAX_FINDINGS) {
            throw new IllegalArgumentException("survey observations are unbounded");
        }
        if (observations.stream().anyMatch(value -> !bounds.contains(value.position()))) {
            throw new IllegalArgumentException("SITE_OUTSIDE_AUTHORIZED_REGION");
        }
        return SiteSurveySnapshot.create(worldIdentity, dimension, bounds, selectionHash,
                planHash, scannedAt,
                observations.stream().map(classifier::classifyForExplicitGrading).toList());
    }
}
