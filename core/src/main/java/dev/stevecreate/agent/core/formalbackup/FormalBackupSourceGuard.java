package dev.stevecreate.agent.core.formalbackup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Dedicated formal-backup transport boundary. Source channels are READ-only and private bytes
 * can only flow directly to a verified staging file and a SHA-256 digest.
 */
final class FormalBackupSourceGuard {
    private static final Set<String> PRIVATE_SEGMENTS = Set.of("playerdata", "stats", "advancements");
    private static final Set<String> TEMP_SUFFIXES = Set.of(".tmp", ".temp", ".part", ".crdownload");
    private final FormalBackupDestinationPlan plan;
    private final Path sourceRoot;
    private final Path backupRoot;

    FormalBackupSourceGuard(FormalBackupDestinationPlan plan) throws IOException {
        this.plan = Objects.requireNonNull(plan, "plan");
        sourceRoot = ordinaryRealDirectory(plan.canonicalSourceRoot());
        backupRoot = ordinaryRealDirectory(plan.canonicalBackupRoot());
        if (!sourceRoot.equals(plan.canonicalSourceRoot().toRealPath(LinkOption.NOFOLLOW_LINKS))
                || !backupRoot.equals(plan.canonicalBackupRoot().toRealPath(LinkOption.NOFOLLOW_LINKS))
                || plan.plannedTarget().startsWith(sourceRoot)
                || plan.plannedStaging().startsWith(sourceRoot)
                || !plan.plannedTarget().startsWith(backupRoot)
                || !plan.plannedStaging().startsWith(backupRoot)
                || plan.plannedTarget().equals(plan.plannedStaging())) {
            throw new IOException("formal backup plan roots are inconsistent");
        }
        ensureNoReparseOnExistingAncestors(plan.plannedTarget());
        ensureNoReparseOnExistingAncestors(plan.plannedStaging());
    }

