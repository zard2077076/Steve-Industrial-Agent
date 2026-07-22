package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Objects;

/** A bounded point read. It never authorizes a graph walk or a chunk load. */
public record KineticCaptureRequest(BlockPos3i position) {
    public KineticCaptureRequest {
        Objects.requireNonNull(position, "position");
    }
}
