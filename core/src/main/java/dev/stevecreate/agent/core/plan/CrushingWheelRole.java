package dev.stevecreate.agent.core.plan;

/** One unique final role in the bounded C-05 crushing-wheel layout. */
public enum CrushingWheelRole {
    LEFT_FLOW_FLOOR,
    LEFT_SOURCE_WEST_WALL,
    LEFT_SOURCE_EAST_WALL,
    SHARED_SOURCE_CENTER_WALL,
    LEFT_SOURCE_OUTER_WALL,
    RIGHT_FLOW_FLOOR,
    RIGHT_SOURCE_WEST_WALL,
    RIGHT_SOURCE_EAST_WALL,
    RIGHT_SOURCE_OUTER_WALL,
    OUTPUT_CHEST,
    OUTPUT_HOPPER,
    LEFT_DRIVE,
    RIGHT_DRIVE,
    LEFT_WHEEL,
    RIGHT_WHEEL,
    LEFT_WATER_SOURCE,
    RIGHT_WATER_SOURCE;

    public boolean isKinetic() {
        return switch (this) {
            case LEFT_DRIVE, RIGHT_DRIVE, LEFT_WHEEL, RIGHT_WHEEL -> true;
            default -> false;
        };
    }
}
