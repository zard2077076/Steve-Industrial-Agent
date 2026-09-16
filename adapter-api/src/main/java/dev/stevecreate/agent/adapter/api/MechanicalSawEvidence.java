package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.MechanicalSawPlacement;
import dev.stevecreate.agent.core.plan.MechanicalSawRole;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable C-07 evidence from an upward item-processing saw and real output storage. */
public record MechanicalSawEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<MechanicalSawPlacement> verifiedPlacements,
        Map<MechanicalSawRole, Double> observedSpeedRpm,
        ResourceId recipeId,
        ResourceId recipeType,
        int recipeProcessingDuration,
        ResourceId inputItem,
        int consumedInputCount,
        ResourceId outputItem,
        int observedOutputCount,
        boolean depotInputObserved,
        boolean sawCycleObserved,
        boolean outputObservedInChest,
        boolean worldBlockCuttingDisabled) {

    public MechanicalSawEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid C-07 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements = List.copyOf(
                Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        EnumSet<MechanicalSawRole> roles = EnumSet.noneOf(MechanicalSawRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (MechanicalSawPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role()) || !positions.add(placement.position())) {
                throw new IllegalArgumentException("C-07 placement evidence is not unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(MechanicalSawRole.class))) {
            throw new IllegalArgumentException("C-07 evidence must include every role");
        }
        EnumMap<MechanicalSawRole, Double> speeds = new EnumMap<>(MechanicalSawRole.class);
        speeds.putAll(Objects.requireNonNull(observedSpeedRpm, "observedSpeedRpm"));
        EnumSet<MechanicalSawRole> kinetic = EnumSet.noneOf(MechanicalSawRole.class);
        for (MechanicalSawRole role : MechanicalSawRole.values()) {
            if (role.isKinetic()) kinetic.add(role);
        }
        if (!speeds.keySet().equals(kinetic)
                || speeds.values().stream().anyMatch(value ->
                        value == null || !Double.isFinite(value) || value == 0.0D)) {
            throw new IllegalArgumentException(
                    "C-07 requires exact non-zero live water-wheel transmission and saw speeds");
        }
        observedSpeedRpm = Collections.unmodifiableMap(speeds);
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (!recipeType.equals(ResourceId.parse("create:cutting"))
                || recipeProcessingDuration < 1
                || consumedInputCount < 1
                || observedOutputCount < 1
                || !depotInputObserved
                || !sawCycleObserved
                || !outputObservedInChest
                || !worldBlockCuttingDisabled) {
            throw new IllegalArgumentException(
                    "C-07 completion lacks real item-processing or safety evidence");
        }
    }
}
