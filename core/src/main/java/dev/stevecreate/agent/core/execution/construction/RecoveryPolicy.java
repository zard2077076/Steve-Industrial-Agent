package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Set;

/** Bounded reload/retry policy that preserves non-duplication of resources and mutations. */
public record RecoveryPolicy(
        RecoveryStrategy strategy,
        int maximumRecoveryAttempts,
        boolean resourceConsumptionReplayForbidden,
        Set<ResourceId> requiredReconciliationEvidence) {
    public static final int MAX_RECOVERY_ATTEMPTS = 8;
    public static final RecoveryPolicy REFUSE = new RecoveryPolicy(
            RecoveryStrategy.REFUSE, 0, true, Set.of());

    public RecoveryPolicy {
        Objects.requireNonNull(strategy, "strategy");
        int minimum = strategy == RecoveryStrategy.REFUSE ? 0 : 1;
        int maximum = strategy == RecoveryStrategy.REFUSE ? 0 : MAX_RECOVERY_ATTEMPTS;
        if (maximumRecoveryAttempts < minimum || maximumRecoveryAttempts > maximum) {
            throw new IllegalArgumentException("maximumRecoveryAttempts must be between "
                    + minimum + " and " + maximum + " for " + strategy);
        }
        if (!resourceConsumptionReplayForbidden) {
            throw new IllegalArgumentException("Resource-consuming work may never be blindly replayed");
        }
        requiredReconciliationEvidence = ConstructionContractValues.sortedIds(
                requiredReconciliationEvidence,
                "requiredReconciliationEvidence",
                strategy.requiresExactRescan());
        if (!strategy.requiresExactRescan() && !requiredReconciliationEvidence.isEmpty()) {
            throw new IllegalArgumentException("Only exact-rescan recovery may require reconciliation evidence");
        }
    }
}
