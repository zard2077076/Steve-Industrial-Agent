package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One exact high-risk block authorized only by a separate final player confirmation. */
public record ForcedTerrainRemoval(
        BlockPos3i position,
        ResourceId blockId,
        String blockStateFingerprint,
        boolean container,
        boolean blockEntity,
        boolean dangerousMedium,
        boolean unbreakable,
        String warning) {
    public ForcedTerrainRemoval {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        blockStateFingerprint = SitePreparationHashes.hash(
                blockStateFingerprint, "blockStateFingerprint");
        warning = SitePreparationHashes.text(warning, "warning");
        if (!container && !blockEntity && !dangerousMedium && !unbreakable) {
            throw new IllegalArgumentException("forced removal has no high-risk reason");
        }
    }
}
