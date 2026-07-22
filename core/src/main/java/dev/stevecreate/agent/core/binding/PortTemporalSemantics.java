package dev.stevecreate.agent.core.binding;

/** Whether a logical implementation port expects a continuous or pulsed connection. */
public enum PortTemporalSemantics {
    CONTINUOUS,
    PULSED,
    EITHER
}
