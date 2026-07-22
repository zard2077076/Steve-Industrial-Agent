package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Complete typed refusal context required by deployment commands and audits. */
public record DeploymentFailure(
        DeploymentFailureCode code,
        DeploymentFailureStage stage,
        String worldIdentity,
        WorldEnvironmentType environmentType,
        String gameDirectoryIdentity,
        ResourceId target,
        String previewHash,
        DeploymentBoundingBox region,
        String policy,
        String risk,
        String approval,
        String backupIdentity,
        String runtimeFingerprint,
        List<String> trace,
        String reason,
        boolean userActionRequired,
        String safeNextStep) {
    public DeploymentFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        worldIdentity = text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(environmentType, "environmentType");
        gameDirectoryIdentity = text(gameDirectoryIdentity, "gameDirectoryIdentity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(previewHash, "previewHash");
        if (!previewHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("previewHash must be lowercase SHA-256 hex");
        }
        Objects.requireNonNull(region, "region");
        policy = text(policy, "policy");
        risk = text(risk, "risk");
        approval = text(approval, "approval");
        backupIdentity = text(backupIdentity, "backupIdentity");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (trace.isEmpty() || trace.size() > 256 || trace.stream().anyMatch(item ->
                item == null || item.isBlank() || item.length() > 2_048)) {
            throw new IllegalArgumentException("trace is empty or invalid");
        }
        reason = text(reason, "reason");
        safeNextStep = text(safeNextStep, "safeNextStep");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
