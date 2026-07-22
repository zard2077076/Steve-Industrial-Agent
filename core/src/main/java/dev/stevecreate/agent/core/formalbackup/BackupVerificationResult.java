package dev.stevecreate.agent.core.formalbackup;

import java.util.List;
import java.util.Objects;

public record BackupVerificationResult(
        FormalWorldBackupIdentity identity,
        FormalWorldBackupManifest manifest,
        List<FormalBackupVerificationCheck> checks,
        String completionMarkerSha256,
        boolean verified,
        boolean privateContentParsed,
        boolean formalSourceRead,
        boolean formalSourceWrite) {
    public BackupVerificationResult {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(manifest, "manifest");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (!checks.equals(List.of(FormalBackupVerificationCheck.values()))) {
            throw new IllegalArgumentException("backup verification checklist is incomplete or unordered");
        }
        Objects.requireNonNull(completionMarkerSha256, "completionMarkerSha256");
        if (!completionMarkerSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("completionMarkerSha256 is invalid");
        }
        if (!verified || privateContentParsed || formalSourceRead || formalSourceWrite
                || !identity.manifestHash().equals(manifest.manifestHash())
                || identity.fileCount() != manifest.entries().size()
                || identity.totalBytes() != manifest.totalBytes()) {
            throw new IllegalArgumentException("verified backup evidence is inconsistent");
        }
    }
}
