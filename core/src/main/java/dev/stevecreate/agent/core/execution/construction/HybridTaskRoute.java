package dev.stevecreate.agent.core.execution.construction;

/** Physical backend selected by a Hybrid policy; HYBRID itself remains the caller-visible mode. */
public enum HybridTaskRoute {
    DIRECT,
    BOTS,
    REFUSE
}
