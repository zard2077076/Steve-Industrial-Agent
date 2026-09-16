package dev.stevecreate.agent.core.industrial;

/** Adapter-neutral lifecycle of a physically observed industrial implementation. */
public enum IndustrialLifecyclePhase {
    DISCOVERED,
    PLANNED,
    PLACED,
    FORMED,
    CONNECTED,
    CONFIGURED,
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    DISMANTLING,
    RECOVERED,
    CANCELLED,
    FAILED
}
