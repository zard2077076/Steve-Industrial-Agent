package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Offline power evidence never claims live RPM, stress capacity/load or network state. */
public record PowerFinding(
        SurveyLocation location,
        ResourceId resourceId,
        SurveyFindingCategory category,
        SurveyConfidence confidence,
        boolean liveRpmKnown,
        boolean liveStressKnown,
        List<SurveyEvidence> evidence) implements InfrastructureFinding {
    public PowerFinding {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(category, "category");
        if (category != SurveyFindingCategory.POWER_SOURCE
                && category != SurveyFindingCategory.TRANSMISSION) {
            throw new IllegalArgumentException("invalid power category");
        }
        Objects.requireNonNull(confidence, "confidence");
        if (liveRpmKnown || liveStressKnown) {
            throw new IllegalArgumentException("offline survey cannot claim live kinetic state");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64) throw new IllegalArgumentException("evidence is invalid");
    }
}
