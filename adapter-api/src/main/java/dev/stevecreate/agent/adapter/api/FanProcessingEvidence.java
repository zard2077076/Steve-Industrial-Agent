package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import dev.stevecreate.agent.core.plan.FanProcessingPlacement;
import dev.stevecreate.agent.core.plan.FanProcessingRole;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable C-06 evidence from a live directional fan, exact medium and real output. */
public record FanProcessingEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        FanProcessingMode mode,
        List<FanProcessingPlacement> verifiedPlacements,
        Map<FanProcessingRole, Double> observedSpeedRpm,
        String observedAirflowDirection,
        float observedAirflowReach,
        ResourceId observedMediumBlock,
        ResourceId recipeId,
        ResourceId recipeType,
        ResourceId inputItem,
        int consumedInputCount,
        ResourceId outputItem,
        int observedOutputCount,
        int observedDwellTicks,
        boolean obstructionFree,
        boolean outputObservedInChest,
        boolean botsExcludedFromDangerousMedium) {

    public FanProcessingEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid C-06 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        Objects.requireNonNull(mode, "mode");
        verifiedPlacements = List.copyOf(
                Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        EnumSet<FanProcessingRole> roles = EnumSet.noneOf(FanProcessingRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (FanProcessingPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role()) || !positions.add(placement.position())) {
                throw new IllegalArgumentException("C-06 placement evidence is not unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(FanProcessingRole.class))) {
            throw new IllegalArgumentException("C-06 evidence must include every role");
        }
        EnumMap<FanProcessingRole, Double> speeds =
                new EnumMap<>(FanProcessingRole.class);
        speeds.putAll(Objects.requireNonNull(observedSpeedRpm, "observedSpeedRpm"));
        if (!speeds.keySet().equals(EnumSet.of(
                FanProcessingRole.WATER_WHEEL,
                FanProcessingRole.BOTTOM_GEARBOX,
                FanProcessingRole.VERTICAL_SHAFT,
                FanProcessingRole.TOP_GEARBOX,
                FanProcessingRole.FAN_DRIVE_SHAFT,
                FanProcessingRole.ENCASED_FAN))
                || speeds.values().stream().anyMatch(value ->
                        value == null || !Double.isFinite(value) || value == 0.0D)) {
            throw new IllegalArgumentException(
                    "C-06 requires exact non-zero live survival-power speeds");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);
        Objects.requireNonNull(observedAirflowDirection, "observedAirflowDirection");
        Objects.requireNonNull(observedMediumBlock, "observedMediumBlock");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (!recipeType.equals(mode.recipeType())
                || !observedMediumBlock.equals(mode.mediumBlock())
                || observedAirflowDirection.isBlank()
                || !Float.isFinite(observedAirflowReach)
                || observedAirflowReach < 1.0F
                || consumedInputCount < 1
                || observedOutputCount < 1
                || observedDwellTicks < 1
                || !obstructionFree
                || !outputObservedInChest
                || (mode.dangerousToBots() && !botsExcludedFromDangerousMedium)) {
            throw new IllegalArgumentException(
                    "C-06 completion lacks live airflow, medium, dwell, output or Bot safety evidence");
        }
    }
}
