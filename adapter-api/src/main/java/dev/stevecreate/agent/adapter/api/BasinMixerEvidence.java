package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinMixerPlacement;
import dev.stevecreate.agent.core.plan.BasinMixerRole;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable evidence from a real C-08 Basin + Mechanical Mixer cycle. */
public record BasinMixerEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<BasinMixerPlacement> verifiedPlacements,
        Map<BasinMixerRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        List<ProcessResource> consumedInputs,
        ResourceId outputItem,
        int observedOutputCount,
        BasinHeatMode heatMode,
        String observedHeatLevel,
        boolean liveIngredientsMatched,
        boolean basinContentsObserved,
        boolean mixerCycleObserved,
        boolean outputObservedInChest,
        boolean fluidInventoryEmpty) {
    public BasinMixerEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException(
                    "Invalid C-08 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(Objects.requireNonNull(
                verifiedPlacements, "verifiedPlacements"));
        EnumSet<BasinMixerRole> roles =
                EnumSet.noneOf(BasinMixerRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (BasinMixerPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalArgumentException(
                        "C-08 placement evidence is not unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(BasinMixerRole.class))) {
            throw new IllegalArgumentException(
                    "C-08 evidence must include every role");
        }
        EnumMap<BasinMixerRole, Double> speeds =
                new EnumMap<>(BasinMixerRole.class);
        speeds.putAll(Objects.requireNonNull(
        observedSpeedRpm, "observedSpeedRpm"));
        EnumSet<BasinMixerRole> kineticRoles = EnumSet.noneOf(BasinMixerRole.class);
        for (BasinMixerRole role : BasinMixerRole.values()) {
            if (role.isKinetic()) kineticRoles.add(role);
        }
        if (!speeds.keySet().equals(kineticRoles)
                || speeds.values().stream().anyMatch(value ->
                        value == null
                                || !Double.isFinite(value)
                                || value == 0.0D)) {
            throw new IllegalArgumentException(
                    "C-08 requires exact non-zero live speeds");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        consumedInputs = List.copyOf(Objects.requireNonNull(
                consumedInputs, "consumedInputs"));
        Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(heatMode, "heatMode");
        if (observedHeatLevel == null || observedHeatLevel.isBlank()
                || observedHeatLevel.length() > 32
                || consumedInputs.isEmpty()
                || observedOutputCount < 1
                || !recipeType.equals(ResourceId.parse("create:mixing"))
                || !liveIngredientsMatched
                || !basinContentsObserved
                || !mixerCycleObserved
                || !outputObservedInChest
                || !fluidInventoryEmpty) {
            throw new IllegalArgumentException(
                    "C-08 completion lacks ingredient, basin, mixer, heat, final fluid-consumption or output evidence");
        }
        if ((heatMode == BasinHeatMode.NONE
                && !"none".equals(observedHeatLevel))
                || (heatMode == BasinHeatMode.HEATED
                && !"kindled".equals(observedHeatLevel))) {
            throw new IllegalArgumentException(
                    "C-08 observed heat differs from the typed recipe");
        }
    }
}
