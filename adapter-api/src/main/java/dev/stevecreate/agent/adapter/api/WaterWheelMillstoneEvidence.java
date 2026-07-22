package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.ResolvedPlanPlacement;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneRole;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable final evidence captured from verified blocks, kinetics, recipe and inventories. */
public record WaterWheelMillstoneEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<ResolvedPlanPlacement> verifiedPlacements,
        Map<WaterWheelMillstoneRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        int recipeProcessingDuration,
        ResourceId inputItem,
        int consumedInputCount,
        ResourceId outputItem,
        int observedOutputCount) {

    private static final Set<WaterWheelMillstoneRole> REQUIRED_KINETIC_ROLES = Set.of(
            WaterWheelMillstoneRole.WATER_WHEEL,
            WaterWheelMillstoneRole.GEARBOX,
            WaterWheelMillstoneRole.VERTICAL_SHAFT,
            WaterWheelMillstoneRole.MILLSTONE);

    public WaterWheelMillstoneEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        if (verifiedPlacements.isEmpty() || verifiedPlacements.size() > WaterWheelMillstonePlan.MAX_PLACEMENTS) {
            throw new IllegalArgumentException("Verified placements are outside the plan bound");
        }

        EnumMap<WaterWheelMillstoneRole, Double> speeds = new EnumMap<>(WaterWheelMillstoneRole.class);
        speeds.putAll(Objects.requireNonNull(observedSpeedRpm, "observedSpeedRpm"));
        for (WaterWheelMillstoneRole role : REQUIRED_KINETIC_ROLES) {
            Double speed = speeds.get(role);
            if (speed == null || !Double.isFinite(speed) || speed <= 0) {
                throw new IllegalArgumentException("Missing positive live speed for " + role);
            }
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);

        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (recipeProcessingDuration < 1 || consumedInputCount < 1 || observedOutputCount < 1) {
            throw new IllegalArgumentException("Recipe and item evidence must be positive");
        }
    }
}
