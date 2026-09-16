package dev.stevecreate.agent.core.execution.construction;

/** Explicit recovery boundary for one task; no strategy permits blind replay. */
public enum RecoveryStrategy {
    REFUSE,
    PRE_MUTATION_RESTART,
    EXACT_RESCAN_RESUME,
    EXACT_RESCAN_REASSIGN,
    IDEMPOTENT_VERIFY_ONLY;

    public boolean requiresExactRescan() {
        return this == EXACT_RESCAN_RESUME
                || this == EXACT_RESCAN_REASSIGN
                || this == IDEMPOTENT_VERIFY_ONLY;
    }

    public boolean allowsReassignment() {
        return this == EXACT_RESCAN_REASSIGN;
    }
}
