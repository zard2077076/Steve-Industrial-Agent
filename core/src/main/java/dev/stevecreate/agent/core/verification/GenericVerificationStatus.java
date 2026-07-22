package dev.stevecreate.agent.core.verification;

/** Bounded rule-evaluation outcome with waiting and timeout kept distinct from failure. */
public enum GenericVerificationStatus {
    PASSED,
    PENDING,
    FAILED,
    TIMED_OUT
}
