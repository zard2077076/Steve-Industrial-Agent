package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Captures an explicit bounded snapshot without loading chunks or changing blocks. */
public final class ForgeReadOnlyPlacementSnapshots {
    private ForgeReadOnlyPlacementSnapshots() {}

    public static PlacementSnapshot capture(
            ServerLevel level,
            String runtimeFingerprint,
            BlockPos3i anchor,
            int horizontalRadius,
            int below,
            int above,
            long generation) {
        return capture(level, runtimeFingerprint, anchor, horizontalRadius, below, above,
                generation, false);
    }

    /**
     * Explicitly reads a small chunk window before capture. Intended only for disposable isolated
     * acceptance worlds whose optimization mods unload every spawn chunk before ServerStartedEvent.
     */
    public static PlacementSnapshot captureWithBoundedChunkReads(
            ServerLevel level,
            String runtimeFingerprint,
            BlockPos3i anchor,
            int horizontalRadius,
            int below,
            int above,
            long generation) {
        return capture(level, runtimeFingerprint, anchor, horizontalRadius, below, above,
                generation, true);
    }

    private static PlacementSnapshot capture(
            ServerLevel level,
            String runtimeFingerprint,
            BlockPos3i anchor,
            int horizontalRadius,
            int below,
            int above,
            long generation,
            boolean readMissingChunks) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(anchor, "anchor");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Placement snapshot capture requires the server thread");
        }
        if (horizontalRadius < 1 || horizontalRadius > 48 || below < 0 || above < 1
                || below + above + 1 > 32) {
            throw new IllegalArgumentException("Placement snapshot bounds are outside the hard limit");
        }
        if (readMissingChunks) {
            Set<Long> chunks = new LinkedHashSet<>();
            for (int x = anchor.x() - horizontalRadius; x <= anchor.x() + horizontalRadius; x++) {
                for (int z = anchor.z() - horizontalRadius; z <= anchor.z() + horizontalRadius; z++) {
                    long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                    chunks.add(key);
                }
            }
            if (chunks.size() > 49) {
                throw new IllegalArgumentException("bounded snapshot cannot read more than 49 chunks");
            }
            for (long key : chunks) {
                level.getChunk((int) (key >> 32), (int) key);
            }
        }
        Map<BlockPos3i, LayoutCellState> cells = new LinkedHashMap<>();
        for (int x = anchor.x() - horizontalRadius; x <= anchor.x() + horizontalRadius; x++) {
            for (int y = anchor.y() - below; y <= anchor.y() + above; y++) {
                for (int z = anchor.z() - horizontalRadius; z <= anchor.z() + horizontalRadius; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    LayoutCellState state;
                    if (!level.hasChunkAt(position)) {
                        state = LayoutCellState.UNLOADED;
                    } else {
                        BlockState block = level.getBlockState(position);
                        state = block.isAir() || block.canBeReplaced()
                                ? LayoutCellState.REPLACEABLE : LayoutCellState.OCCUPIED;
                    }
                    cells.put(new BlockPos3i(x, y, z), state);
                }
            }
        }
        return new PlacementSnapshot(runtimeFingerprint, generation, cells);
    }
}
