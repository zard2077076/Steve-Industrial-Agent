package dev.stevecreate.agent.core.player;

/** Durable, monotonic player workflow stage. */
public enum WorkflowStage {
    GOAL_SELECTION,
    PLACEMENT_PREVIEW,
    SITE_SURVEY,
    AWAITING_APPROVAL,
    CLEARING,
    POST_CLEAR_RESCAN,
    MATERIAL_SOURCE_SELECTION,
    MATERIAL_RESERVED,
    CONSTRUCTION,
    PAUSED,
    COMPLETED,
    CANCELLED,
    REFUSED
}
