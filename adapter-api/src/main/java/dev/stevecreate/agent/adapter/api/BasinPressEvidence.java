package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinPressPlacement;
import dev.stevecreate.agent.core.plan.BasinPressRole;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable evidence from a real C-09 Basin + Mechanical Press cycle. */
public record BasinPressEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<BasinPressPlacement> verifiedPlacements,
        Map<BasinPressRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        List<ProcessResource> consumedInputs,
        ResourceId outputItem,
        int observedOutputCount,
        BasinHeatMode heatMode,
        boolean basinContentsObserved,
        boolean pressCycleObserved,
        boolean outputObservedInChest,
        boolean fluidInventoryEmpty) {
    public BasinPressEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid C-09 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(
                Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        EnumSet<BasinPressRole> roles = EnumSet.noneOf(BasinPressRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (BasinPressPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role()) || !positions.add(placement.position())) {
                throw new IllegalArgumentException("C-09 placement evidence is not unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(BasinPressRole.class))) {
            throw new IllegalArgumentException("C-09 evidence must include every role");
        }
        EnumMap<BasinPressRole, Double> speeds =
                new EnumMap<>(BasinPressRole.class);
        speeds.putAll(Objects.requireNonNull(observedSpeedRpm, "observedSpeedRpm"));
        EnumSet<BasinPressRole> kineticRoles = EnumSet.allOf(BasinPressRole.class);
        kineticRoles.removeIf(role -> !role.isKinetic());
        if (!speeds.keySet().equals(kineticRoles)
                || speeds.values().stream().anyMatch(value ->
                        value == null || !Double.isFinite(value) || value == 0.0D)) {
            throw new IllegalArgumentException("C-09 requires exact non-zero live speeds");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        consumedInputs = List.copyOf(
                Objects.requireNonNull(consumedInputs, "consumedInputs"));
        Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(heatMode, "heatMode");
        if (consumedInputs.isEmpty()
                || observedOutputCount < 1
                || heatMode != BasinHeatMode.NONE
                || !recipeType.equals(ResourceId.parse("create:compacting"))
                || !basinContentsObserved
                || !pressCycleObserved
                || !outputObservedInChest
                || !fluidInventoryEmpty) {
            throw new IllegalArgumentException(
                    "C-09 completion lacks basin, press, fluid-safety or output evidence");
        }
    }
}
