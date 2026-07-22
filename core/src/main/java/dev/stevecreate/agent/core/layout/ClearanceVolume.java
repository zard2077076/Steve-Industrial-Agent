package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Set;

public record ClearanceVolume(Set<BlockPos3i> cells) {
    public ClearanceVolume {
        cells = GeometryValues.cells(cells, "clearance", false);
    }
}
