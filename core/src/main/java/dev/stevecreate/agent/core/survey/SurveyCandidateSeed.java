package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;

import java.util.Objects;

public record SurveyCandidateSeed(
        String candidateId,
        ResourceId dimension,
        int regionX,
        int regionZ,
        int requestedDeepScanRadius,
        int evidenceScore) {
    public SurveyCandidateSeed {
        candidateId = SurveyModelValues.text(candidateId, "candidateId", 256);
        Objects.requireNonNull(dimension, "dimension");
        if (requestedDeepScanRadius < 0 || requestedDeepScanRadius > 128
                || evidenceScore < 0 || evidenceScore > 1_000_000) {
            throw new IllegalArgumentException("candidate seed is invalid");
        }
    }
}
