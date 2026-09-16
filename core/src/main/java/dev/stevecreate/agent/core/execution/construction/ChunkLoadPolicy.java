package dev.stevecreate.agent.core.execution.construction;

/** Bot Fleet never grants chunk tickets or loads a missing construction chunk in Phase I. */
public enum ChunkLoadPolicy {
    REQUIRE_ALREADY_LOADED
}
