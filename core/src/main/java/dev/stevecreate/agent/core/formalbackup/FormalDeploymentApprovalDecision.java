package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** No FORMAL_ALLOWED state exists. TEST_ONLY is structurally restore-copy-only. */
public record FormalDeploymentApprovalDecision(
        FormalApprovalDecisionState state,
        FormalApprovalEnvironment environment,
        Optional<Instant> decidedAt,
        Optional<String> authorizer,
        boolean formalExecutionAllowed) {
    public FormalDeploymentApprovalDecision {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(environment, "environment");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (formalExecutionAllowed
                || (state == FormalApprovalDecisionState.ABSENT
                        && (decidedAt.isPresent() || authorizer.isPresent()))
                || (state != FormalApprovalDecisionState.ABSENT
                        && (decidedAt.isEmpty() || authorizer.isEmpty()))
                || (state == FormalApprovalDecisionState.TEST_ONLY_ALLOWED
                        && (environment != FormalApprovalEnvironment.RESTORE_DRILL_COPY
                                || !authorizer.orElseThrow().equals("TEST_ONLY")))
                || (environment == FormalApprovalEnvironment.FORMAL_SOURCE
                        && state == FormalApprovalDecisionState.TEST_ONLY_ALLOWED)) {
            throw new IllegalArgumentException("approval decision violates the formal hard stop");
        }
    }

    public static FormalDeploymentApprovalDecision absentFormal() {
        return new FormalDeploymentApprovalDecision(FormalApprovalDecisionState.ABSENT,
                FormalApprovalEnvironment.FORMAL_SOURCE, Optional.empty(), Optional.empty(), false);
    }
}