    FormalBackupSourceSnapshot snapshot(int maxFiles, long maxBytes) throws IOException {
        if (maxFiles < 1 || maxFiles > 1_000_000 || maxBytes < 1) {
            throw new IllegalArgumentException("source bounds are invalid");
        }
        List<Path> observed;
        try (var stream = Files.walk(sourceRoot)) {
            observed = stream.filter(path -> !path.equals(sourceRoot))
                    .sorted(Comparator.comparing(this::relative))
                    .limit((long) maxFiles + 1)
                    .toList();
        }
        if (observed.size() > maxFiles) throw new IOException("formal backup file limit exceeded");

        List<FormalBackupFileEntry> entries = new ArrayList<>();
        List<FormalBackupExcludedEntry> exclusions = new ArrayList<>();
        long chargedBytes = 0;
        for (Path path : observed) {
            ensureOrdinarySourceEntry(path);
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) continue;
            if (!attributes.isRegularFile()) throw new IOException("formal backup source entry is not ordinary");
            String relative = relative(path);
            if (relative.equals(FormalBackupService.COMPLETED_MARKER)
                    || relative.equals(FormalBackupService.MANIFEST_FILE)) {
                throw new IOException("formal source contains a reserved backup-control path");
            }
            FormalBackupExclusionReason exclusion = exclusion(relative);
            if (exclusion != null) {
                exclusions.add(new FormalBackupExcludedEntry(
                        relative, attributes.size(), attributes.lastModifiedTime().toMillis(), exclusion));
                continue;
            }
            chargedBytes = Math.addExact(chargedBytes, attributes.size());
            if (chargedBytes > maxBytes) throw new IOException("formal backup byte limit exceeded");
            String hash = hashReadOnly(path);
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.size() != after.size()
                    || !attributes.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("formal backup source changed while hashing");
            }
            entries.add(new FormalBackupFileEntry(relative, attributes.size(),
                    attributes.lastModifiedTime().toMillis(), hash, privacy(relative)));
        }
        FormalWorldBackupManifest manifest = FormalWorldBackupManifest.complete(entries, exclusions);
        MessageDigest identity = FormalWorldBackupManifest.digest();
        FormalWorldBackupManifest.update(identity, plan.worldIdentity());
        FormalWorldBackupManifest.update(identity, manifest.manifestHash());
        return new FormalBackupSourceSnapshot(manifest, HexFormat.of().formatHex(identity.digest()));
    }

    FormalBackupFileEntry copyOpaqueToStaging(FormalBackupFileEntry expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Path source = safeResolve(sourceRoot, expected.relativePath());
        ensureOrdinarySourceEntry(source);
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() != expected.sizeBytes()
                || before.lastModifiedTime().toMillis() != expected.lastModifiedEpochMillis()
                || privacy(expected.relativePath()) != expected.privacy()
                || exclusion(expected.relativePath()) != null) {
            throw new IOException("source entry no longer matches the pre-copy manifest");
        }
        Path target = safeStagingTarget(expected.relativePath());
        prepareOrdinaryStagingParent(target.getParent());
        MessageDigest digest = FormalWorldBackupManifest.digest();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                FileChannel output = FileChannel.open(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1_024);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
            output.force(true);
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(expected.lastModifiedEpochMillis()));
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String copiedHash = HexFormat.of().formatHex(digest.digest());
        if (after.size() != expected.sizeBytes()
                || after.lastModifiedTime().toMillis() != expected.lastModifiedEpochMillis()
                || !copiedHash.equals(expected.sha256())) {
            throw new IOException("source entry changed during opaque copy");
        }
        return expected;
    }

    private void prepareOrdinaryStagingParent(Path parent) throws IOException {
        Path staging = plan.plannedStaging().toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalizedParent.startsWith(staging)) throw new IOException("staging parent escaped root");
        Path cursor = staging;
        for (Path segment : staging.relativize(normalizedParent)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (reparseLike(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("staging parent is reparse-like or non-directory");
                }
            } else {
                Files.createDirectory(cursor);
                if (reparseLike(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("created staging parent is unsafe");
                }
            }
        }
    }

    private Path safeStagingTarget(String relative) throws IOException {
        Path staging = plan.plannedStaging().toAbsolutePath().normalize();
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(staging)) {
            throw new IOException("staging root is not an ordinary directory");
        }
        Path target = safeResolve(staging, relative);
        if (!target.startsWith(backupRoot) || target.startsWith(sourceRoot)) {
            throw new IOException("staging target escaped its safe root");
        }
        return target;
    }

    private String hashReadOnly(Path source) throws IOException {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1_024);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void ensureOrdinarySourceEntry(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(sourceRoot)) throw new IOException("source entry escaped root");
        ensureNoReparseOnExistingAncestors(normalized);
    }

    private String relative(Path path) {
        return sourceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static FormalBackupFilePrivacy privacy(String relative) {
        Path path = Path.of(relative.replace('/', java.io.File.separatorChar));
        for (Path segment : path) {
            if (PRIVATE_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return FormalBackupFilePrivacy.OPAQUE_PRIVATE;
            }
        }
        return FormalBackupFilePrivacy.ORDINARY;
    }

    private static FormalBackupExclusionReason exclusion(String relative) {
        String name = Path.of(relative.replace('/', java.io.File.separatorChar))
                .getFileName().toString().toLowerCase(Locale.ROOT);
        if (relative.equalsIgnoreCase("session.lock")) return FormalBackupExclusionReason.ACTIVE_SESSION_LOCK;
        for (String suffix : TEMP_SUFFIXES) {
            if (name.endsWith(suffix)) return FormalBackupExclusionReason.INVALID_TEMPORARY_FILE;
        }
        return null;
    }

    static FormalWorldBackupManifest manifestAtTarget(
            Path targetRoot,
            FormalWorldBackupManifest expected,
            boolean manifestAllowed,
            boolean completedMarkerAllowed) throws IOException {
        Path root = ordinaryRealDirectory(targetRoot);
        List<FormalBackupFileEntry> entries = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        java.util.Map<String, FormalBackupFileEntry> expectedByPath = expected.entries().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        FormalBackupFileEntry::relativePath, entry -> entry));
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(candidate -> !candidate.equals(root))
                    .sorted(Comparator.comparing(candidate ->
                            root.relativize(candidate).toString().replace('\\', '/'))).toList()) {
                ensureNoReparseOnExistingAncestors(path);
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) throw new IOException("backup contains a non-ordinary entry");
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (completedMarkerAllowed && relative.equals(FormalBackupService.COMPLETED_MARKER)) continue;
                if (manifestAllowed && relative.equals(FormalBackupService.MANIFEST_FILE)) continue;
                seen.add(relative);
                FormalBackupFileEntry source = expectedByPath.get(relative);
                if (source == null) throw new IOException("backup contains an unexpected file");
                entries.add(new FormalBackupFileEntry(relative, attributes.size(),
                        attributes.lastModifiedTime().toMillis(), hashOrdinary(path), source.privacy()));
            }
        }
        if (!seen.equals(expected.entries().stream().map(FormalBackupFileEntry::relativePath).toList())) {
            throw new IOException("backup is missing an expected file");
        }
        return FormalWorldBackupManifest.complete(entries, expected.exclusions());
    }

    private static String hashOrdinary(Path path) throws IOException {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1_024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        FormalBackupFileEntry.relative(relative);
        Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) throw new IOException("relative path escaped root");
        return resolved;
    }

    private static Path ordinaryRealDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparseOnExistingAncestors(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("path is not an ordinary directory");
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void ensureNoReparseOnExistingAncestors(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null) throw new IOException("path has no root");
        Path cursor = root;
        if (reparseLike(cursor)) throw new IOException("filesystem root is reparse-like");
        for (Path segment : normalized) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (reparseLike(cursor)) throw new IOException("path ancestor is reparse-like");
        }
    }

    private static boolean reparseLike(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isOther();
    }
}
