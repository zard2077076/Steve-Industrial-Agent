package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Presence-only storage evidence. Concrete item contents are structurally forbidden. */
public record StorageFinding(
        SurveyLocation location,
        ResourceId resourceId,
        boolean blockEntityPresent,
        boolean withinCandidateZone,
        boolean contentsRead,
        boolean futureContentAuthorizationRequired,
        SurveyConfidence confidence,
        List<SurveyEvidence> evidence) implements InfrastructureFinding {
    public StorageFinding {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceId, "resourceId");
        if (contentsRead || !futureContentAuthorizationRequired) {
            throw new IllegalArgumentException("storage findings must be presence-only and authorization-gated");
        }
        Objects.requireNonNull(confidence, "confidence");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64) throw new IllegalArgumentException("evidence is invalid");
    }

    @Override
    public SurveyFindingCategory category() {
        return SurveyFindingCategory.STORAGE;
    }
}
