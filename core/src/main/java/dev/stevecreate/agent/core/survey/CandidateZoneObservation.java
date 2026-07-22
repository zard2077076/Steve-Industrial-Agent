package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Bounded offline facts used for deterministic candidate scoring. */
public record CandidateZoneObservation(
        String worldIdentity,
        ResourceId dimension,
        DeploymentBoundingBox boundingBox,
        List<BlockPos3i> anchorCandidates,
        List<InfrastructureFinding> nearbyInfrastructure,
        List<SurveyLimitation> protectedAndUnknownFindings,
        long clearVolume,
        int exploredChunks,
        int totalChunks,
        int blockEntityCount,
        int playerBuiltRiskCount,
        int chunkBoundaryCrossings,
        CandidatePermissionStatus permissionStatus,
        boolean dryRunFeasible,
        boolean coverageComplete,
        SurveyCoverage coverage) {
    public CandidateZoneObservation {
        worldIdentity = SurveyModelValues.text(worldIdentity, "worldIdentity", 4_096);
        if (!worldIdentity.matches("world:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("worldIdentity is invalid");
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(boundingBox, "boundingBox");
        anchorCandidates = List.copyOf(Objects.requireNonNull(anchorCandidates, "anchorCandidates"));
        nearbyInfrastructure = List.copyOf(
                Objects.requireNonNull(nearbyInfrastructure, "nearbyInfrastructure"));
        protectedAndUnknownFindings = List.copyOf(
                Objects.requireNonNull(protectedAndUnknownFindings, "protectedAndUnknownFindings"));
        Objects.requireNonNull(permissionStatus, "permissionStatus");
        Objects.requireNonNull(coverage, "coverage");
        String expectedWorldIdentity = worldIdentity;
        if (anchorCandidates.isEmpty() || anchorCandidates.size() > 64
                || nearbyInfrastructure.size() > 65_536 || protectedAndUnknownFindings.size() > 4_096
                || anchorCandidates.stream().anyMatch(anchor -> !boundingBox.contains(anchor))
                || nearbyInfrastructure.stream().anyMatch(finding ->
                        !finding.location().worldIdentity().equals(expectedWorldIdentity)
                                || !finding.location().dimension().equals(dimension))
                || clearVolume < 0 || clearVolume > boundingBox.volume()
                || exploredChunks < 0 || totalChunks < 1 || totalChunks > 4_096
                || exploredChunks > totalChunks
                || exploredChunks > coverage.chunksParsed()
                || blockEntityCount < 0 || blockEntityCount > 65_536
                || playerBuiltRiskCount < 0 || playerBuiltRiskCount > 65_536
                || chunkBoundaryCrossings < 0 || chunkBoundaryCrossings > 4_096
                || coverageComplete != (exploredChunks == totalChunks && !coverage.budgetExhausted())) {
            throw new IllegalArgumentException("candidate observation is incomplete, inconsistent or unbounded");
        }
    }
}
