package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable C-04 evidence from final blocks, live kinetics, belt input, press cycle and chest output. */
public record BeltPressEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<BeltPressPlacement> verifiedPlacements,
        Map<BeltPressRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        int recipeProcessingDuration,
        int pressCycleTicks,
        ResourceId inputItem,
        int consumedInputCount,
        ResourceId outputItem,
        int observedOutputCount,
        boolean inputObservedOnBelt,
        boolean pressCycleObserved,
        boolean outputObservedInChest) {

    public BeltPressEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid C-04 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        if (verifiedPlacements.isEmpty() || verifiedPlacements.size() > BeltPressPlan.MAX_FINAL_PLACEMENTS) {
            throw new IllegalArgumentException("Verified placements are outside the C-04 plan bound");
        }
        EnumSet<BeltPressRole> placementRoles = EnumSet.noneOf(BeltPressRole.class);
        HashSet<BlockPos3i> placementPositions = new HashSet<>();
        for (BeltPressPlacement placement : verifiedPlacements) {
            if (!placementRoles.add(placement.role()) || !placementPositions.add(placement.position())) {
                throw new IllegalArgumentException("Verified C-04 placement roles and positions must be unique");
            }
        }
        if (!placementRoles.equals(EnumSet.allOf(BeltPressRole.class))) {
            throw new IllegalArgumentException("Verified C-04 placements must contain every final role");
        }

        EnumMap<BeltPressRole, Double> speeds = new EnumMap<>(BeltPressRole.class);
        speeds.putAll(Objects.requireNonNull(observedSpeedRpm, "observedSpeedRpm"));
        EnumSet<BeltPressRole> expectedKineticRoles = EnumSet.noneOf(BeltPressRole.class);
        for (BeltPressRole role : BeltPressRole.values()) {
            if (!role.isKinetic()) {
                continue;
            }
            expectedKineticRoles.add(role);
            Double speed = speeds.get(role);
            if (speed == null || !Double.isFinite(speed) || speed <= 0) {
                throw new IllegalArgumentException("Missing positive live speed for " + role);
            }
        }
        if (!speeds.keySet().equals(expectedKineticRoles)) {
            throw new IllegalArgumentException("Live speeds must contain exactly the C-04 kinetic roles");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);

        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (recipeProcessingDuration < 0
                || pressCycleTicks < 1
                || consumedInputCount < 1
                || observedOutputCount < 1) {
            throw new IllegalArgumentException("Recipe, press cycle and item evidence are outside valid bounds");
        }
        if (!inputObservedOnBelt || !pressCycleObserved || !outputObservedInChest) {
            throw new IllegalArgumentException("C-04 completion requires belt input, press cycle and chest output");
        }
    }
}
