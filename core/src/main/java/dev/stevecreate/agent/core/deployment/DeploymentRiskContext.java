package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable read-only facts consumed by deterministic PW-05 rules. */
public record DeploymentRiskContext(
        ResourceId dimensionId,
        boolean dimensionAllowed,
        boolean unknownBlockReplacement,
        boolean playerBuildingAtRisk,
        List<BlockPos3i> fluidPositions,
        List<BlockPos3i> fireOrLavaPositions,
        boolean explosionCapable,
        int expectedDroppedEntities,
        long availableStressCapacity,
        boolean logisticsCongestion,
        Map<ResourceId, Long> availableResources,
        boolean reloadRecoverySafe,
        boolean multiNodeResourceHistory,
        DeploymentPermissionRisk permissionRisk,
        boolean backupAvailable,
        boolean snapshotFresh,
        String currentRuntimeFingerprint) {
    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator
            .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z);
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public DeploymentRiskContext {
        Objects.requireNonNull(dimensionId, "dimensionId");
        fluidPositions = positions(fluidPositions, "fluidPositions");
        fireOrLavaPositions = positions(fireOrLavaPositions, "fireOrLavaPositions");
        if (expectedDroppedEntities < 0 || expectedDroppedEntities > 1_000_000) {
            throw new IllegalArgumentException("expectedDroppedEntities is outside its bound");
        }
        if (availableStressCapacity < 0) {
            throw new IllegalArgumentException("availableStressCapacity cannot be negative");
        }
        Objects.requireNonNull(availableResources, "availableResources");
        if (availableResources.size() > 4_096) {
            throw new IllegalArgumentException("availableResources exceeds its bound");
        }
        TreeMap<ResourceId, Long> resources = new TreeMap<>(ID_ORDER);
        availableResources.forEach((resource, quantity) -> {
            Objects.requireNonNull(resource, "available resource");
            if (quantity == null || quantity < 0 || quantity > 1_000_000_000_000L) {
                throw new IllegalArgumentException("available resource quantity is outside its bound");
            }
            resources.put(resource, quantity);
        });
        availableResources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        Objects.requireNonNull(permissionRisk, "permissionRisk");
        currentRuntimeFingerprint = WorldEnvironmentEvidence.text(
                currentRuntimeFingerprint, "currentRuntimeFingerprint");
    }

    private static List<BlockPos3i> positions(List<BlockPos3i> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 262_144) throw new IllegalArgumentException(name + " exceeds its bound");
        List<BlockPos3i> copy = new ArrayList<>(values);
        copy.forEach(value -> Objects.requireNonNull(value, name + " element"));
        copy.sort(POSITION_ORDER);
        return List.copyOf(copy);
    }
}
