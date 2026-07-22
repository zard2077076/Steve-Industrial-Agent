package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

/** Explicitly bounded scan request; callers cannot accidentally request an unbounded per-tick scan. */
public record ScanRequest(BlockPos3i center, int radius) {
    public static final int MAX_RADIUS = 16;

    public ScanRequest {
        Objects.requireNonNull(center, "center");
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be between 1 and " + MAX_RADIUS);
        }
    }
}

