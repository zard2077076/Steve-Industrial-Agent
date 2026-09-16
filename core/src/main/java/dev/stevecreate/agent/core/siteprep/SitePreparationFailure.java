package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

public record SitePreparationFailure(
        SitePreparationFailureCode code,
        String stage,
        String worldIdentity,
        ResourceId dimension,
        BlockPos3i anchor,
        SiteFacing facing,
        String planHash,
        String siteHash,
        String obstacleIdentity,
        String approvalState,
        List<BlockPos3i> affectedPositions,
        List<String> evidence,
        String reason,
        String safeNextStep) {
    public SitePreparationFailure {
        Objects.requireNonNull(code, "code");
        stage = SitePreparationHashes.text(stage, "stage");
        worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facing, "facing");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        siteHash = SitePreparationHashes.hash(siteHash, "siteHash");
        obstacleIdentity = SitePreparationHashes.text(obstacleIdentity, "obstacleIdentity");
        approvalState = SitePreparationHashes.text(approvalState, "approvalState");
        affectedPositions = List.copyOf(Objects.requireNonNull(
                affectedPositions, "affectedPositions"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (affectedPositions.size() > 4_096 || evidence.size() > 256) {
            throw new IllegalArgumentException("failure context is unbounded");
        }
        reason = SitePreparationHashes.text(reason, "reason");
        safeNextStep = SitePreparationHashes.text(safeNextStep, "safeNextStep");
    }
}
