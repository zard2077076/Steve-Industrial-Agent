package dev.stevecreate.agent.core.model;

/** The six block-grid directions without a Minecraft dependency. */
public enum Direction6 {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST;

    public Direction6 rotateY(QuarterTurn turn) {
        if (this == UP || this == DOWN || turn == QuarterTurn.ZERO) {
            return this;
        }
        return switch (turn) {
            case ZERO -> this;
            case CLOCKWISE_90 -> switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
            case CLOCKWISE_180 -> rotateY(QuarterTurn.CLOCKWISE_90).rotateY(QuarterTurn.CLOCKWISE_90);
            case CLOCKWISE_270 -> rotateY(QuarterTurn.CLOCKWISE_180).rotateY(QuarterTurn.CLOCKWISE_90);
        };
    }
}

