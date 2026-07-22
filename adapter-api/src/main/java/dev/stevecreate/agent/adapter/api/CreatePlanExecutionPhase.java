package dev.stevecreate.agent.adapter.api;

/** Typed, finite phases for a bounded server-thread Create plan execution. */
public enum CreatePlanExecutionPhase {
    BUILDING,
    AWAITING_POWER,
    FEEDING,
    PROCESSING
}
