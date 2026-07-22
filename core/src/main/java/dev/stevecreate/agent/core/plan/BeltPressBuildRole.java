package dev.stevecreate.agent.core.plan;

/** A typed C-04 construction role; temporary pulley shafts are not final evidence roles. */
public enum BeltPressBuildRole {
    BELT_START_PULLEY_SHAFT,
    BELT_END_PULLEY_SHAFT,
    BELT_DRIVE,
    PRESS_DRIVE,
    MECHANICAL_PRESS,
    OUTPUT_CHEST,
    OUTPUT_FUNNEL,
    CONNECT_BELT
}
