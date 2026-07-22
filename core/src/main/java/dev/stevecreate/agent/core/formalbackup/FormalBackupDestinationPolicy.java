package dev.stevecreate.agent.core.formalbackup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/** Pure FB-01 destination planner except for read-only canonical-path and FileStore observations. */
public final class FormalBackupDestinationPolicy {
    private static final String NOT_APPLICABLE = "not-applicable";
    private final ToLongFunction<Path> usableSpace;

    public FormalBackupDestinationPolicy() {
        this(path -> {
            try {
                return Files.getFileStore(path).getUsableSpace();
            } catch (IOException exception) {
                throw new SpaceObservationException(exception);
            }
        });
    }

    FormalBackupDestinationPolicy(ToLongFunction<Path> usableSpace) {
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
    }

    public FormalBackupDestinationDecision plan(FormalBackupDestinationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.expectedWorldIdentity().equals(request.observedWorldIdentity())) {
            return refuse(request, FormalBackupFailureCode.BACKUP_SOURCE_WORLD_MISMATCH,
                    request.sourceWorldRoot(), request.backupRoot(), 0, 0,
                    List.of("expected=" + request.expectedWorldIdentity(),
                            "observed=" + request.observedWorldIdentity()),
                    "The current guarded source identity does not match the selected formal world",
                    "Repeat guarded source discovery and do not create a backup destination");
        }

        Path platform;
        Path instance;
        Path source;
        Path repository;
        Path backup;
        try {
            platform = ordinaryRealDirectory(request.formalPlatformRoot());
            instance = ordinaryRealDirectory(request.formalInstanceRoot());
            source = ordinaryRealDirectory(request.sourceWorldRoot());
            repository = ordinaryRealDirectory(request.repositoryRoot());
            backup = ordinaryRealDirectory(request.backupRoot());
        } catch (IOException | SecurityException exception) {
            return refuse(request, FormalBackupFailureCode.BACKUP_REPARSE_POINT_FORBIDDEN,
                    request.sourceWorldRoot(), request.backupRoot(), 0, 0,
                    List.of("exception=" + exception.getClass().getSimpleName()),
                    "A required root is missing, unresolved, symbolic, junction or reparse-like",
                    "Use only existing ordinary roots and review every canonical ancestor");
        }

        if (!instance.startsWith(platform) || !source.startsWith(instance)) {
            return refuse(request, FormalBackupFailureCode.BACKUP_SOURCE_WORLD_MISMATCH,
                    source, backup, 0, 0,
                    List.of("platform=" + identity(platform), "instance=" + identity(instance)),
                    "The source is not inside the exact formal instance/platform hierarchy",
                    "Use the uniquely discovered formal source root without path substitution");
        }
        Path expectedBackup = repository.resolve("work/formal-backups").normalize();
        if (!backup.equals(expectedBackup)) {
            return refuse(request, FormalBackupFailureCode.BACKUP_DESTINATION_UNSAFE,
                    source, backup, 0, 0,
                    List.of("requiredBackupRoot=" + identity(expectedBackup)),
                    "The backup root is not the repository-owned ignored work/formal-backups root",
                    "Use the exact registered repository backup root; arbitrary paths are forbidden");
        }
        if (backup.startsWith(platform) || backup.startsWith(instance) || backup.startsWith(source)) {
            return refuse(request, FormalBackupFailureCode.BACKUP_DESTINATION_INSIDE_FORMAL_ROOT,
                    source, backup, 0, 0,
                    List.of("formalPlatform=" + identity(platform)),
                    "The canonical backup root is inside the formal platform hierarchy",
                    "Use the repository ignored backup root outside the complete formal platform");
        }

        long required;
        long available;
        try {
            required = Math.addExact(request.estimatedSourceBytes(), request.safetyMarginBytes());
            available = usableSpace.applyAsLong(backup);
            if (available < 0) throw new IllegalArgumentException("usable space is negative");
        } catch (ArithmeticException | IllegalArgumentException | SpaceObservationException exception) {
            return refuse(request, FormalBackupFailureCode.BACKUP_DISK_SPACE_INSUFFICIENT,
                    source, backup, Long.MAX_VALUE, 0,
                    List.of("exception=" + exception.getClass().getSimpleName()),
                    "Required or available backup capacity could not be proven safely",
                    "Stop and verify the destination FileStore and a bounded source estimate");
        }
        if (available < required) {
            return refuse(request, FormalBackupFailureCode.BACKUP_DISK_SPACE_INSUFFICIENT,
                    source, backup, required, available,
                    List.of("estimatedSourceBytes=" + request.estimatedSourceBytes(),
                            "safetyMarginBytes=" + request.safetyMarginBytes()),
                    "The backup destination does not have the required source size plus safety margin",
                    "Free space or choose a separately reviewed safe local destination outside the formal platform");
        }

