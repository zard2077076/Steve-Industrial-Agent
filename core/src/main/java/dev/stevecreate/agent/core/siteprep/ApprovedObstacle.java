package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

public record ApprovedObstacle(
        String obstacleId,
        BlockPos3i position,
        String blockStateFingerprint,
        ObstacleClassification classification) {
    public ApprovedObstacle {
        obstacleId = SitePreparationHashes.text(obstacleId, "obstacleId");
        Objects.requireNonNull(position, "position");
        blockStateFingerprint = SitePreparationHashes.hash(
                blockStateFingerprint, "blockStateFingerprint");
        Objects.requireNonNull(classification, "classification");
        if (!classification.approvable()) {
            throw new IllegalArgumentException("classification is not approvable");
        }
    }
}
