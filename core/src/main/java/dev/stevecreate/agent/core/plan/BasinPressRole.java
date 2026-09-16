package dev.stevecreate.agent.core.plan;

public enum BasinPressRole {
    FLOW_CATCH_FLOOR,
    FLOW_CATCH_WEST_WALL,
    FLOW_CATCH_EAST_WALL,
    FLOW_CATCH_NORTH_WALL,
    FLOW_CATCH_SOUTH_WALL,
    FLOW_CHAMBER_WEST_WALL,
    FLOW_CHAMBER_EAST_WALL,
    FLOW_CHAMBER_NORTH_WALL,
    FLOW_CHAMBER_SOUTH_WALL,
    FLOW_CHANNEL_WEST_WALL,
    FLOW_CHANNEL_EAST_WALL,
    FLOW_CHANNEL_NORTH_WALL,
    PRESS_PLATFORM,
    WATER_WHEEL,
    BOTTOM_GEARBOX,
    VERTICAL_SHAFT,
    TOP_GEARBOX,
    HORIZONTAL_SHAFT,
    MECHANICAL_PRESS,
    BASIN,
    OUTPUT_CHEST,
    WATER_SOURCE;

    public boolean isKinetic() {
        return switch (this) {
            case WATER_WHEEL, BOTTOM_GEARBOX, VERTICAL_SHAFT,
                    TOP_GEARBOX, HORIZONTAL_SHAFT, MECHANICAL_PRESS -> true;
            default -> false;
        };
    }
}
