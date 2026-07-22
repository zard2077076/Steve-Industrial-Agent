package dev.stevecreate.agent.core.model;

/** Immutable loader-neutral block position. */
public record BlockPos3i(int x, int y, int z) {
    public BlockPos3i translate(int dx, int dy, int dz) {
        return new BlockPos3i(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public BlockPos3i rotateY(QuarterTurn turn) {
        return switch (turn) {
            case ZERO -> this;
            case CLOCKWISE_90 -> new BlockPos3i(-z, y, x);
            case CLOCKWISE_180 -> new BlockPos3i(-x, y, -z);
            case CLOCKWISE_270 -> new BlockPos3i(z, y, -x);
        };
    }
}

