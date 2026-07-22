package dev.stevecreate.agent.core.survey;

import java.util.Objects;
import java.util.Optional;

public record SurveyRegionWork(
        SurveyRegionMetadata metadata,
        SurveyRegionScanPhase phase,
        Optional<String> candidateId,
        int distanceFromAnchor) {
    public SurveyRegionWork {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(phase, "phase");
        candidateId = Objects.requireNonNull(candidateId, "candidateId");
        if (distanceFromAnchor < 0 || candidateId.map(String::isBlank).orElse(false)
                || (phase == SurveyRegionScanPhase.CANDIDATE_DEEP_SCAN) != candidateId.isPresent()
                || (phase == SurveyRegionScanPhase.HOT_SAMPLE && distanceFromAnchor != 0)) {
            throw new IllegalArgumentException("region work is invalid");
        }
    }
}
