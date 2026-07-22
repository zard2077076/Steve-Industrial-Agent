package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Deterministic, serializable and non-executable deployment dry-run artifact. */
public record DeploymentPreview(
        ResourceId target,
        long quantity,
        List<ResourceId> recipeIds,
        List<ResourceId> implementationIds,
        BlockPos3i anchor,
        List<QuarterTurn> orientations,
        DeploymentBoundingBox affectedBounds,
        List<DeploymentBlockPlacement> plannedPlacements,
        List<DeploymentBlockObservation> plannedRemovals,
        List<DeploymentBlockReplacement> plannedReplacements,
        List<DeploymentBlockObservation> protectedBlocksEncountered,
        List<DeploymentBlockObservation> blockEntitiesEncountered,
        List<DeploymentRoutePreview> itemRoutes,
        List<DeploymentRoutePreview> rotationalPowerRoutes,
        Map<ResourceId, Long> materialBillOfMaterials,
        Map<ResourceId, Long> requiredInputResources,
        Map<ResourceId, Long> machineConstructionMaterials,
        Map<ResourceId, Long> expectedOutput,
        OptionalLong estimatedTicks,
        long stressDemand,
        List<String> powerSourceAssumptions,
        long journalEstimate,
        RollbackClassification rollbackClassification,
        WorldEnvironmentType environmentClassification,
        String runtimeFingerprint,
        String worldSnapshotFingerprint,
        List<String> riskFindings,
        List<String> requiredApprovals,
        List<String> policyViolations,
        String previewHash) {
    public DeploymentPreview {
        Objects.requireNonNull(target, "target");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        recipeIds = List.copyOf(Objects.requireNonNull(recipeIds, "recipeIds"));
        implementationIds = List.copyOf(Objects.requireNonNull(implementationIds, "implementationIds"));
        Objects.requireNonNull(anchor, "anchor");
        orientations = List.copyOf(Objects.requireNonNull(orientations, "orientations"));
        Objects.requireNonNull(affectedBounds, "affectedBounds");
        plannedPlacements = List.copyOf(Objects.requireNonNull(plannedPlacements, "plannedPlacements"));
        plannedRemovals = List.copyOf(Objects.requireNonNull(plannedRemovals, "plannedRemovals"));
        plannedReplacements = List.copyOf(Objects.requireNonNull(plannedReplacements, "plannedReplacements"));
        protectedBlocksEncountered = List.copyOf(Objects.requireNonNull(
                protectedBlocksEncountered, "protectedBlocksEncountered"));
        blockEntitiesEncountered = List.copyOf(Objects.requireNonNull(
                blockEntitiesEncountered, "blockEntitiesEncountered"));
        itemRoutes = List.copyOf(Objects.requireNonNull(itemRoutes, "itemRoutes"));
        rotationalPowerRoutes = List.copyOf(Objects.requireNonNull(
                rotationalPowerRoutes, "rotationalPowerRoutes"));
        materialBillOfMaterials = Map.copyOf(Objects.requireNonNull(
                materialBillOfMaterials, "materialBillOfMaterials"));
        requiredInputResources = Map.copyOf(Objects.requireNonNull(
                requiredInputResources, "requiredInputResources"));
        machineConstructionMaterials = Map.copyOf(Objects.requireNonNull(
                machineConstructionMaterials, "machineConstructionMaterials"));
        expectedOutput = Map.copyOf(Objects.requireNonNull(expectedOutput, "expectedOutput"));
        Objects.requireNonNull(estimatedTicks, "estimatedTicks");
        if (stressDemand < 0 || journalEstimate < 0) {
            throw new IllegalArgumentException("preview estimates cannot be negative");
        }
        powerSourceAssumptions = List.copyOf(Objects.requireNonNull(
                powerSourceAssumptions, "powerSourceAssumptions"));
        Objects.requireNonNull(rollbackClassification, "rollbackClassification");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        worldSnapshotFingerprint = text(worldSnapshotFingerprint, "worldSnapshotFingerprint");
        riskFindings = List.copyOf(Objects.requireNonNull(riskFindings, "riskFindings"));
        requiredApprovals = List.copyOf(Objects.requireNonNull(requiredApprovals, "requiredApprovals"));
        policyViolations = List.copyOf(Objects.requireNonNull(policyViolations, "policyViolations"));
        Objects.requireNonNull(previewHash, "previewHash");
        if (!previewHash.isEmpty() && !previewHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("previewHash must be lowercase SHA-256 hex");
        }
    }

    public String canonicalJson() {
        return DeploymentPreviewCodec.canonicalJson(this, true);
    }

    public boolean hasValidHash() {
        return previewHash.equals(DeploymentPreviewService.sha256(
                DeploymentPreviewCodec.canonicalJson(this, false)));
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
