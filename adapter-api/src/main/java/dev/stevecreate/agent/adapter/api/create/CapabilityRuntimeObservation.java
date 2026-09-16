package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Successful server-authoritative readback for one exact task assignment. */
public record CapabilityRuntimeObservation(
        ResourceId sessionId,
        ResourceId graphId,
        ResourceId taskId,
        ResourceId assignmentId,
        ResourceId verifiedPhysicalPlanId,
        CreateCapabilityId capability,
        ResourceId recipeId,
        String runtimeFingerprint,
        String worldSnapshotFingerprint,
        long requestedAtTick,
        int maximumObservationTicks,
        long observedServerTick,
        Map<ResourceId, ResourceId> observedBlockIds,
        Map<ResourceId, Double> kineticSpeeds,
        Set<ResourceId> overstressedRoles,
        Map<ResourceId, Long> observedItemCounts,
        boolean liveRecipeMatched,
        boolean inputConsumed,
        boolean outputObserved,
        boolean machineStateMatched,
        boolean environmentMatched,
        boolean worldMutation,
        List<String> trace) {
    public CapabilityRuntimeObservation {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(recipeId, "recipeId");
        runtimeFingerprint = fingerprint(runtimeFingerprint, "runtimeFingerprint");
        worldSnapshotFingerprint = fingerprint(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        if (requestedAtTick < 0 || maximumObservationTicks < 1
                || maximumObservationTicks > 72_000
                || observedServerTick < requestedAtTick
                || observedServerTick - requestedAtTick > maximumObservationTicks) {
            throw new IllegalArgumentException("Observation tick window is invalid or stale");
        }
        observedBlockIds = sorted(observedBlockIds, "observedBlockIds");
        kineticSpeeds = sorted(kineticSpeeds, "kineticSpeeds");
        if (observedBlockIds.size() > CapabilityObservationRequest.MAX_COMPONENTS
                || kineticSpeeds.size() > CapabilityObservationRequest.MAX_COMPONENTS
                || kineticSpeeds.values().stream().anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("Observed component evidence is invalid or unbounded");
        }
        TreeSet<ResourceId> stress = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        stress.addAll(Objects.requireNonNull(overstressedRoles, "overstressedRoles"));
        overstressedRoles = Collections.unmodifiableSet(stress);
        if (overstressedRoles.size() > CapabilityObservationRequest.MAX_COMPONENTS) {
            throw new IllegalArgumentException("overstressedRoles exceeds the component bound");
        }
        observedItemCounts = sorted(observedItemCounts, "observedItemCounts");
        if (observedItemCounts.size() > CapabilityObservationRequest.MAX_ITEM_IDENTITIES
                || observedItemCounts.values().stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("Observed item evidence is invalid or unbounded");
        }
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (trace.isEmpty() || trace.size() > 32 || worldMutation
                || trace.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 256)) {
            throw new IllegalArgumentException("Observation trace is invalid or mutation was reported");
        }
        if (!liveRecipeMatched || !inputConsumed || !outputObserved
                || !machineStateMatched || !environmentMatched) {
            throw new IllegalArgumentException("Successful runtime observation must pass every readback gate");
        }
    }

    private static <T> Map<ResourceId, T> sorted(Map<ResourceId, T> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<ResourceId, T> result = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((key, value) -> result.put(
                Objects.requireNonNull(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static String fingerprint(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 2_048) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
