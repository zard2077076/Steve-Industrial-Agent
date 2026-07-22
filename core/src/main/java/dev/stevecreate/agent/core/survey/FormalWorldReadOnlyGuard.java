package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The only formal-file opening boundary. It opens channels with StandardOpenOption.READ only. */
public final class FormalWorldReadOnlyGuard {
    private static final String NOT_APPLICABLE = "not-applicable";
    private static final Set<String> PRIVATE_PATH_SEGMENTS = Set.of("playerdata", "stats", "advancements");
    private static final Set<OpenOption> READ_ONLY_OPTIONS = Set.of(StandardOpenOption.READ);
    private final FormalReadOnlyPolicy policy;
    private final Path formalInstanceRoot;
    private final Path approvedSaveRoot;
    private final Path auditRoot;
    private final Path auditFile;

    public FormalWorldReadOnlyGuard(FormalReadOnlyPolicy policy) throws FormalReadAccessException, IOException {
        this.policy = Objects.requireNonNull(policy, "policy");
        try {
            formalInstanceRoot = ordinaryRealDirectory(policy.formalInstanceRoot());
            approvedSaveRoot = ordinaryRealDirectory(policy.approvedSaveRoot());
        } catch (NoSuchFileException exception) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_SAVE_ROOT_NOT_FOUND,
                    policy.approvedSaveRoot(),
                    "The formal instance or approved save root is missing",
                    "Confirm the exact formal instance without starting Minecraft",
                    List.of(exception.getClass().getSimpleName())));
        } catch (IOException | SecurityException exception) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    policy.approvedSaveRoot(),
                    "The formal instance or approved save root is missing, unresolved or reparse-like",
                    "Resolve an ordinary exact formal root before any read",
                    List.of(exception.getClass().getSimpleName())));
        }
        if (!approvedSaveRoot.startsWith(formalInstanceRoot)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                    approvedSaveRoot,
                    "The approved save root is outside the formal instance",
                    "Approve an exact save root inside the formal instance",
                    List.of("formalInstanceRoot=" + identity(formalInstanceRoot))));
        }
        Path normalizedAudit = policy.auditRoot().toAbsolutePath().normalize();
        if (normalizedAudit.startsWith(formalInstanceRoot) || normalizedAudit.startsWith(approvedSaveRoot)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT,
                    normalizedAudit,
                    "The audit destination is inside the formal instance",
                    "Use the repository ignored work/formal-survey audit root",
                    List.of("auditRoot=" + identity(normalizedAudit))));
        }
        verifyExistingAuditAncestors(normalizedAudit);
        Files.createDirectories(normalizedAudit);
        auditRoot = ordinaryRealDirectory(normalizedAudit);
        if (auditRoot.startsWith(formalInstanceRoot)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT,
                    auditRoot,
                    "The real audit destination resolves inside the formal instance",
                    "Use an ordinary repository-owned audit root",
                    List.of("auditRoot=" + identity(auditRoot))));
        }
        auditFile = auditRoot.resolve("formal-read-audit.jsonl");
    }

    public String approvedSaveRootIdentity() {
        return identity(approvedSaveRoot);
    }

    public String formalInstanceRootIdentity() {
        return identity(formalInstanceRoot);
    }

    public String auditFileIdentity() {
        return identity(auditFile);
    }

    public String worldIdentity() {
        return policy.worldIdentity();
    }

    public FormalReadOnlyDecision authorize(FormalFileOperation operation, Path path, FormalReadIntent intent) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(intent, "intent");
        if (operation != FormalFileOperation.READ) {
            FormalSurveyFailureCode code = switch (operation) {
                case LOCK -> FormalSurveyFailureCode.FORMAL_WORLD_LOCK_ATTEMPT;
                case PROCESS_START -> FormalSurveyFailureCode.FORMAL_WORLD_PROCESS_START_FORBIDDEN;
                default -> FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT;
            };
            return FormalReadOnlyDecision.refuse(failure(code, path,
                    "Formal survey policy forbids " + operation,
                    "Use the read-only survey or an ignored repository output path",
                    List.of("operation=" + operation, "intent=" + intent)));
        }
        try {
            Path real = ordinaryRealFile(path);
            if (!real.startsWith(approvedSaveRoot)) {
                return FormalReadOnlyDecision.refuse(failure(
                        FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                        real,
                        "The requested file is outside the exact approved save root",
                        "Use a discovered relative path inside the selected save",
                        List.of("approvedSaveRoot=" + identity(approvedSaveRoot))));
            }
            if (containsPrivateSegment(approvedSaveRoot.relativize(real))) {
                return FormalReadOnlyDecision.refuse(failure(
                        FormalSurveyFailureCode.FORMAL_INVENTORY_CONTENTS_FORBIDDEN,
                        real,
                        "Player-linked private data is outside the authorized survey boundary",
                        "Continue with level, region and non-content infrastructure metadata only",
                        List.of("relativePath=" + relative(real))));
            }
            for (Path cursor = approvedSaveRoot; cursor != null && real.startsWith(cursor); ) {
                if (reparseLike(cursor)) {
                    return FormalReadOnlyDecision.refuse(failure(
                            FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                            cursor,
                            "A path component is symbolic, junction or reparse-like",
                            "Reject the path and use an ordinary exact save tree",
                            List.of("relativePath=" + relative(real))));
                }
                if (cursor.equals(real)) break;
                Path remainder = cursor.relativize(real);
                cursor = cursor.resolve(remainder.getName(0));
            }
            return FormalReadOnlyDecision.allow();
        } catch (IOException | SecurityException exception) {
            return FormalReadOnlyDecision.refuse(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    path,
                    "The requested path could not be resolved as an ordinary regular file",
                    "Reject the path and review its filesystem identity",
                    List.of(exception.getClass().getSimpleName())));
        }
    }

    public <T> T read(Path path, FormalReadIntent intent, GuardedFormalRead<T> action) throws IOException {
        Objects.requireNonNull(action, "action");
        FormalReadOnlyDecision decision = authorize(FormalFileOperation.READ, path, intent);
        if (!decision.allowed()) throw new FormalReadAccessException(decision.typedFailure().orElseThrow());
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        audit("READ_OPEN", intent, real);
        try (FileChannel channel = FileChannel.open(real, READ_ONLY_OPTIONS)) {
            T result = action.read(channel);
            audit("READ_COMPLETE", intent, real);
            return result;
        }
    }

    /** Bounded direct-child enumeration used before one formal world has been selected. */
    List<Path> enumerateOrdinaryDirectDirectories(int maxEntries) throws IOException {
        if (maxEntries <= 0 || maxEntries > 256) {
            throw new IllegalArgumentException("maxEntries must be between 1 and 256");
        }
        audit("ENUMERATE_DIRECT_OPEN", FormalReadIntent.SAVE_ENUMERATION, approvedSaveRoot);
        List<Path> directories = new ArrayList<>();
        int observed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(approvedSaveRoot)) {
            for (Path entry : stream) {
                observed++;
                if (observed > maxEntries) {
                    throw new FormalReadAccessException(failure(
                            FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                            approvedSaveRoot,
                            "The saves root exceeds the bounded direct-entry discovery limit",
                            "Narrow the approved instance or select a world explicitly",
                            List.of("entryLimit=" + maxEntries)));
                }
                if (reparseLike(entry)) {
                    throw new FormalReadAccessException(failure(
                            FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                            entry,
                            "A direct save entry is symbolic, junction or reparse-like",
                            "Reject the entry or approve an ordinary exact save directory",
                            List.of("entry=" + entry.getFileName())));
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                audit("METADATA", FormalReadIntent.SAVE_ENUMERATION, entry);
                if (attributes.isDirectory()) directories.add(entry.toRealPath(LinkOption.NOFOLLOW_LINKS));
            }
        }
        directories.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        audit("ENUMERATE_DIRECT_COMPLETE", FormalReadIntent.SAVE_ENUMERATION, approvedSaveRoot);
        return List.copyOf(directories);
    }

    /** Resolves one known metadata filename beneath one direct ordinary save directory. */
    Optional<Path> findOrdinaryDirectFile(Path directory, String fileName, FormalReadIntent intent)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(intent, "intent");
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("fileName is not one direct name");
        }
        Path realDirectory;
        try {
            realDirectory = ordinaryRealDirectory(directory);
        } catch (IOException | SecurityException exception) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    directory,
                    "The candidate save directory is missing, nested or reparse-like",
                    "Reject the candidate and review the exact direct save path",
                    List.of(exception.getClass().getSimpleName())));
        }
        if (!approvedSaveRoot.equals(realDirectory.getParent())) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                    realDirectory,
                    "The candidate is not a direct child of the approved saves root",
                    "Use only identities returned by guarded direct discovery",
                    List.of("approvedSaveRoot=" + identity(approvedSaveRoot))));
        }
        Path file = realDirectory.resolve(fileName);
        audit("METADATA_LOOKUP", intent, file);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        if (reparseLike(file)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    file,
                    "The candidate metadata file is reparse-like",
                    "Reject the candidate metadata path",
                    List.of("fileName=" + fileName)));
        }
        if (!attributes.isRegularFile()) return Optional.empty();
        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.getParent().equals(realDirectory)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                    real,
                    "The candidate metadata file escaped its direct save directory",
                    "Reject the candidate metadata path",
                    List.of("save=" + identity(realDirectory))));
        }
        return Optional.of(real);
    }

    List<Path> enumerateOrdinaryFiles(int maxFiles) throws IOException {
        if (maxFiles <= 0) throw new IllegalArgumentException("maxFiles must be positive");
        audit("ENUMERATE_OPEN", FormalReadIntent.FINGERPRINT_METADATA, approvedSaveRoot);
        List<Path> paths;
        try (var stream = Files.walk(approvedSaveRoot)) {
            paths = stream.filter(path -> !path.equals(approvedSaveRoot))
                    .sorted((left, right) -> relative(left).compareTo(relative(right)))
                    .limit((long) maxFiles + 1)
                    .toList();
        }
        if (paths.size() > maxFiles) throw new IOException("formal fingerprint file limit exceeded");
        for (Path path : paths) {
            if (reparseLike(path)) {
                throw new FormalReadAccessException(failure(
                        FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                        path,
                        "The formal tree contains a reparse-like entry",
                        "Stop the survey and review the exact path",
                        List.of("relativePath=" + relative(path))));
            }
            audit("METADATA", FormalReadIntent.FINGERPRINT_METADATA, path);
        }
        audit("ENUMERATE_COMPLETE", FormalReadIntent.FINGERPRINT_METADATA, approvedSaveRoot);
        return paths.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    FormalFileMetadata fingerprintMetadata(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(approvedSaveRoot)) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT, normalized,
                    "Fingerprint metadata path is outside the approved save",
                    "Use only guard-enumerated formal files", List.of()));
        }
        ensureNoReparseAncestors(normalized);
        BasicFileAttributes attributes = Files.readAttributes(
                normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("fingerprint entry is not a regular file");
        audit("FINGERPRINT_METADATA", FormalReadIntent.FINGERPRINT_METADATA, normalized);
        return new FormalFileMetadata(attributes.size(), attributes.lastModifiedTime().toMillis(),
                containsPrivateSegment(approvedSaveRoot.relativize(normalized)));
    }

    String relative(Path path) {
        return approvedSaveRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void audit(String event, FormalReadIntent intent, Path path) throws IOException {
        String line = "{\"timestamp\":\"" + Instant.now() + "\",\"event\":\"" + event
                + "\",\"intent\":\"" + intent + "\",\"worldIdentity\":\""
                + escape(policy.worldIdentity()) + "\",\"path\":\"" + escape(identity(path)) + "\"}\n";
        Files.writeString(auditFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private FormalSurveyFailure failure(
            FormalSurveyFailureCode code, Path path, String reason, String nextStep, List<String> evidence) {
        return new FormalSurveyFailure(code, FormalSurveyStage.READ_ONLY_GUARD, policy.worldIdentity(),
                identity(path), NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE,
                "policy=" + policy.policyVersion(), evidence, reason, nextStep);
    }

    private static Path ordinaryRealDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparseAncestors(normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(identity(normalized));
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not an ordinary directory");
        }
        return normalized.toRealPath();
    }

    private static Path ordinaryRealFile(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparseAncestors(normalized);
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not an ordinary regular file");
        }
        return normalized.toRealPath();
    }

    private void verifyExistingAuditAncestors(Path path) throws FormalReadAccessException {
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    path,
                    "No existing ordinary ancestor can anchor the audit path",
                    "Use the repository ignored work/formal-survey directory",
                    List.of("auditRoot=" + identity(path))));
        }
        try {
            ensureNoReparseAncestors(existing.toAbsolutePath().normalize());
            Path realExisting = existing.toRealPath();
            if (realExisting.startsWith(formalInstanceRoot)) {
                throw new FormalReadAccessException(failure(
                        FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT,
                        path,
                        "An existing audit-path ancestor resolves inside the formal instance",
                        "Use an ordinary repository-owned audit path",
                        List.of("existingAncestor=" + identity(realExisting))));
            }
        } catch (FormalReadAccessException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new FormalReadAccessException(failure(
                    FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                    path,
                    "An audit-path ancestor is unresolved or reparse-like",
                    "Use an ordinary repository-owned audit path",
                    List.of(exception.getClass().getSimpleName())));
        }
    }

    private static void ensureNoReparseAncestors(Path path) throws IOException {
        Path root = path.getRoot();
        if (root == null) throw new IOException("path has no filesystem root");
        Path cursor = root;
        if (reparseLike(cursor)) throw new IOException("filesystem root is reparse-like");
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            if (reparseLike(cursor)) throw new IOException("path ancestor is reparse-like");
        }
    }

    private static boolean reparseLike(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isOther();
    }

    private static boolean containsPrivateSegment(Path relative) {
        for (Path segment : relative) {
            if (PRIVATE_PATH_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
