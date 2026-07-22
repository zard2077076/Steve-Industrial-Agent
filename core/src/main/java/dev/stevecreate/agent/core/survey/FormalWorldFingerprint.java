package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FormalWorldFingerprint(
        String worldIdentity,
        long fileCount,
        long totalBytes,
        String manifestSha256,
        String worldFingerprint,
        Map<String, String> keyFileHashes,
        List<FormalFileFingerprintEntry> manifest) {
    public FormalWorldFingerprint {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (worldIdentity.isBlank()) throw new IllegalArgumentException("worldIdentity is blank");
        if (fileCount < 0 || totalBytes < 0) throw new IllegalArgumentException("counts are negative");
        manifestSha256 = hash(manifestSha256, "manifestSha256");
        worldFingerprint = hash(worldFingerprint, "worldFingerprint");
        keyFileHashes = Map.copyOf(Objects.requireNonNull(keyFileHashes, "keyFileHashes"));
        if (keyFileHashes.values().stream().anyMatch(value -> value == null || !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("keyFileHashes contain an invalid hash");
        }
        manifest = List.copyOf(Objects.requireNonNull(manifest, "manifest"));
        if (fileCount != manifest.size()) throw new IllegalArgumentException("fileCount does not match manifest");
        long calculatedBytes = manifest.stream().mapToLong(FormalFileFingerprintEntry::sizeBytes).sum();
        if (calculatedBytes != totalBytes) throw new IllegalArgumentException("totalBytes does not match manifest");
    }

    public boolean exactlyMatches(FormalWorldFingerprint other) {
        return equals(other);
    }

    private static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
