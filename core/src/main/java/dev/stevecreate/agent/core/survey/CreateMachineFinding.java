package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

public record CreateMachineFinding(
        SurveyLocation location,
        ResourceId resourceId,
        SurveyFindingCategory category,
        SurveyConfidence confidence,
        List<SurveyEvidence> evidence) implements InfrastructureFinding {
    private static final EnumSet<SurveyFindingCategory> ALLOWED = EnumSet.of(
            SurveyFindingCategory.POWER_SOURCE,
            SurveyFindingCategory.TRANSMISSION,
            SurveyFindingCategory.PROCESSING,
            SurveyFindingCategory.LOGISTICS,
            SurveyFindingCategory.FLUID,
            SurveyFindingCategory.MOVING_STRUCTURE,
            SurveyFindingCategory.OTHER_INFRASTRUCTURE);

    public CreateMachineFinding {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(category, "category");
        if (!ALLOWED.contains(category)) throw new IllegalArgumentException("invalid Create category");
        Objects.requireNonNull(confidence, "confidence");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64) throw new IllegalArgumentException("evidence is invalid");
    }
}
