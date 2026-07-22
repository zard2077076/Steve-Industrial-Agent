package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.DeploymentDryRunReport;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.RollbackPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FormalDeploymentCandidateInput(
        String worldIdentity,
        CandidateIndustrialZone candidateZone,
        ResourceId target,
        long quantity,
        Optional<DeploymentDryRunReport> dryRunReport,
        Optional<ResourceId> verifiedPhysicalPlanIdentity,
        String runtimeFingerprint,
        String worldSnapshotFingerprint,
        BackupVerificationResult backup,
        PermissionDecision claimPermission,
        List<RegionAuthorizedOperation> requiredOperations,
        List<String> missingAuthorizations,
        RollbackPolicy rollbackPolicy,
        Instant expiresAt,
        String humanSummary) {
    public FormalDeploymentCandidateInput {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (!worldIdentity.matches("world:[0-9a-f]{64}")) throw new IllegalArgumentException("worldIdentity is invalid");
        Objects.requireNonNull(candidateZone, "candidateZone");
        Objects.requireNonNull(target, "target");
        if (quantity < 1 || quantity > 1_000_000_000L) throw new IllegalArgumentException("quantity is invalid");
        dryRunReport = Objects.requireNonNull(dryRunReport, "dryRunReport");
        verifiedPhysicalPlanIdentity = Objects.requireNonNull(
                verifiedPhysicalPlanIdentity, "verifiedPhysicalPlanIdentity");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        worldSnapshotFingerprint = text(worldSnapshotFingerprint, "worldSnapshotFingerprint");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(claimPermission, "claimPermission");
        requiredOperations = List.copyOf(Objects.requireNonNull(requiredOperations, "requiredOperations"));
        missingAuthorizations = values(missingAuthorizations, "missingAuthorizations");
        Objects.requireNonNull(rollbackPolicy, "rollbackPolicy");
        Objects.requireNonNull(expiresAt, "expiresAt");
        humanSummary = text(humanSummary, "humanSummary");
        if (requiredOperations.isEmpty() || requiredOperations.size() > 32
                || requiredOperations.stream().distinct().count() != requiredOperations.size()) {
            throw new IllegalArgumentException("requiredOperations are invalid");
        }
    }

    private static List<String> values(List<String> values, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.isEmpty() || copy.size() > 128 || copy.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 2_048)
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return copy.stream().sorted().toList();
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
