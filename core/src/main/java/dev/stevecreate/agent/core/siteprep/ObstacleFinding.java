package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public record ObstacleFinding(
        String obstacleId,
        BlockPos3i position,
        ResourceId blockId,
        String blockStateFingerprint,
        boolean blockEntity,
        boolean container,
        boolean hasInventory,
        boolean machine,
        boolean naturalCandidate,
        double hardness,
        String toolRequirement,
        String dropsExpectation,
        boolean fluidRisk,
        ObstacleClassification classification,
        int confidence,
        String evidenceSource,
        String reason) {
    public ObstacleFinding {
        obstacleId = SitePreparationHashes.text(obstacleId, "obstacleId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        blockStateFingerprint = SitePreparationHashes.hash(
                blockStateFingerprint, "blockStateFingerprint");
        toolRequirement = SitePreparationHashes.text(toolRequirement, "toolRequirement");
        dropsExpectation = SitePreparationHashes.text(dropsExpectation, "dropsExpectation");
        Objects.requireNonNull(classification, "classification");
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence must be between 0 and 100");
        }
        evidenceSource = SitePreparationHashes.text(evidenceSource, "evidenceSource");
        reason = SitePreparationHashes.text(reason, "reason");
    }
}
