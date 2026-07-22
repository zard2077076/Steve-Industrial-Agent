package dev.stevecreate.agent.core.plan;

/** One unique final role in the bounded C-04 belt/press layout. */
public enum BeltPressRole {
    BELT_DRIVE,
    PRESS_DRIVE,
    MECHANICAL_PRESS,
    OUTPUT_CHEST,
    OUTPUT_FUNNEL,
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
            case BELT_DRIVE, PRESS_DRIVE, MECHANICAL_PRESS, BELT_START, BELT_PRESSING, BELT_END -> true;
            case OUTPUT_CHEST, OUTPUT_FUNNEL -> false;
        };
    }
}
