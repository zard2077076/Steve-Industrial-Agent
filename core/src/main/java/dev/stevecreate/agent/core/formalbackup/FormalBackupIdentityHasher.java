package dev.stevecreate.agent.core.formalbackup;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

final class FormalBackupIdentityHasher {
    private FormalBackupIdentityHasher() {}

    static String hash(
            String planIdentity,
            String worldIdentity,
            String sourceFingerprint,
            String runtimeFingerprint,
            String manifestHash,
            Instant completedAt,
            String policyVersion,
            String toolVersion,
            String gitHead) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        FormalWorldBackupManifest.update(digest, planIdentity);
        FormalWorldBackupManifest.update(digest, worldIdentity);
        FormalWorldBackupManifest.update(digest, sourceFingerprint);
        FormalWorldBackupManifest.update(digest, runtimeFingerprint);
        FormalWorldBackupManifest.update(digest, manifestHash);
        FormalWorldBackupManifest.update(digest, completedAt.toString());
        FormalWorldBackupManifest.update(digest, policyVersion);
        FormalWorldBackupManifest.update(digest, toolVersion);
        FormalWorldBackupManifest.update(digest, gitHead);
        return "formal-backup:" + HexFormat.of().formatHex(digest.digest());
    }
}
