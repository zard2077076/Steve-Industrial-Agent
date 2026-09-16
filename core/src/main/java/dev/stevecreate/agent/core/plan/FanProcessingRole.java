package dev.stevecreate.agent.core.plan;

/** Unique owned roles in one bounded C-06 fan-processing cell. */
public enum FanProcessingRole {
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
    WATER_WHEEL,
    BOTTOM_GEARBOX,
    VERTICAL_SHAFT,
    TOP_GEARBOX,
    FAN_DRIVE_SHAFT,
    ENCASED_FAN,
    MEDIUM_SUPPORT,
    MEDIUM_LEFT_BARRIER,
    MEDIUM_RIGHT_BARRIER,
    MEDIUM_STOP,
    INPUT_DEPOT,
    PROCESSING_MEDIUM,
    OUTPUT_CHEST,
    WATER_SOURCE;

    public boolean isKinetic() {
        return switch (this) {
            case WATER_WHEEL, BOTTOM_GEARBOX, VERTICAL_SHAFT, TOP_GEARBOX,
                    FAN_DRIVE_SHAFT, ENCASED_FAN -> true;
            default -> false;
        };
    }
}
