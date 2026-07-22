package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record DeploymentWriteRequest(
        WorldEnvironmentDescriptor environment,
        DeploymentPolicy policy,
        DeploymentWriteEntryPoint entryPoint,
        boolean approvalValid,
        boolean backupValid,
        boolean snapshotFresh,
        boolean regionAuthorized,
        boolean mutationBudgetValid) {
    public DeploymentWriteRequest {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(entryPoint, "entryPoint");
    }
}
