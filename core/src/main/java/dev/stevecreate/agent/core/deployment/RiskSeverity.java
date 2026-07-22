package dev.stevecreate.agent.core.deployment;

/** Fixed deterministic ordering; no model or free text may assign severity. */
public enum RiskSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
