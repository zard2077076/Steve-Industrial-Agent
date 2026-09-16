package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Loader-neutral authoritative observation used by the pure classifier. */
public record ObstacleObservation(
        BlockPos3i position,
        ResourceId blockId,
        String blockStateFingerprint,
        boolean blockEntity,
        boolean container,
        boolean hasInventory,
        boolean machine,
        boolean runningMachine,
        boolean naturalCandidate,
        boolean playerPlacedUnknown,
        double hardness,
        String toolRequirement,
        String dropsExpectation,
        boolean fluidRisk,
        boolean environmentalRisk,
        boolean protectionKnown,
        boolean protectedByClaim,
        boolean insideAuthorizedRegion,
        String evidenceSource) {
    public ObstacleObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        blockStateFingerprint = SitePreparationHashes.hash(
                blockStateFingerprint, "blockStateFingerprint");
        if (!Double.isFinite(hardness) || hardness < -1.0 || hardness > 100_000.0) {
            throw new IllegalArgumentException("hardness is invalid");
        }
        toolRequirement = SitePreparationHashes.text(toolRequirement, "toolRequirement");
        dropsExpectation = SitePreparationHashes.text(dropsExpectation, "dropsExpectation");
        evidenceSource = SitePreparationHashes.text(evidenceSource, "evidenceSource");
    }
}
