package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.UUID;

/** One player's exact, expiring and single-use approval for one maintenance proposal. */
public record FactoryMaintenanceApproval(
        ResourceId proposalId,
        UUID playerId,
        String diagnosticHash,
        FactoryFaultCode faultCode,
        FactoryRecommendedAction recommendedAction,
        long approvedAtTick,
        long expiresAtTick,
        State state,
        long generation) {
    public FactoryMaintenanceApproval {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(diagnosticHash, "diagnosticHash");
        Objects.requireNonNull(faultCode, "faultCode");
        Objects.requireNonNull(recommendedAction, "recommendedAction");
        Objects.requireNonNull(state, "state");
        if (!diagnosticHash.matches("[0-9a-f]{64}") || approvedAtTick < 0
                || expiresAtTick <= approvedAtTick
                || expiresAtTick - approvedAtTick
                        > FactoryMaintenanceProposal.MAX_LIFETIME_TICKS
                || generation < 1) {
            throw new IllegalArgumentException("maintenance approval is invalid");
        }
    }

    FactoryMaintenanceApproval consume(long tick) {
        if (state != State.APPROVED || tick < approvedAtTick || tick >= expiresAtTick) {
            throw new IllegalStateException("maintenance approval cannot be consumed");
        }
        return new FactoryMaintenanceApproval(proposalId, playerId, diagnosticHash,
                faultCode, recommendedAction, approvedAtTick, expiresAtTick,
                State.CONSUMED, Math.addExact(generation, 1));
    }

    public enum State { APPROVED, CONSUMED }
}
