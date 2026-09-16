package dev.stevecreate.agent.core.execution.construction;

/** Honest per-mode capability state; unsupported Bot work may not be reported as success. */
public enum CapabilitySupport {
    SUPPORTED,
    CONSTRAINED,
    UNSUPPORTED;

    public boolean executable() {
        return this != UNSUPPORTED;
    }
}
