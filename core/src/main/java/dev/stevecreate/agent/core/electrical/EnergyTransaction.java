package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One idempotent exact FE settlement; consumed FE cannot be returned or silently replayed. */
public record EnergyTransaction(
        ResourceId transactionId,
        ResourceId projectId,
        ResourceId taskId,
        ResourceId sourceEndpointId,
        ResourceId energyResource,
        long amountFe,
        EnergyTransactionState state,
        long generation,
        long updatedTick) {
    public EnergyTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceEndpointId, "sourceEndpointId");
        Objects.requireNonNull(energyResource, "energyResource");
        if (amountFe < 1 || amountFe > 1_000_000_000_000L || generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("energy transaction values are invalid");
        }
        Objects.requireNonNull(state, "state");
    }

    public EnergyTransaction advance(EnergyTransactionState next, long nextGeneration, long tick) {
        boolean legal = state == EnergyTransactionState.PREPARED
                && (next == EnergyTransactionState.SETTLED || next == EnergyTransactionState.RELEASED);
        if (!legal || nextGeneration <= generation || tick < updatedTick) {
            throw new IllegalStateException("illegal energy transaction transition");
        }
        return new EnergyTransaction(transactionId, projectId, taskId, sourceEndpointId,
                energyResource, amountFe, next, nextGeneration, tick);
    }
}
