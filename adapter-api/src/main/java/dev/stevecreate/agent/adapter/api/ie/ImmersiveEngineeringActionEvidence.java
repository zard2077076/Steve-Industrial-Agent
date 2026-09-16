package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ImmersiveEngineeringActionEvidence(
        ResourceId requestId,
        ResourceId actionId,
        IndustrialLifecyclePhase lifecyclePhase,
        int mutations,
        List<ResourceId> retainedToolsReturned,
        Map<ResourceId, Long> observedResourceDeltas,
        String beforeSnapshotSha256,
        String afterSnapshotSha256,
        long observedTick,
        boolean verified) {
    public ImmersiveEngineeringActionEvidence {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(lifecyclePhase, "lifecyclePhase");
        if (mutations < 0 || mutations > 4_096 || observedTick < 0 || !verified) {
            throw new IllegalArgumentException("IE action evidence is not verified or bounded");
        }
        retainedToolsReturned = List.copyOf(Objects.requireNonNull(
                retainedToolsReturned, "retainedToolsReturned"));
        observedResourceDeltas = Map.copyOf(Objects.requireNonNull(
                observedResourceDeltas, "observedResourceDeltas"));
        if (!hash(beforeSnapshotSha256) || !hash(afterSnapshotSha256)) {
            throw new IllegalArgumentException("IE action evidence hash is invalid");
        }
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
