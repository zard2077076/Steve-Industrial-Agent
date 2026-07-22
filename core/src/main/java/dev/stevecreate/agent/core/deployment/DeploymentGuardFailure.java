package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record DeploymentGuardFailure(
        DeploymentFailureCode code,
        DeploymentWriteEntryPoint entryPoint,
        String reason,
        boolean userActionRequired,
        String safeNextStep) implements DeploymentGuardResult {
    public DeploymentGuardFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(entryPoint, "entryPoint");
        reason = WorldEnvironmentEvidence.text(reason, "reason");
        safeNextStep = WorldEnvironmentEvidence.text(safeNextStep, "safeNextStep");
    }
}
