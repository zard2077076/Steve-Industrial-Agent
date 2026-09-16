package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.DeployerPlacement;
import dev.stevecreate.agent.core.plan.DeployerRole;
import dev.stevecreate.agent.core.plan.HeldItemDisposition;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable evidence from one real C-10 owned-Depot Deployer cycle. */
public record DeployerEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<DeployerPlacement> verifiedPlacements,
        Map<DeployerRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        ResourceId processedInput,
        int consumedInputCount,
        ResourceId heldItem,
        int heldItemBeforeCount,
        int heldItemAfterCount,
        HeldItemDisposition heldItemDisposition,
        ResourceId outputItem,
        int observedOutputCount,
        boolean depotItemObserved,
        boolean deployerCycleObserved,
        boolean outputObservedInChest,
        boolean correctInteractionFaceObserved,
        boolean ownedDepotTargetObserved,
        boolean noEntityInteraction,
        boolean noContainerOrPlayerInventoryAccess,
        boolean noUnknownNbtMutation,
        boolean noUnknownWorldSideEffects) {
    public DeployerEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException(
                    "Invalid C-10 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(
                Objects.requireNonNull(
                        verifiedPlacements, "verifiedPlacements"));
        EnumSet<DeployerRole> roles =
                EnumSet.noneOf(DeployerRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (DeployerPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalArgumentException(
                        "C-10 placement evidence is not unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(DeployerRole.class))) {
            throw new IllegalArgumentException(
                    "C-10 evidence must include every role");
        }
        EnumMap<DeployerRole, Double> speeds =
                new EnumMap<>(DeployerRole.class);
        speeds.putAll(Objects.requireNonNull(
                observedSpeedRpm, "observedSpeedRpm"));
        EnumSet<DeployerRole> kineticRoles = EnumSet.allOf(DeployerRole.class);
        kineticRoles.removeIf(role -> !role.isKinetic());
        if (!speeds.keySet().equals(kineticRoles)
                || speeds.values().stream().anyMatch(value ->
                        value == null
                                || !Double.isFinite(value)
                                || value == 0.0D)) {
            throw new IllegalArgumentException(
                    "C-10 requires exact non-zero live speeds");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(processedInput, "processedInput");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(
                heldItemDisposition, "heldItemDisposition");
        Objects.requireNonNull(outputItem, "outputItem");
        boolean heldStateMatches =
                heldItemDisposition == HeldItemDisposition.CONSUMED
                        ? heldItemBeforeCount > 0
                                && heldItemAfterCount
                                        == heldItemBeforeCount - 1
                        : heldItemBeforeCount > 0
                                && heldItemAfterCount
                                        == heldItemBeforeCount;
        if (!recipeType.equals(ResourceId.parse("create:deploying"))
                || consumedInputCount < 1
                || observedOutputCount < 1
                || !heldStateMatches
                || !depotItemObserved
                || !deployerCycleObserved
                || !outputObservedInChest
                || !correctInteractionFaceObserved
                || !ownedDepotTargetObserved
                || !noEntityInteraction
                || !noContainerOrPlayerInventoryAccess
                || !noUnknownNbtMutation
                || !noUnknownWorldSideEffects) {
            throw new IllegalArgumentException(
                    "C-10 completion lacks exact held-item, cycle, "
                            + "output or immediate safety evidence");
        }
    }
}