        String planIdentity = planIdentity(request, source, backup, required);
        String slug = request.expectedWorldIdentity().substring("world:".length());
        String directoryName = request.requestedAt().toEpochMilli() + "-" + planIdentity.substring(12, 28);
        Path target = backup.resolve(slug).resolve(directoryName).normalize();
        Path staging = target.resolveSibling(target.getFileName() + ".staging");
        try {
            ensureNoReparseOnExistingAncestors(target);
            ensureNoReparseOnExistingAncestors(staging);
        } catch (IOException | SecurityException exception) {
            return refuse(request, FormalBackupFailureCode.BACKUP_REPARSE_POINT_FORBIDDEN,
                    source, target, required, available,
                    List.of("exception=" + exception.getClass().getSimpleName()),
                    "A planned destination ancestor is symbolic, junction or reparse-like",
                    "Reject the target and use an ordinary repository work tree");
        }
        if (!target.startsWith(backup) || !staging.startsWith(backup)) {
            return refuse(request, FormalBackupFailureCode.BACKUP_DESTINATION_UNSAFE,
                    source, target, required, available,
                    List.of("backupRoot=" + identity(backup)),
                    "The computed target escaped the exact registered backup root",
                    "Reject the plan and review its canonical identity inputs");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return refuse(request, FormalBackupFailureCode.BACKUP_ALREADY_EXISTS,
                    source, target, required, available,
                    List.of("targetExists=" + Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                            "stagingExists=" + Files.exists(staging, LinkOption.NOFOLLOW_LINKS)),
                    "The exact immutable backup target or staging sibling already exists",
                    "Generate a new timestamped plan; never overwrite or resume it implicitly");
        }

        return FormalBackupDestinationDecision.allow(new FormalBackupDestinationPlan(
                planIdentity, request.expectedWorldIdentity(), request.sourceFingerprint(),
                request.runtimeFingerprint(), source, backup, target, staging,
                request.sourceFileCount(), request.estimatedSourceBytes(), request.safetyMarginBytes(),
                required, available, request.requestedAt(), request.policyVersion(), request.toolVersion(),
                request.gitHead(), true, true, false, false, false, false));
    }

    private static FormalBackupDestinationDecision refuse(
            FormalBackupDestinationRequest request,
            FormalBackupFailureCode code,
            Path source,
            Path destination,
            long required,
            long actual,
            List<String> evidence,
            String reason,
            String nextStep) {
        FormalBackupFailure failure = new FormalBackupFailure(code, FormalBackupStage.DESTINATION_POLICY,
                request.expectedWorldIdentity(), NOT_APPLICABLE, NOT_APPLICABLE,
                identity(source), identity(destination), request.sourceFingerprint(),
                request.runtimeFingerprint(), required, actual, NOT_APPLICABLE, "NOT_REQUESTED",
                evidence, reason, nextStep);
        return FormalBackupDestinationDecision.refuse(failure);
    }

    private static String planIdentity(
            FormalBackupDestinationRequest request,
            Path source,
            Path backup,
            long required) {
        String canonical = request.expectedWorldIdentity() + "\n"
                + request.sourceFingerprint() + "\n" + request.runtimeFingerprint() + "\n"
                + identity(source) + "\n" + identity(backup) + "\n"
                + request.sourceFileCount() + "\n" + request.estimatedSourceBytes() + "\n"
                + request.safetyMarginBytes() + "\n" + required + "\n" + request.requestedAt() + "\n"
                + request.policyVersion() + "\n" + request.toolVersion() + "\n" + request.gitHead();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "backup-plan:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path ordinaryRealDirectory(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
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

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static final class SpaceObservationException extends RuntimeException {
        private SpaceObservationException(IOException cause) {
            super(cause);
        }
    }
}
