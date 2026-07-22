package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalWorldFingerprint;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record FormalBackupCopyResult(
        String backupIdentity,
        FormalBackupDestinationPlan destinationPlan,
        FormalWorldBackupManifest manifest,
        FormalWorldFingerprint sourcePreFingerprint,
        FormalWorldFingerprint sourcePostFingerprint,
        String opaqueSourcePreFingerprint,
        String opaqueSourcePostFingerprint,
        Path completedTarget,
        Path completedMarker,
        Instant completedAt,
        boolean manifestVerified,
        boolean atomicPublish,
        boolean sourceUnchanged,
        boolean privateContentParsed,
        boolean sourceAttributesModified,
        boolean formalWorldWrite,
        boolean externalMutation) {
    public FormalBackupCopyResult {
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        if (!backupIdentity.matches("formal-backup:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backupIdentity is invalid");
        }
        Objects.requireNonNull(destinationPlan, "destinationPlan");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sourcePreFingerprint, "sourcePreFingerprint");
        Objects.requireNonNull(sourcePostFingerprint, "sourcePostFingerprint");
        if (!opaqueSourcePreFingerprint.matches("[0-9a-f]{64}")
                || !opaqueSourcePostFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("opaque source fingerprint is invalid");
        }
        Objects.requireNonNull(completedTarget, "completedTarget");
        Objects.requireNonNull(completedMarker, "completedMarker");
        Objects.requireNonNull(completedAt, "completedAt");
        if (!manifestVerified || !atomicPublish || !sourceUnchanged || privateContentParsed
                || sourceAttributesModified || formalWorldWrite || externalMutation) {
            throw new IllegalArgumentException("formal backup result violates its completion contract");
        }
    }
}
