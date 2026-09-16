package dev.stevecreate.agent.core.plan;

/** One unique final role in the bounded C-04 belt/press layout. */
public enum BeltPressRole {
    BELT_FLOW_FLOOR,
    BELT_FLOW_OUTER_FLOOR,
    BELT_FLOW_OUTER_WEST_WALL,
    BELT_FLOW_OUTER_EAST_WALL,
    BELT_FLOW_NORTH_WALL,
    BELT_BASE_START,
    BELT_BASE_PRESSING,
    BELT_BASE_END,
    PRESS_FLOW_FLOOR,
    PRESS_FLOW_OUTER_FLOOR,
    PRESS_FLOW_EAST_WALL,
    PRESS_FLOW_NORTH_WALL,
    BELT_WATER_WHEEL,
    BELT_GEARBOX,
    BELT_DRIVE_SHAFT,
    PRESS_WATER_WHEEL,
    PRESS_GEARBOX,
    PRESS_DRIVE_SHAFT,
    MECHANICAL_PRESS,
    OUTPUT_CHEST,
    OUTPUT_FUNNEL,
    BELT_WATER_SOURCE,
    PRESS_WATER_SOURCE,
    BELT_START,
    BELT_PRESSING,
    BELT_END;

    public boolean isBelt() {
        return switch (this) {
            case BELT_START, BELT_PRESSING, BELT_END -> true;
            default -> false;
        };
    }

    public boolean isKinetic() {
        return switch (this) {
            case BELT_WATER_WHEEL, BELT_GEARBOX, BELT_DRIVE_SHAFT,
                    PRESS_WATER_WHEEL, PRESS_GEARBOX, PRESS_DRIVE_SHAFT,
                    MECHANICAL_PRESS, BELT_START, BELT_PRESSING, BELT_END -> true;
            default -> false;
        };
    }
}
