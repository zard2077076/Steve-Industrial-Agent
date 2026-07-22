package dev.stevecreate.agent.core.formalbackup;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Independent unconditional FB-05/B-07 barrier. */
public final class FormalWorldExecutionHardStop {
    private static final String NOT_APPLICABLE = "not-applicable";

    public FormalExecutionHardStopResult evaluate(
            String worldIdentity,
            Optional<FormalDeploymentApprovalRequest> request,
            Optional<FormalDeploymentApprovalDecision> decision) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        request = Objects.requireNonNull(request, "request");
        decision = Objects.requireNonNull(decision, "decision");
        String backup = request.map(value -> value.candidatePackage().backupIdentity().backupIdentity())
                .orElse(NOT_APPLICABLE);
        String candidate = request.map(value -> value.candidatePackage().packageIdentity())
                .orElse(NOT_APPLICABLE);
        String runtime = request.map(value -> value.candidatePackage().runtimeFingerprint())
                .orElse(NOT_APPLICABLE);
        String source = request.map(value -> value.candidatePackage().backupIdentity().sourceFingerprint())
                .orElse(NOT_APPLICABLE);
        String approval = decision.map(value -> value.state().name()).orElse("ABSENT");
        FormalBackupFailure failure = new FormalBackupFailure(
                FormalBackupFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                FormalBackupStage.APPROVAL_REQUEST, worldIdentity, backup, candidate,
                NOT_APPLICABLE, NOT_APPLICABLE, source, runtime, 0, 0,
                NOT_APPLICABLE, approval,
                List.of("formalSource=true", "separateExecutionPilotApprovalRequired=true",
                        "writeGuardBypassAvailable=false"),
                "Stage B never authorizes execution against the formal source world",
                "In a future separate stage, explicitly select one candidate and approve its exact preview, budgets, resource source and formal execution pilot");
        return new FormalExecutionHardStopResult(failure, false, false);
    }
}
