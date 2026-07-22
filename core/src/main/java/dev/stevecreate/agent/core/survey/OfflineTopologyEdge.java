package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Objects;

public record OfflineTopologyEdge(
        SurveyLocation first,
        SurveyLocation second,
        GenericResourceType resourceType,
        SurveyConfidence confidence,
        boolean runtimeConnectivityKnown,
        List<SurveyEvidence> evidence) {
    public OfflineTopologyEdge {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(confidence, "confidence");
        evidence = List.copyOf(evidence);
        if (first.equals(second) || runtimeConnectivityKnown
                || resourceType != GenericResourceType.ROTATIONAL_POWER
                    && resourceType != GenericResourceType.ITEM
                    && resourceType != GenericResourceType.FLUID
                || confidence != SurveyConfidence.DERIVED_HIGH_CONFIDENCE
                    && confidence != SurveyConfidence.DERIVED_LOW_CONFIDENCE
                || evidence.isEmpty() || evidence.size() > 8) {
            throw new IllegalArgumentException("offline topology edge is invalid");
        }
    }
}
