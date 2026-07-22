package dev.stevecreate.agent.core.planning;

/** Role of a node before any adapter implementation or physical layout is selected. */
public enum LogicalNodeKind {
    PROCESS,
    RAW_RESOURCE_SOURCE,
    OWNED_RESOURCE_SOURCE,
    TARGET_SINK
}
