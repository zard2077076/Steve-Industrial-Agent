package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact-assignment observation input derived from one verified physical plan. */
public record CapabilityObservationRequest(
        ResourceId sessionId,
        ResourceId graphId,
        ResourceId taskId,
        ResourceId assignmentId,
        ResourceId verifiedPhysicalPlanId,
        TaskSourceKind sourceKind,
        ResourceId physicalElementId,
        CreateCapabilityId capability,
        ResourceId recipeId,
        String runtimeFingerprint,
        String worldSnapshotFingerprint,
        Map<ResourceId, BlockPos3i> componentPositions,
        Map<ResourceId, Long> baselineItemCounts,
        Map<ResourceId, Long> expectedConsumedItemCounts,
        Map<ResourceId, Long> expectedProducedItemCounts,
        long requestedAtTick,
        int maximumObservationTicks) {
    public static final int MAX_COMPONENTS = 32;
    public static final int MAX_ITEM_IDENTITIES = 64;

    public CapabilityObservationRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(physicalElementId, "physicalElementId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(recipeId, "recipeId");
        runtimeFingerprint = fingerprint(runtimeFingerprint, "runtimeFingerprint");
        worldSnapshotFingerprint = fingerprint(worldSnapshotFingerprint, "worldSnapshotFingerprint");
        componentPositions = sortedPositions(componentPositions);
        baselineItemCounts = sortedCounts(baselineItemCounts, "baselineItemCounts", true);
        expectedConsumedItemCounts = sortedCounts(
                expectedConsumedItemCounts, "expectedConsumedItemCounts", false);
        expectedProducedItemCounts = sortedCounts(
                expectedProducedItemCounts, "expectedProducedItemCounts", false);
        if (!sourceKind.permitsWorldMutation()) {
            throw new IllegalArgumentException(
                    "Capability observation must originate from a verified placement, route or port");
        }
        if (!componentPositions.containsKey(CapabilityObservationRoles.PRIMARY_MACHINE)) {
            throw new IllegalArgumentException("Capability observation requires a primary machine position");
        }
        if (expectedConsumedItemCounts.isEmpty() || expectedProducedItemCounts.isEmpty()) {
            throw new IllegalArgumentException("Capability observation needs exact consumed and produced items");
        }
        if (requestedAtTick < 0 || maximumObservationTicks < 1 || maximumObservationTicks > 72_000) {
            throw new IllegalArgumentException("Capability observation tick bounds are invalid");
        }
    }

    private static Map<ResourceId, BlockPos3i> sortedPositions(Map<ResourceId, BlockPos3i> values) {
        Objects.requireNonNull(values, "componentPositions");
        if (values.isEmpty() || values.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("componentPositions count must be 1.." + MAX_COMPONENTS);
        }
        TreeMap<ResourceId, BlockPos3i> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((key, value) -> sorted.put(
                Objects.requireNonNull(key, "component role"),
                Objects.requireNonNull(value, "component position")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<ResourceId, Long> sortedCounts(
            Map<ResourceId, Long> values,
            String name,
            boolean emptyAllowed) {
        Objects.requireNonNull(values, name);
        if ((!emptyAllowed && values.isEmpty()) || values.size() > MAX_ITEM_IDENTITIES) {
            throw new IllegalArgumentException(name + " count is invalid");
        }
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
            if (value < 0 || (!emptyAllowed && value < 1)) {
                throw new IllegalArgumentException(name + " contains an invalid count");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String fingerprint(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 2_048) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
