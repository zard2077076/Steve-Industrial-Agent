package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Immutable, read-only and non-reserving deployment resource/power budget. */
public record DeploymentBudget(
        String previewHash,
        Map<ResourceId, Long> rawMaterialRequirements,
        Map<ResourceId, Long> intermediateProductRequirements,
        Map<ResourceId, Long> constructionBlockRequirements,
        Map<ResourceId, Long> powerComponentRequirements,
        Map<ResourceId, Long> logisticsComponentRequirements,
        Map<ResourceId, Long> inputInventoryRequirements,
        Map<ResourceId, Long> expectedOutput,
        long rotationalStressDemand,
        long powerCapacityMargin,
        OptionalLong estimatedRuntimeTicks,
        long maximumConstructionTicks,
        long maximumAffectedBlocks,
        long rollbackExtraSpaceBytes,
        long journalSizeEstimateBytes,
        ResourceSourcePolicy resourceSourcePolicy,
        List<DeploymentBudgetViolation> policyViolations,
        boolean withinPolicy) {
    public DeploymentBudget {
        Objects.requireNonNull(previewHash, "previewHash");
        if (!previewHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("previewHash must be lowercase SHA-256 hex");
        }
        rawMaterialRequirements = Map.copyOf(Objects.requireNonNull(
                rawMaterialRequirements, "rawMaterialRequirements"));
        intermediateProductRequirements = Map.copyOf(Objects.requireNonNull(
                intermediateProductRequirements, "intermediateProductRequirements"));
        constructionBlockRequirements = Map.copyOf(Objects.requireNonNull(
                constructionBlockRequirements, "constructionBlockRequirements"));
        powerComponentRequirements = Map.copyOf(Objects.requireNonNull(
                powerComponentRequirements, "powerComponentRequirements"));
        logisticsComponentRequirements = Map.copyOf(Objects.requireNonNull(
                logisticsComponentRequirements, "logisticsComponentRequirements"));
        inputInventoryRequirements = Map.copyOf(Objects.requireNonNull(
                inputInventoryRequirements, "inputInventoryRequirements"));
        expectedOutput = Map.copyOf(Objects.requireNonNull(expectedOutput, "expectedOutput"));
        Objects.requireNonNull(estimatedRuntimeTicks, "estimatedRuntimeTicks");
        if (rotationalStressDemand < 0 || maximumConstructionTicks < 1
                || maximumAffectedBlocks < 0 || rollbackExtraSpaceBytes < 0
                || journalSizeEstimateBytes < 0) {
            throw new IllegalArgumentException("deployment budget estimate cannot be negative");
        }
        Objects.requireNonNull(resourceSourcePolicy, "resourceSourcePolicy");
        policyViolations = List.copyOf(Objects.requireNonNull(policyViolations, "policyViolations"));
        DeploymentBudgetViolation previous = null;
        for (DeploymentBudgetViolation violation : policyViolations) {
            Objects.requireNonNull(violation, "violation");
            if (previous != null && previous.ordinal() >= violation.ordinal()) {
                throw new IllegalArgumentException("budget violations are duplicate or unordered");
            }
            previous = violation;
        }
        if (withinPolicy != policyViolations.isEmpty()) {
            throw new IllegalArgumentException("withinPolicy disagrees with violations");
        }
    }
}
