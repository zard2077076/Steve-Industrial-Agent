package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RestoreDrillResult(
        String restoreDrillIdentity,
        FormalWorldBackupIdentity backupIdentity,
        Path disposableRestoreTarget,
        FormalWorldBackupManifest restoredManifest,
        FormalWorldIdentity restoredWorldIdentity,
        List<FormalRestoreDrillCheck> checks,
        Instant completedAt,
        boolean passed,
        boolean minecraftStarted,
        boolean formalSourceRead,
        boolean formalSourceWrite,
        boolean privateContentParsed,
        boolean disposableCopyRetained) {
    public RestoreDrillResult {
        Objects.requireNonNull(restoreDrillIdentity, "restoreDrillIdentity");
        if (!restoreDrillIdentity.matches("restore-drill:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("restoreDrillIdentity is invalid");
        }
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        Objects.requireNonNull(disposableRestoreTarget, "disposableRestoreTarget");
        Objects.requireNonNull(restoredManifest, "restoredManifest");
        Objects.requireNonNull(restoredWorldIdentity, "restoredWorldIdentity");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (!checks.equals(List.of(FormalRestoreDrillCheck.values()))) {
            throw new IllegalArgumentException("restore drill checklist is incomplete or unordered");
        }
        Objects.requireNonNull(completedAt, "completedAt");
        if (!passed || minecraftStarted || formalSourceRead || formalSourceWrite || privateContentParsed
                || !disposableCopyRetained
                || !restoredManifest.manifestHash().equals(backupIdentity.manifestHash())
                || !restoredWorldIdentity.equals(backupIdentity.worldIdentity())) {
            throw new IllegalArgumentException("restore drill completion evidence is inconsistent");
        }
    }
}
