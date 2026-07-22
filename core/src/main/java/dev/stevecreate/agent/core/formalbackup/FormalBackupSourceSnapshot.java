package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;

/** An opaque source snapshot. Private bytes are hashed but never returned. */
public record FormalBackupSourceSnapshot(FormalWorldBackupManifest manifest, String snapshotFingerprint) {
    public FormalBackupSourceSnapshot {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
        if (!snapshotFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotFingerprint is invalid");
        }
    }
}
