package dev.stevecreate.agent.core.execution.construction;

public enum TaskFailureCategory {
    RESERVATION_CONFLICT(true),
    MATERIAL_UNAVAILABLE(false),
    CHUNK_UNAVAILABLE(true),
    NAVIGATION_BLOCKED(true),
    WORKER_UNAVAILABLE(true),
    STATE_CHANGED(false),
    OWNERSHIP_LOST(false),
    UNSUPPORTED_CAPABILITY(false),
    HIGH_RISK_INTERACTION(false),
    RECOVERY_REFUSED(false),
    VERIFICATION_FAILED(false),
    CANCELLED(false),
    INTERNAL_CONTRACT_VIOLATION(false);

    private final boolean retryEligible;

    TaskFailureCategory(boolean retryEligible) {
        this.retryEligible = retryEligible;
    }

    public boolean retryEligible() {
        return retryEligible;
    }
}
