package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Set;

public record MachineFootprint(Set<BlockPos3i> cells) {
    public MachineFootprint {
        cells = GeometryValues.cells(cells, "footprint", true);
    }
}
