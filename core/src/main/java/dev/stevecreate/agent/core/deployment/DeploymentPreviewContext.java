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

/** Bounded loader-neutral read-only observations used to enrich a dry-run preview. */
public record DeploymentPreviewContext(
        WorldEnvironmentDescriptor environment,
        DeploymentPolicy policy,
        String worldSnapshotFingerprint,
        Map<BlockPos3i, DeploymentBlockObservation> observations,
        Map<ResourceId, Long> routeAndPowerConstructionMaterials,
        RollbackClassification rollbackClassification,
        List<String> powerSourceAssumptions) {
    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator
            .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z);

    public DeploymentPreviewContext {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(policy, "policy");
        worldSnapshotFingerprint = WorldEnvironmentEvidence.text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        Objects.requireNonNull(observations, "observations");
        if (observations.size() > 262_144) throw new IllegalArgumentException("observation count exceeds bound");
        TreeMap<BlockPos3i, DeploymentBlockObservation> sorted = new TreeMap<>(POSITION_ORDER);
        observations.forEach((position, observation) -> {
            Objects.requireNonNull(position, "observation position");
            Objects.requireNonNull(observation, "observation");
            if (!position.equals(observation.position())) {
                throw new IllegalArgumentException("observation key and value positions differ");
            }
            sorted.put(position, observation);
        });
        observations = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        Objects.requireNonNull(routeAndPowerConstructionMaterials, "routeAndPowerConstructionMaterials");
        TreeMap<ResourceId, Long> materials = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        routeAndPowerConstructionMaterials.forEach((resource, quantity) -> {
            Objects.requireNonNull(resource, "construction material");
            if (quantity == null || quantity < 1 || quantity > 1_000_000_000L) {
                throw new IllegalArgumentException("construction material quantity is outside its bound");
            }
            materials.put(resource, quantity);
        });
        routeAndPowerConstructionMaterials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        Objects.requireNonNull(rollbackClassification, "rollbackClassification");
        List<String> assumptions = new ArrayList<>(Objects.requireNonNull(
                powerSourceAssumptions, "powerSourceAssumptions"));
        assumptions.forEach(value -> {
            if (value == null || value.isBlank() || value.length() > 1_024) {
                throw new IllegalArgumentException("power source assumption is blank or too long");
            }
        });
        assumptions.sort(String::compareTo);
        powerSourceAssumptions = List.copyOf(assumptions);
    }
}
