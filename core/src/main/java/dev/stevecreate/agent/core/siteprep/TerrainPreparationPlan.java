package dev.stevecreate.agent.core.siteprep;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TerrainPreparationPlan(
        String planIdentity,
        DemolitionApprovalToken approvalToken,
        TerrainPreparationTaskGraph taskGraph,
        GroundLevelingPolicy levelingPolicy,
        String salvageDestinationIdentity,
        List<String> safetyConstraints,
        Optional<TerrainGradingSpecification> gradingSpecification,
        Optional<ForcedTerrainRemovalAuthorization> forcedRemovalAuthorization) {
    public TerrainPreparationPlan(
            String planIdentity,
            DemolitionApprovalToken approvalToken,
            TerrainPreparationTaskGraph taskGraph,
            GroundLevelingPolicy levelingPolicy,
            String salvageDestinationIdentity,
            List<String> safetyConstraints) {
        this(planIdentity, approvalToken, taskGraph, levelingPolicy,
                salvageDestinationIdentity, safetyConstraints, Optional.empty(), Optional.empty());
    }

    public TerrainPreparationPlan(
            String planIdentity,
            DemolitionApprovalToken approvalToken,
            TerrainPreparationTaskGraph taskGraph,
            GroundLevelingPolicy levelingPolicy,
            String salvageDestinationIdentity,
            List<String> safetyConstraints,
            Optional<TerrainGradingSpecification> gradingSpecification) {
        this(planIdentity, approvalToken, taskGraph, levelingPolicy, salvageDestinationIdentity,
                safetyConstraints, gradingSpecification, Optional.empty());
    }

    public TerrainPreparationPlan {
        planIdentity = SitePreparationHashes.text(planIdentity, "planIdentity");
        Objects.requireNonNull(approvalToken, "approvalToken");
        Objects.requireNonNull(taskGraph, "taskGraph");
        Objects.requireNonNull(levelingPolicy, "levelingPolicy");
        salvageDestinationIdentity = SitePreparationHashes.text(
                salvageDestinationIdentity, "salvageDestinationIdentity");
        safetyConstraints = List.copyOf(safetyConstraints);
        gradingSpecification = Objects.requireNonNull(
                gradingSpecification, "gradingSpecification");
        forcedRemovalAuthorization = Objects.requireNonNull(
                forcedRemovalAuthorization, "forcedRemovalAuthorization");
        if (safetyConstraints.isEmpty() || safetyConstraints.size() > 64) {
            throw new IllegalArgumentException("safetyConstraints must contain 1..64 values");
        }
        if (!taskGraph.approvalTokenIdentity().equals(approvalToken.tokenIdentity())) {
            throw new IllegalArgumentException("terrain graph and approval token mismatch");
        }
        gradingSpecification.ifPresent(grading -> {
            if (grading.mutationCount() > levelingPolicy.maximumTotalMutations()
                    || grading.placementPositions().size()
                    > levelingPolicy.maximumFillBlocks()) {
                throw new IllegalArgumentException("grading exceeds the leveling policy");
            }
        });
        if (forcedRemovalAuthorization.isPresent()) {
            if (gradingSpecification.isEmpty()) {
                throw new IllegalArgumentException("forced removal requires grading authority");
            }
            ForcedTerrainRemovalAuthorization forced = forcedRemovalAuthorization.get();
            TerrainGradingSpecification grading = gradingSpecification.get();
            if (!forced.gradingSpecificationHash().equals(grading.specificationHash())
                    || !forced.siteSnapshotHash().equals(taskGraph.siteSnapshotHash())
                    || forced.removals().stream().anyMatch(value ->
                    !grading.removalPositions().contains(value.position()))) {
                throw new IllegalArgumentException("forced removal does not match grading plan");
            }
        }
    }
}
