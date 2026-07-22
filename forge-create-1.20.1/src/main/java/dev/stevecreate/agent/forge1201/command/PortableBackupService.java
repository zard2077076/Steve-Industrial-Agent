package dev.stevecreate.agent.forge1201.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Portable offline backup request and independent evidence verification. */
final class PortableBackupService {
    static final String REQUEST_SCHEMA = "steve-industrial:portable-backup-request/v1";
    static final String BACKUP_SCHEMA = "steve-industrial:portable-backup/v1";
    private static final int MAX_FILES = 100_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PortableBackupService() {}

    static Prepared prepare(ServerLevel level) throws Exception {
        PublicAlphaRuntime.Success success = success(level);
        PublicAlphaRuntime.Authorization authorization = success.authorization();
        Files.createDirectories(authorization.backupRoot());
        Path backup = authorization.backupRoot().toRealPath();
        if (!backup.equals(authorization.backupRoot())) throw new IOException("BACKUP_ROOT_CHANGED");
        Path requests = backup.resolve("requests");
        Files.createDirectories(requests);
        if (!requests.toRealPath().startsWith(backup)) throw new IOException("BACKUP_REQUEST_ESCAPE");
        String requestId = UUID.randomUUID().toString();
        BackupRequest request = new BackupRequest(REQUEST_SCHEMA, requestId,
                authorization.worldIdentity(), authorization.markerGeneration(),
                authorization.gameDir().toString(), authorization.worldRoot().toString(),
                backup.toString(), authorization.importantRoots().stream().map(Path::toString).toList(),
                Instant.now().toString(), "PENDING_GAME_SHUTDOWN");
        Path output = requests.resolve(requestId + ".json");
        Files.writeString(output, GSON.toJson(request), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Prepared(requestId, output, backup);
    }

    static Verified verify(ServerLevel level) throws Exception {
        PublicAlphaRuntime.Authorization authorization = success(level).authorization();
        Path backupRoot = authorization.backupRoot().toRealPath();
        String worldKey = authorization.worldIdentity().substring("world:".length());
        Path worldDirectory = backupRoot.resolve("worlds").resolve(worldKey);
        Path pointerPath = worldDirectory.resolve("latest.json");
        if (!Files.isRegularFile(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("BACKUP_EVIDENCE_NOT_FOUND");
        }
        Latest latest = GSON.fromJson(Files.readString(pointerPath, StandardCharsets.UTF_8), Latest.class);
        if (latest == null || !BACKUP_SCHEMA.equals(latest.schema)
                || latest.relativeRecord == null
                || !latest.relativeRecord.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f-]{36}")) {
            throw new IOException("BACKUP_POINTER_INVALID");
        }
        Path record = worldDirectory.resolve(latest.relativeRecord).normalize().toRealPath();
        if (!record.startsWith(worldDirectory.toRealPath())) throw new IOException("BACKUP_POINTER_ESCAPE");
        Path completionPath = record.resolve("completed.json");
        Path markerPath = record.resolve("completed.marker");
        if (!Files.isRegularFile(completionPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("BACKUP_COMPLETION_MISSING");
        }
        Completion completion = GSON.fromJson(
                Files.readString(completionPath, StandardCharsets.UTF_8), Completion.class);
        if (completion == null || !BACKUP_SCHEMA.equals(completion.schema)
                || !completion.completed || !completion.backupVerified || !completion.restoreDrillPass
                || !completion.sourcePreFingerprint.equals(completion.sourcePostFingerprint)
                || !authorization.worldIdentity().equals(completion.worldIdentity)
                || !authorization.markerGeneration().equals(completion.markerGeneration)
                || completion.formalWorldTouched) {
            throw new IOException("BACKUP_COMPLETION_INVALID_OR_STALE");
        }
        List<String> lines = Files.readAllLines(record.resolve("manifest.tsv"), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"relativePath\tsizeBytes\tsha256".equals(lines.get(0))
                || lines.size() - 1 > MAX_FILES) throw new IOException("BACKUP_MANIFEST_INVALID");
        String normalizedManifest = String.join("\n", lines) + "\n";
        if (!sha256(normalizedManifest).equals(completion.manifestHash)) {
            throw new IOException("BACKUP_MANIFEST_HASH_MISMATCH");
        }
        List<ManifestEntry> entries = new ArrayList<>();
        long bytes = 0;
        Set<String> relativePaths = new HashSet<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\t", -1);
            if (fields.length != 3 || fields[0].isBlank() || fields[0].contains("..")
                    || fields[0].startsWith("/") || fields[0].contains("\\")) {
                throw new IOException("BACKUP_MANIFEST_ROW_INVALID");
            }
            if (!relativePaths.add(fields[0])) throw new IOException("BACKUP_MANIFEST_DUPLICATE_PATH");
            long size = Long.parseLong(fields[1]);
            if (size < 0 || !fields[2].matches("[0-9a-f]{64}")) throw new IOException("BACKUP_MANIFEST_VALUE_INVALID");
            verifyFile(record.resolve("backup"), fields[0], size, fields[2]);
            verifyFile(record.resolve("restore-drill"), fields[0], size, fields[2]);
            entries.add(new ManifestEntry(fields[0], size, fields[2]));
            bytes = Math.addExact(bytes, size);
        }
        if (entries.size() != completion.totalFiles || bytes != completion.totalBytes) {
            throw new IOException("BACKUP_TOTALS_MISMATCH");
        }
        String marker = Files.readString(markerPath, StandardCharsets.US_ASCII).trim();
        if (!marker.equals(BACKUP_SCHEMA + "\n" + completion.manifestHash)) {
            throw new IOException("BACKUP_COMPLETED_MARKER_INVALID");
        }
        return new Verified("backup-" + completion.manifestHash, record.resolve("backup"),
                record.resolve("restore-drill"), completion.manifestHash, entries, bytes,
                completion.sourcePreFingerprint, completion.sourcePostFingerprint);
    }

    static List<String> list(ServerLevel level) throws Exception {
        PublicAlphaRuntime.Authorization authorization = success(level).authorization();
        String worldKey = authorization.worldIdentity().substring("world:".length());
        Path root = authorization.backupRoot().resolve("worlds").resolve(worldKey);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(value -> value.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f-]{36}"))
                    .sorted(Comparator.reverseOrder()).limit(32).toList();
        }
    }

