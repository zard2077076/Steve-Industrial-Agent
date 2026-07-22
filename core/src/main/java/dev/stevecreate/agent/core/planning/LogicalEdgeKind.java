package dev.stevecreate.agent.core.planning;

/** Provenance and topology category for one logical resource flow. */
public enum LogicalEdgeKind {
    RAW_INPUT,
    OWNED_INPUT,
    INTERMEDIATE,
    TARGET_OUTPUT
}
