package dev.stevecreate.agent.adapter.api;

/** Mutually exclusive high-level state derived from a point-in-time kinetic snapshot. */
public enum KineticOperatingState {
    STOPPED,
    POWERED,
    OVERSTRESSED
}
