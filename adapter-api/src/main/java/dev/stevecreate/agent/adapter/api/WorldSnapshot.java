package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded, point-in-time world data. It is never a live Level reference. */
public record WorldSnapshot(
        UUID snapshotId,
        long gameTick,
        RuntimeFingerprint runtime,
        BlockPos3i center,
        int radius,
        List<ObservedComponent> components) {
    public WorldSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(center, "center");
        if (radius < 0 || radius > ScanRequest.MAX_RADIUS) {
            throw new IllegalArgumentException("radius outside supported range");
        }
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }
}

