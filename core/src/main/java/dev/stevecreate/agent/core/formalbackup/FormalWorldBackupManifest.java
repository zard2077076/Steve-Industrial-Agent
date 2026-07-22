package dev.stevecreate.agent.core.formalbackup;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical complete-file manifest; excluded locks/temp files remain explicit metadata evidence. */
public record FormalWorldBackupManifest(
        List<FormalBackupFileEntry> entries,
        List<FormalBackupExcludedEntry> exclusions,
        long totalBytes,
        String manifestHash,
        boolean complete) {
    public FormalWorldBackupManifest {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        exclusions = List.copyOf(Objects.requireNonNull(exclusions, "exclusions"));
        if (entries.isEmpty() || entries.size() > 1_000_000 || exclusions.size() > 1_000_000) {
            throw new IllegalArgumentException("manifest entry count is outside its bound");
        }
        validateOrder(entries.stream().map(FormalBackupFileEntry::relativePath).toList(), "entries");
        validateOrder(exclusions.stream().map(FormalBackupExcludedEntry::relativePath).toList(), "exclusions");
        java.util.Set<String> copiedPaths = entries.stream()
                .map(FormalBackupFileEntry::relativePath).collect(java.util.stream.Collectors.toSet());
        if (exclusions.stream().map(FormalBackupExcludedEntry::relativePath).anyMatch(copiedPaths::contains)) {
            throw new IllegalArgumentException("a path cannot be both copied and excluded");
        }
        long calculated = 0;
        for (FormalBackupFileEntry entry : entries) calculated = Math.addExact(calculated, entry.sizeBytes());
        if (totalBytes != calculated || totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes disagrees with entries");
        }
        Objects.requireNonNull(manifestHash, "manifestHash");
        if (!manifestHash.matches("[0-9a-f]{64}") || !manifestHash.equals(calculateHash(entries, exclusions))) {
            throw new IllegalArgumentException("manifestHash is invalid");
        }
        if (!complete) throw new IllegalArgumentException("an incomplete manifest cannot be constructed as valid");
    }

    public static FormalWorldBackupManifest complete(
            List<FormalBackupFileEntry> entries,
            List<FormalBackupExcludedEntry> exclusions) {
        List<FormalBackupFileEntry> copied = entries.stream()
                .sorted(java.util.Comparator.comparing(FormalBackupFileEntry::relativePath)).toList();
        List<FormalBackupExcludedEntry> excluded = exclusions.stream()
                .sorted(java.util.Comparator.comparing(FormalBackupExcludedEntry::relativePath)).toList();
        long bytes = 0;
        for (FormalBackupFileEntry entry : copied) bytes = Math.addExact(bytes, entry.sizeBytes());
        return new FormalWorldBackupManifest(copied, excluded, bytes, calculateHash(copied, excluded), true);
    }

    private static void validateOrder(List<String> paths, String name) {
        String previous = null;
        for (String path : paths) {
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new IllegalArgumentException(name + " are duplicate or unordered");
            }
            previous = path;
        }
    }

    private static String calculateHash(
            List<FormalBackupFileEntry> entries,
            List<FormalBackupExcludedEntry> exclusions) {
        MessageDigest digest = digest();
        for (FormalBackupFileEntry entry : entries) {
            update(digest, "FILE");
            update(digest, entry.relativePath());
            update(digest, Long.toString(entry.sizeBytes()));
            update(digest, Long.toString(entry.lastModifiedEpochMillis()));
            update(digest, entry.sha256());
            update(digest, entry.privacy().name());
        }
        for (FormalBackupExcludedEntry entry : exclusions) {
            update(digest, "EXCLUDED");
            update(digest, entry.relativePath());
            update(digest, Long.toString(entry.sizeBytes()));
            update(digest, Long.toString(entry.lastModifiedEpochMillis()));
            update(digest, entry.reason().name());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
