package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One idempotent journal row for an exact allocation quantity. */
public record MaterialTransaction(
        ResourceId transactionId,
        ResourceId projectId,
        ResourceId taskId,
        ResourceId allocationId,
        MaterialExecutorKind executor,
        long quantity,
        MaterialTransactionState state,
        long generation,
        long updatedTick) {
    public MaterialTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(allocationId, "allocationId");
        Objects.requireNonNull(executor, "executor");
        if (quantity < 1 || quantity > 1_000_000_000L || generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("transaction quantity/generation/tick invalid");
        }
        Objects.requireNonNull(state, "state");
    }

    public MaterialTransaction advance(MaterialTransactionState next, long nextGeneration, long tick) {
        if (!allowed(state, next)) throw new IllegalStateException("invalid material transition " + state + " -> " + next);
        return new MaterialTransaction(transactionId, projectId, taskId, allocationId,
                executor, quantity, next, nextGeneration, tick);
    }

    private static boolean allowed(MaterialTransactionState from, MaterialTransactionState to) {
        return switch (from) {
            case PREPARED -> to == MaterialTransactionState.WITHDRAWN
                    || to == MaterialTransactionState.RELEASED;
            case WITHDRAWN -> to == MaterialTransactionState.DELIVERED
                    || to == MaterialTransactionState.RETURN_PENDING;
            case DELIVERED -> to == MaterialTransactionState.CONSUMED
                    || to == MaterialTransactionState.RETURN_PENDING;
            case RETURN_PENDING -> to == MaterialTransactionState.RETURNED;
            case CONSUMED, RELEASED, RETURNED -> false;
        };
    }
}
