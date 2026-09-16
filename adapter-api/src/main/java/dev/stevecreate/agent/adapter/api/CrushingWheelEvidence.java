package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.CrushingWheelPlacement;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelRole;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable C-05 evidence from real wheels, controller, input entity, rolls and chest output. */
public record CrushingWheelEvidence(
        long feedTick,
        long completionTick,
        RuntimeFingerprint runtime,
        ResourceId dimension,
        BlockPos3i planOrigin,
        List<CrushingWheelPlacement> verifiedPlacements,
        Map<CrushingWheelRole, Double> observedSignedSpeedRpm,
        Map<CrushingWheelRole, Double> observedStressCapacity,
        Map<CrushingWheelRole, Double> observedStressLoad,
        ResourceId recipeId,
        ResourceId recipeType,
        int recipeProcessingDuration,
        ResourceId inputItem,
        int consumedInputCount,
        ResourceId outputItem,
        int observedOutputCount,
        Map<ResourceId, Integer> observedByproductCounts,
        Map<ResourceId, Integer> probabilityPerMillion,
        boolean controllerValid,
        boolean inputEntityObserved,
        boolean outputObservedInChest) {

    public CrushingWheelEvidence {
        if (feedTick < 0 || completionTick < feedTick) {
            throw new IllegalArgumentException("Invalid C-05 execution tick range");
        }
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(planOrigin, "planOrigin");
        verifiedPlacements =
                List.copyOf(Objects.requireNonNull(verifiedPlacements, "verifiedPlacements"));
        if (verifiedPlacements.isEmpty()
                || verifiedPlacements.size() > CrushingWheelPlan.MAX_PLACEMENTS) {
            throw new IllegalArgumentException("Verified placements are outside the C-05 bound");
        }
        EnumSet<CrushingWheelRole> roles = EnumSet.noneOf(CrushingWheelRole.class);
        HashSet<BlockPos3i> positions = new HashSet<>();
        for (CrushingWheelPlacement placement : verifiedPlacements) {
            if (!roles.add(placement.role()) || !positions.add(placement.position())) {
                throw new IllegalArgumentException(
                        "Verified C-05 placement roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(CrushingWheelRole.class))) {
            throw new IllegalArgumentException(
                    "Verified C-05 placements must contain every owned role");
        }

        EnumMap<CrushingWheelRole, Double> speeds =
                new EnumMap<>(CrushingWheelRole.class);
        speeds.putAll(Objects.requireNonNull(
                observedSignedSpeedRpm, "observedSignedSpeedRpm"));
        EnumSet<CrushingWheelRole> expected = EnumSet.noneOf(CrushingWheelRole.class);
        for (CrushingWheelRole role : CrushingWheelRole.values()) {
            if (!role.isKinetic()) continue;
            expected.add(role);
            Double value = speeds.get(role);
            if (value == null || !Double.isFinite(value) || value == 0.0D) {
                throw new IllegalArgumentException(
                        "Missing non-zero live signed speed for " + role);
            }
        }
        if (!speeds.keySet().equals(expected)
                || Math.signum(speeds.get(CrushingWheelRole.LEFT_WHEEL))
                        == Math.signum(speeds.get(CrushingWheelRole.RIGHT_WHEEL))
                || Double.compare(
                        Math.abs(speeds.get(CrushingWheelRole.LEFT_WHEEL)),
                        Math.abs(speeds.get(CrushingWheelRole.RIGHT_WHEEL))) != 0) {
            throw new IllegalArgumentException(
                    "C-05 wheel evidence requires equal-magnitude opposed live rotation");
        }
        observedSignedSpeedRpm = Collections.unmodifiableMap(speeds);
        observedStressCapacity = kineticTotals(
                observedStressCapacity, expected, "observedStressCapacity");
        observedStressLoad = kineticTotals(
                observedStressLoad, expected, "observedStressLoad");
        for (CrushingWheelRole role : expected) {
            if (observedStressLoad.get(role) > observedStressCapacity.get(role)) {
                throw new IllegalArgumentException(
                        "C-05 completion cannot claim an overstressed kinetic role: " + role);
            }
        }

        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (recipeProcessingDuration < 1
                || consumedInputCount < 1
                || observedOutputCount < 1) {
            throw new IllegalArgumentException(
                    "C-05 recipe and item evidence must be positive");
        }
        observedByproductCounts = boundedCounts(
                observedByproductCounts, "observedByproductCounts", true);
        probabilityPerMillion = boundedCounts(
                probabilityPerMillion, "probabilityPerMillion", false);
        if (!observedByproductCounts.keySet().equals(probabilityPerMillion.keySet())) {
            throw new IllegalArgumentException(
                    "C-05 byproduct observations and probability declarations differ");
        }
        for (Integer probability : probabilityPerMillion.values()) {
            if (probability < 1 || probability > 1_000_000) {
                throw new IllegalArgumentException(
                        "C-05 output probability is outside (0, 1_000_000]");
            }
        }
        if (!controllerValid || !inputEntityObserved || !outputObservedInChest) {
            throw new IllegalArgumentException(
                    "C-05 completion requires controller, input-entity and chest evidence");
        }
    }

    private static Map<ResourceId, Integer> boundedCounts(
            Map<ResourceId, Integer> values,
            String name,
            boolean allowZero) {
        Objects.requireNonNull(values, name);
        if (values.size() > 16) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        LinkedHashMap<ResourceId, Integer> copy = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> {
            Objects.requireNonNull(entry.getKey(), name + " key");
            Integer value = Objects.requireNonNull(entry.getValue(), name + " value");
            if (value < (allowZero ? 0 : 1) || value > 1_000_000) {
                throw new IllegalArgumentException(name + " contains an invalid value");
            }
            copy.put(entry.getKey(), value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<CrushingWheelRole, Double> kineticTotals(
            Map<CrushingWheelRole, Double> values,
            EnumSet<CrushingWheelRole> expected,
            String name) {
        Objects.requireNonNull(values, name);
        EnumMap<CrushingWheelRole, Double> copy =
                new EnumMap<>(CrushingWheelRole.class);
        copy.putAll(values);
        if (!copy.keySet().equals(expected)) {
            throw new IllegalArgumentException(name + " must contain every kinetic role");
        }
        for (Double value : copy.values()) {
            if (value == null || !Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException(name + " contains an invalid value");
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
