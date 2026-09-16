package dev.stevecreate.agent.core.execution.construction;

/** Stable reason that a verified project needs a material line. */
public enum ProjectMaterialPurpose {
    PROCESS_INPUT,
    MACHINE_COMPONENT,
    ITEM_ROUTE_COMPONENT,
    POWER_COMPONENT,
    PROCESS_MEDIUM,
    FUEL,
    RETAINED_TOOL
}
