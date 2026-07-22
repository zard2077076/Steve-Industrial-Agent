package dev.stevecreate.agent.core.deployment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record BackupManifest(
        List<BackupFileEntry> entries,
        long totalBytes,
        String manifestHash,
        BackupManifestState state) {
    public BackupManifest {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty() || entries.size() > 1_000_000) {
            throw new IllegalArgumentException("manifest entry count is outside its bound");
        }
        String previous = null;
        long calculated = 0;
        for (BackupFileEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (previous != null && previous.compareTo(entry.relativePath()) >= 0) {
                throw new IllegalArgumentException("manifest entries are duplicate or unordered");
            }
            previous = entry.relativePath();
            calculated = Math.addExact(calculated, entry.sizeBytes());
        }
        if (totalBytes < 0 || totalBytes != calculated) {
            throw new IllegalArgumentException("totalBytes disagrees with entries");
        }
        manifestHash = sha256(manifestHash, "manifestHash");
        Objects.requireNonNull(state, "state");
    }

    public static BackupManifest complete(List<BackupFileEntry> entries) {
        List<BackupFileEntry> copy = List.copyOf(entries);
        long total = 0;
        for (BackupFileEntry entry : copy) total = Math.addExact(total, entry.sizeBytes());
        return new BackupManifest(copy, total, calculateHash(copy), BackupManifestState.COMPLETE);
    }

    public boolean hasValidHash() {
        return manifestHash.equals(calculateHash(entries));
    }

    static String calculateHash(List<BackupFileEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (BackupFileEntry entry : entries) {
                String line = entry.relativePath() + "\u0000" + entry.sizeBytes()
                        + "\u0000" + entry.sha256() + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String sha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
        }
        return value;
    }
}
