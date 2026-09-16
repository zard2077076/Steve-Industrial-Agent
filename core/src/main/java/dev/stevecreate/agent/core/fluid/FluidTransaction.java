package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

public record FluidTransaction(
        ResourceId transactionId,
        ResourceId projectId,
        ResourceId taskId,
        ResourceId sourceEndpointId,
        FluidIdentity identity,
        long amountMb,
        FluidTransactionState state,
        long generation,
        long updatedTick) {
    public FluidTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceEndpointId, "sourceEndpointId");
        Objects.requireNonNull(identity, "identity");
        if (amountMb < 1 || amountMb > 1_000_000_000_000L || generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("fluid transaction values are invalid");
        }
        Objects.requireNonNull(state, "state");
    }

    public FluidTransaction advance(FluidTransactionState next, long nextGeneration, long tick) {
        boolean legal = switch (state) {
            case PREPARED -> next == FluidTransactionState.WITHDRAWN
                    || next == FluidTransactionState.RELEASED;
            case WITHDRAWN -> next == FluidTransactionState.DELIVERED
                    || next == FluidTransactionState.RETURN_PENDING;
            case DELIVERED -> next == FluidTransactionState.CONSUMED
                    || next == FluidTransactionState.RETURN_PENDING;
            case RETURN_PENDING -> next == FluidTransactionState.RETURNED;
            case CONSUMED, RELEASED, RETURNED -> false;
        };
        if (!legal || nextGeneration <= generation || tick < updatedTick) {
            throw new IllegalStateException("illegal fluid transaction transition");
        }
        return new FluidTransaction(transactionId, projectId, taskId, sourceEndpointId,
                identity, amountMb, next, nextGeneration, tick);
    }
}
