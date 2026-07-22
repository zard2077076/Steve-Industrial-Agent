package dev.stevecreate.agent.core.deployment;

/** Final fail-closed check shared by every mutation-capable entry point. */
public final class FormalWorldWriteGuard {
    public DeploymentGuardResult verify(DeploymentWriteRequest request) {
        WorldEnvironmentDescriptor environment = request.environment();
        WorldEnvironmentType type = environment.environmentType();
        if (type == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                || type == WorldEnvironmentType.FORBIDDEN_WORLD
                || type == WorldEnvironmentType.UNKNOWN_WORLD
                || !environment.writable() || !environment.executionAllowed()
                || !request.policy().executionPermitted()) {
            return failure(request, DeploymentFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "World classification or policy has no execution authority", false,
                    "Use an explicitly classified disposable isolated world");
        }
        if (!request.policy().allowsEnvironment(type, environment.gameDirectoryIdentity())) {
            return failure(request, DeploymentFailureCode.FORBIDDEN_GAME_DIRECTORY,
                    "Canonical gameDir is outside the deployment policy", false,
                    "Use the exact policy-allowed isolated gameDir");
        }
        if (!request.approvalValid()) return failure(request, DeploymentFailureCode.HUMAN_APPROVAL_REQUIRED,
                "Exact scoped approval is absent", true, "Obtain a current scoped approval");
        if (request.policy().backupRequired() && !request.backupValid())
            return failure(request, DeploymentFailureCode.BACKUP_REQUIRED,
                    "Required verified backup is absent", true, "Complete the isolated backup verification");
        if (!request.snapshotFresh()) return failure(request, DeploymentFailureCode.WORLD_SNAPSHOT_STALE,
                "World snapshot is stale", true, "Generate a new dry-run preview");
        if (!request.regionAuthorized()) return failure(request, DeploymentFailureCode.REGION_AUTHORIZATION_MISSING,
                "Region authorization is absent", true, "Authorize the exact preview region");
        if (!request.mutationBudgetValid()) return failure(request, DeploymentFailureCode.MUTATION_BUDGET_EXCEEDED,
                "Mutation budget is invalid", true, "Reduce the plan or approve a smaller bounded policy");
        return new DeploymentWritePermit(request);
    }

    private static DeploymentGuardFailure failure(DeploymentWriteRequest request,
            DeploymentFailureCode code, String reason, boolean userAction, String next) {
        return new DeploymentGuardFailure(code, request.entryPoint(), reason, userAction, next);
    }
}
