package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class BackupManifestBuilder {
    public BackupManifest buildIsolated(Path sourceRoot, BackupPathPolicy policy) throws IOException {
        if (!policy.allowsSource(sourceRoot)) {
            throw new IOException("manifest source is outside isolated policy");
        }
        return buildDirectory(sourceRoot.toRealPath());
    }

    public BackupManifest buildBackupTarget(Path targetRoot, BackupPathPolicy policy) throws IOException {
        if (!policy.allowsTarget(targetRoot) || !Files.isDirectory(targetRoot)) {
            throw new IOException("manifest target is outside backup policy or unavailable");
        }
        return buildDirectory(targetRoot.toRealPath());
    }

    static BackupManifest buildDirectory(Path root) throws IOException {
        Path realRoot = root.toRealPath();
        List<Path> paths;
        try (var stream = Files.walk(realRoot)) {
            paths = stream.sorted(Comparator.comparing(path ->
                            realRoot.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        List<BackupFileEntry> entries = new ArrayList<>();
        for (Path path : paths) {
            if (path.equals(realRoot)) continue;
            if (Files.isSymbolicLink(path)) {
                throw new IOException("backup manifest refuses symbolic links");
            }
            if (Files.isRegularFile(path)) {
                entries.add(new BackupFileEntry(
                        realRoot.relativize(path).toString().replace('\\', '/'),
                        Files.size(path), fileHash(path)));
            }
        }
        return BackupManifest.complete(entries);
    }

    private static String fileHash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                    DigestInputStream hashed = new DigestInputStream(input, digest)) {
                hashed.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
