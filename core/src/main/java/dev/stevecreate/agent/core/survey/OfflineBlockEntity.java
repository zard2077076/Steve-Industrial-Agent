package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Intentionally contains type and position only; inventory/content tags are discarded while parsing. */
public record OfflineBlockEntity(ResourceId resourceId, BlockPos3i position) {
    public OfflineBlockEntity {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(position, "position");
    }
}
