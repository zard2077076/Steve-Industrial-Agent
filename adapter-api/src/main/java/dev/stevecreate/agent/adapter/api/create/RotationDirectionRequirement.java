package dev.stevecreate.agent.adapter.api.create;

/** Directional kinetic relationship; it never chooses a world orientation. */
public enum RotationDirectionRequirement {
    ANY_NONZERO,
    OPPOSED_INWARD_PAIR,
    ALONG_MACHINE_FACING,
    FORWARD_PROCESSING_DIRECTION
}
