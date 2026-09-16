package dev.stevecreate.agent.core.industrial;

/** Bounded physical action vocabulary exposed by an industrial adapter. */
public enum IndustrialActionType {
    PLACE_BLOCK,
    USE_TOOL,
    FORM_MULTIBLOCK,
    CONNECT_RESOURCE,
    INSERT_RESOURCE,
    EXTRACT_RESOURCE,
    START_MACHINE,
    OBSERVE_STATUS,
    DISCONNECT_RESOURCE,
    DISMANTLE,
    RECOVER
}
