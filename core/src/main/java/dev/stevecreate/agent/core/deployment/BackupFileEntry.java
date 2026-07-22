package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.Objects;

public record BackupFileEntry(
        String relativePath,
        long sizeBytes,
        String sha256) {
    public BackupFileEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        relativePath = relativePath.replace('\\', '/');
        if (relativePath.isBlank() || relativePath.length() > 4_096
                || relativePath.startsWith("/") || Path.of(relativePath).isAbsolute()
                || relativePath.equals("..") || relativePath.startsWith("../")
                || relativePath.contains("/../")) {
            throw new IllegalArgumentException("relativePath is unsafe");
        }
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
        sha256 = BackupManifest.sha256(sha256, "sha256");
    }
}
