package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ApprovalScope(
        String worldIdentity,
        String candidatePackageIdentity,
        String candidateZoneIdentity,
        String userSelectedCandidateIdentity,
        String previewHash,
        ResourceId target,
        long quantity,
        String backupIdentity,
        String sourceFingerprint,
        String worldSnapshotFingerprint,
        String runtimeFingerprint,
        long maximumAffectedBlocks,
        String materialBudgetHash,
        String powerBudgetHash,
        List<RegionAuthorizedOperation> allowedOperations,
        Instant expiresAt,
        boolean oneTimeUse) {
    public ApprovalScope {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(candidatePackageIdentity, "candidatePackageIdentity");
        Objects.requireNonNull(candidateZoneIdentity, "candidateZoneIdentity");
        Objects.requireNonNull(userSelectedCandidateIdentity, "userSelectedCandidateIdentity");
        Objects.requireNonNull(previewHash, "previewHash");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        Objects.requireNonNull(worldSnapshotFingerprint, "worldSnapshotFingerprint");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(materialBudgetHash, "materialBudgetHash");
        Objects.requireNonNull(powerBudgetHash, "powerBudgetHash");
        allowedOperations = List.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations"));
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!worldIdentity.matches("world:[0-9a-f]{64}")
                || !candidatePackageIdentity.matches("formal-candidate:[0-9a-f]{64}")
                || !candidateZoneIdentity.matches("zone:[0-9a-f]{64}")
                || !userSelectedCandidateIdentity.equals(candidatePackageIdentity)
                || !previewHash.matches("[0-9a-f]{64}")
                || quantity < 1 || !backupIdentity.matches("formal-backup:[0-9a-f]{64}")
                || !sourceFingerprint.matches("[0-9a-f]{64}")
                || !materialBudgetHash.matches("[0-9a-f]{64}")
                || !powerBudgetHash.matches("[0-9a-f]{64}")
                || maximumAffectedBlocks < 0 || allowedOperations.isEmpty()
                || allowedOperations.stream().distinct().count() != allowedOperations.size()
                || !oneTimeUse) {
            throw new IllegalArgumentException("approval scope is incomplete or non-exact");
        }
    }
}
