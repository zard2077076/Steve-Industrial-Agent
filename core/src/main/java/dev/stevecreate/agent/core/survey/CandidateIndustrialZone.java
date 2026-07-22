package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CandidateIndustrialZone(
        String candidateId,
        ResourceId dimension,
        DeploymentBoundingBox boundingBox,
        List<BlockPos3i> anchorCandidates,
        List<InfrastructureFinding> nearbyInfrastructure,
        List<SurveyLimitation> protectedAndUnknownFindings,
        Map<String, Integer> scoreBreakdown,
        int totalScore,
        SurveyConfidence confidence,
        List<String> requiredFutureAuthorization,
        SurveyCoverage coverage,
        CandidateZoneStatus status) {
    public CandidateIndustrialZone {
        candidateId = SurveyModelValues.text(candidateId, "candidateId", 512);
        if (!candidateId.matches("zone:[0-9a-f]{64}")) throw new IllegalArgumentException("candidateId is invalid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(boundingBox, "boundingBox");
        anchorCandidates = List.copyOf(Objects.requireNonNull(anchorCandidates, "anchorCandidates"));
        nearbyInfrastructure = List.copyOf(Objects.requireNonNull(nearbyInfrastructure, "nearbyInfrastructure"));
        protectedAndUnknownFindings = List.copyOf(
                Objects.requireNonNull(protectedAndUnknownFindings, "protectedAndUnknownFindings"));
        scoreBreakdown = Map.copyOf(Objects.requireNonNull(scoreBreakdown, "scoreBreakdown"));
        Objects.requireNonNull(confidence, "confidence");
        requiredFutureAuthorization = SurveyModelValues.texts(
                requiredFutureAuthorization, "requiredFutureAuthorization", 64);
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(status, "status");
        if (anchorCandidates.isEmpty() || anchorCandidates.size() > 64
                || nearbyInfrastructure.size() > 65_536 || protectedAndUnknownFindings.size() > 4_096
                || scoreBreakdown.isEmpty() || scoreBreakdown.size() > 64
                || requiredFutureAuthorization.isEmpty()
                || anchorCandidates.stream().anyMatch(anchor -> !boundingBox.contains(anchor))) {
            throw new IllegalArgumentException("candidate zone is incomplete or unbounded");
        }
        int calculated = scoreBreakdown.values().stream().mapToInt(Integer::intValue).sum();
        if (calculated != totalScore) throw new IllegalArgumentException("totalScore does not match scoreBreakdown");
    }
}
