package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/**
 * Immutable request for a future separately-authorized maintenance action.
 *
 * <p>A proposal is evidence, not permission. It intentionally cannot carry inventory,
 * world-mutation or execution authority.</p>
 */
public record FactoryMaintenanceProposal(
        ResourceId proposalId,
        ResourceId subjectId,
        long diagnosticTick,
        long expiresAtTick,
        FactoryFaultCode faultCode,
        FactoryHealthCategory category,
        FactoryRecommendedAction recommendedAction,
        String evidenceCode,
        String diagnosticHash,
        State state,
        boolean worldMutationAuthorized,
        boolean inventoryAccessAuthorized,
        boolean executionAuthorized) {
    public static final long MAX_LIFETIME_TICKS = 12_000;

    public FactoryMaintenanceProposal {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(faultCode, "faultCode");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(recommendedAction, "recommendedAction");
        Objects.requireNonNull(evidenceCode, "evidenceCode");
        Objects.requireNonNull(diagnosticHash, "diagnosticHash");
        Objects.requireNonNull(state, "state");
        if (diagnosticTick < 0 || expiresAtTick <= diagnosticTick
                || expiresAtTick - diagnosticTick > MAX_LIFETIME_TICKS) {
            throw new IllegalArgumentException("maintenance proposal lifetime is invalid");
        }
        if (!evidenceCode.matches("[A-Z0-9_:.\\-/]{1,128}")
                || !diagnosticHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("maintenance proposal evidence is invalid");
        }
        if (state != State.AWAITING_EXPLICIT_APPROVAL || worldMutationAuthorized
                || inventoryAccessAuthorized || executionAuthorized) {
            throw new IllegalArgumentException("maintenance proposal attempted authority");
        }
    }

    public enum State { AWAITING_EXPLICIT_APPROVAL }
}
