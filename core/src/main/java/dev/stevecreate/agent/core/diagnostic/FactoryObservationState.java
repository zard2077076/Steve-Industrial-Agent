package dev.stevecreate.agent.core.diagnostic;

/** Unknown is intentionally distinct from healthy: an absent probe is not evidence. */
public enum FactoryObservationState {
    HEALTHY,
    FAULT,
    UNKNOWN,
    NOT_APPLICABLE
}
