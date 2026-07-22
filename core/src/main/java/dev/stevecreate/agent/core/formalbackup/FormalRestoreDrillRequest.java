package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.NbtReadLimits;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record FormalRestoreDrillRequest(
        FormalBackupVerificationRequest verificationRequest,
        BackupVerificationResult verifiedBackup,
        Path restoreRoot,
        Instant requestedAt,
        NbtReadLimits nbtReadLimits) {
    public FormalRestoreDrillRequest {
        Objects.requireNonNull(verificationRequest, "verificationRequest");
        Objects.requireNonNull(verifiedBackup, "verifiedBackup");
        Objects.requireNonNull(restoreRoot, "restoreRoot");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(nbtReadLimits, "nbtReadLimits");
    }
}
