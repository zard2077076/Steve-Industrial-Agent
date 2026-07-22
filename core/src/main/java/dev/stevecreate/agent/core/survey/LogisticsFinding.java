package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Objects;

public record LogisticsFinding(
        SurveyLocation location,
        ResourceId resourceId,
        GenericResourceType transportType,
        SurveyConfidence confidence,
        boolean runtimeConnectivityKnown,
        List<SurveyEvidence> evidence) implements InfrastructureFinding {
    public LogisticsFinding {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(transportType, "transportType");
        if (transportType != GenericResourceType.ITEM && transportType != GenericResourceType.FLUID) {
            throw new IllegalArgumentException("offline logistics supports only ITEM or FLUID approximation");
        }
        Objects.requireNonNull(confidence, "confidence");
        if (runtimeConnectivityKnown) throw new IllegalArgumentException("offline connectivity cannot be live-known");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64) throw new IllegalArgumentException("evidence is invalid");
    }

    @Override
    public SurveyFindingCategory category() {
        return transportType == GenericResourceType.FLUID
                ? SurveyFindingCategory.FLUID : SurveyFindingCategory.LOGISTICS;
    }
}
