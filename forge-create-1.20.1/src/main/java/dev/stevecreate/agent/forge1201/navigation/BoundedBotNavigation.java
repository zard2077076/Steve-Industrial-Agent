package dev.stevecreate.agent.forge1201.navigation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-authoritative movement primitives for the visible Steve/Alex workers.
 *
 * <p>A path node is the block occupied by a bot's feet.  A normal Minecraft step is
 * therefore not {@code (x + 1, y, z)} only: when the next floor is one block higher
 * or lower, the next stand node is {@code (x + 1, y + 1, z)} or
 * {@code (x + 1, y - 1, z)}.  Keeping that rule here prevents the courier, C-10
 * worker and acceptance worker from quietly disagreeing about stairs.</p>
 */
public final class BoundedBotNavigation {
    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH);

    private BoundedBotNavigation() {}

    /**
     * Returns bounded four-way neighbours, including one-block up/down stair moves.
     *
     * <p>The source cell is deliberately not required to remain standable. A machine
     * placement or a loader update may occupy the cell underneath a worker while it is
     * between two already-authorized path nodes. The worker must still be able to leave
     * that stale cell; every destination remains fully stand-validated.</p>
     */
    public static List<BlockPos> neighbours(ServerLevel level, BlockPos current) {
        List<BlockPos> result = new ArrayList<>(12);
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos flat = current.relative(direction).immutable();
            addIfTraversable(level, current, flat, result);
            addIfTraversable(level, current, flat.above().immutable(), result);
            addIfTraversable(level, current, flat.below().immutable(), result);
        }
        return List.copyOf(result);
    }

    /** True for a flat move or a one-block horizontal stair transition. */
    public static boolean isAdjacentStep(BlockPos from, BlockPos to) {
        int horizontal = Math.abs(from.getX() - to.getX())
                + Math.abs(from.getZ() - to.getZ());
        return horizontal == 1 && Math.abs(from.getY() - to.getY()) <= 1;
    }

    /** Stand-cell validation shared by all server-driven workers. */
    public static boolean canStand(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.hasChunkAt(position.above())
                || !level.hasChunkAt(position.below())) return false;
        var feet = level.getBlockState(position);
        var head = level.getBlockState(position.above());
        var floor = level.getBlockState(position.below());
        return (feet.isAir() || feet.canBeReplaced())
                && (head.isAir() || head.canBeReplaced())
                && floor.isFaceSturdy(level, position.below(), Direction.UP);
    }

    private static void addIfTraversable(ServerLevel level, BlockPos from, BlockPos to,
            List<BlockPos> result) {
        if (isAdjacentStep(from, to) && canStand(level, to)) {
            result.add(to);
        }
    }
}
