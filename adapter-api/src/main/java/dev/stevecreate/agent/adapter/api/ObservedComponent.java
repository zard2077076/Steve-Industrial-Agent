package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Immutable normalized block observation safe to process off the server thread. */
public record ObservedComponent(ResourceId blockId, BlockPos3i position, Map<String, String> state) {
    public ObservedComponent {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        state = Map.copyOf(Objects.requireNonNull(state, "state"));
    }
}

