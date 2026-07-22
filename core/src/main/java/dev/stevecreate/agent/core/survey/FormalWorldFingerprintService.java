package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Full path/size/mtime manifest plus explicit hashes for configured key files. */
public final class FormalWorldFingerprintService {
    public FormalWorldFingerprint capture(
            FormalWorldReadOnlyGuard guard,
            String worldIdentity,
            FormalFingerprintPolicy policy) throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(policy, "policy");
        List<Path> files = guard.enumerateOrdinaryFiles(policy.maxFiles());
        List<FormalFileFingerprintEntry> entries = new ArrayList<>(files.size());
        Map<String, String> keyHashes = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Path file : files) {
            String relative = guard.relative(file);
            FormalFileMetadata metadata = guard.fingerprintMetadata(file);
            long size = metadata.sizeBytes();
            long lastModified = metadata.lastModifiedEpochMillis();
            String hash = FormalFileFingerprintEntry.NOT_HASHED;
            if (!metadata.privateContent()
                    && (policy.hashAllAllowedContent() || policy.keyRelativePaths().contains(relative))) {
                hash = hashFile(guard, file);
                keyHashes.put(relative, hash);
            }
            entries.add(new FormalFileFingerprintEntry(relative, size, lastModified, hash));
            totalBytes = Math.addExact(totalBytes, size);
        }
        entries.sort(Comparator.comparing(FormalFileFingerprintEntry::relativePath));
        String manifestHash = hashManifest(entries);
        MessageDigest world = digest();
        update(world, worldIdentity);
        update(world, Long.toString(entries.size()));
        update(world, Long.toString(totalBytes));
        update(world, manifestHash);
        keyHashes.forEach((path, hash) -> {
            update(world, path);
            update(world, hash);
        });
        return new FormalWorldFingerprint(worldIdentity, entries.size(), totalBytes, manifestHash,
                HexFormat.of().formatHex(world.digest()), keyHashes, entries);
    }

    private static String hashFile(FormalWorldReadOnlyGuard guard, Path file) throws IOException {
        return guard.read(file, FormalReadIntent.FINGERPRINT_CONTENT, channel -> {
            MessageDigest digest = digest();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1_024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            return HexFormat.of().formatHex(digest.digest());
        });
    }

    private static String hashManifest(List<FormalFileFingerprintEntry> entries) {
        MessageDigest digest = digest();
        for (FormalFileFingerprintEntry entry : entries) {
            update(digest, entry.relativePath());
            update(digest, Long.toString(entry.sizeBytes()));
            update(digest, Long.toString(entry.lastModifiedEpochMillis()));
            update(digest, entry.sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
