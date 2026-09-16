package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact bounded intent accepted only after the generic server authority gates pass. */
public record ImmersiveEngineeringActionRequest(
        ResourceId requestId,
        ResourceId projectId,
        ResourceId verifiedPhysicalPlanId,
        ResourceId actionId,
        List<BlockPos3i> positions,
        Optional<ResourceId> retainedTool,
        List<ResourceId> materialAllocationIds,
        String regionAuthorizationSha256,
        String expectedWorldSnapshotSha256,
        int maximumMutations,
        long expiresAtTick) {
    public ImmersiveEngineeringActionRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(actionId, "actionId");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.isEmpty() || positions.size() > 4_096) {
            throw new IllegalArgumentException("IE action positions are empty or unbounded");
        }
        retainedTool = Objects.requireNonNull(retainedTool, "retainedTool");
        materialAllocationIds = List.copyOf(Objects.requireNonNull(
                materialAllocationIds, "materialAllocationIds"));
        if (materialAllocationIds.size() > 4_096) {
            throw new IllegalArgumentException("IE action allocations exceed bound");
        }
        if (!hash(regionAuthorizationSha256) || !hash(expectedWorldSnapshotSha256)
                || maximumMutations < 0 || maximumMutations > 4_096 || expiresAtTick < 1) {
            throw new IllegalArgumentException("IE action authority is incomplete");
        }
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