    private static PublicAlphaRuntime.Success success(ServerLevel level) throws IOException {
        PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(level, true);
        if (result instanceof PublicAlphaRuntime.Failure failure) throw new IOException(failure.code());
        return (PublicAlphaRuntime.Success) result;
    }

    private static void verifyFile(Path base, String relative, long size, String hash) throws Exception {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || Files.size(resolved) != size || !sha256(resolved).equals(hash)) {
            throw new IOException("BACKUP_FILE_MISMATCH:" + relative);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65_536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    record Prepared(String requestId, Path requestFile, Path backupRoot) {}
    record ManifestEntry(String relativePath, long sizeBytes, String sha256) {}
    record Verified(
            String backupIdentity, Path backupDirectory, Path restoreDirectory,
            String manifestHash, List<ManifestEntry> entries, long totalBytes,
            String sourcePreFingerprint, String sourcePostFingerprint) {}
    private record BackupRequest(
            String schema, String requestId, String worldIdentity, String markerGeneration,
            String testInstanceRoot, String worldRoot, String backupRoot,
            List<String> importantInstanceRoots, String createdAtUtc, String status) {}
    private static final class Latest {
        String schema;
        String relativeRecord;
    }
    private static final class Completion {
        String schema;
        String worldIdentity;
        String markerGeneration;
        String manifestHash;
        String sourcePreFingerprint;
        String sourcePostFingerprint;
        int totalFiles;
        long totalBytes;
        boolean completed;
        boolean backupVerified;
        boolean restoreDrillPass;
        boolean formalWorldTouched;
    }
}
