package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalReadOnlyPolicy;
import dev.stevecreate.agent.core.survey.FormalSaveDiscovery;
import dev.stevecreate.agent.core.survey.FormalSaveDiscoveryResult;
import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import dev.stevecreate.agent.core.survey.FormalWorldReadOnlyGuard;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Restores only into a new disposable repository work tree and reparses level.dat offline. */
public final class FormalRestoreDrillService {
    private static final String NOT_APPLICABLE = "not-applicable";
    private final FormalBackupVerifier verifier;
    private final Clock clock;

    public FormalRestoreDrillService() {
        this(new FormalBackupVerifier(), Clock.systemUTC());
    }

    FormalRestoreDrillService(FormalBackupVerifier verifier, Clock clock) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FormalRestoreDrillDecision run(FormalRestoreDrillRequest request) {
        Objects.requireNonNull(request, "request");
        BackupVerificationResult expected = request.verifiedBackup();
        Path staging = null;
        boolean stagingCreated = false;
        try {
            FormalBackupVerificationDecision currentDecision = verifier.verify(request.verificationRequest());
            if (currentDecision.result().isEmpty()
                    || !currentDecision.result().orElseThrow().equals(expected)) {
                return refuse(request, FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                        NOT_APPLICABLE, List.of("freshVerificationMatch=false"),
                        "The completed backup did not pass an identical fresh verification",
                        "Do not create a restore copy from stale or changed evidence");
            }
            Path platform = ordinaryRealDirectory(request.verificationRequest().formalPlatformRoot());
            Path repository = ordinaryRealDirectory(request.verificationRequest().repositoryRoot());
            Path restoreRoot = ordinaryRealDirectory(request.restoreRoot());
            Path requiredRestoreRoot = repository.resolve("work/formal-restore-drills").normalize();
            if (!restoreRoot.equals(requiredRestoreRoot.toRealPath(LinkOption.NOFOLLOW_LINKS))
                    || restoreRoot.startsWith(platform)
                    || restoreRoot.startsWith(expected.identity().canonicalBackupTarget())) {
                return refuse(request, FormalBackupFailureCode.RESTORE_TARGET_UNSAFE,
                        expected.manifest().manifestHash(),
                        List.of("requiredRestoreRoot=" + identity(requiredRestoreRoot)),
                        "The restore root is not the exact registered disposable work directory",
                        "Use only repository work/formal-restore-drills outside the formal platform");
            }
            String backupSlug = expected.identity().backupIdentity().substring("formal-backup:".length(), 32);
            String directory = request.requestedAt().toEpochMilli() + "-" + backupSlug;
            Path target = restoreRoot.resolve(backupSlug).resolve(directory).normalize();
            staging = target.resolveSibling(target.getFileName() + ".staging");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                return refuse(request, FormalBackupFailureCode.RESTORE_TARGET_UNSAFE,
                        expected.manifest().manifestHash(),
                        List.of("targetExists=" + Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                                "stagingExists=" + Files.exists(staging, LinkOption.NOFOLLOW_LINKS)),
                        "The immutable disposable restore target already exists",
                        "Use a fresh restore request timestamp and never overwrite a drill");
            }
            prepareOrdinaryParent(restoreRoot, staging.getParent());
            Files.createDirectory(staging);
            stagingCreated = true;
            Path saves = Files.createDirectory(staging.resolve("saves"));
            Path restoredWorld = Files.createDirectory(saves.resolve(
                    expected.identity().worldIdentity().saveDirectoryName()));
            copyOpaqueBackup(expected.identity().canonicalBackupTarget(), restoredWorld, expected.manifest());
            FormalWorldBackupManifest stagedManifest = FormalBackupSourceGuard.manifestAtTarget(
                    restoredWorld, expected.manifest(), false, false);
            if (!stagedManifest.equals(expected.manifest())) {
                cleanup(staging, restoreRoot);
                return refuse(request, FormalBackupFailureCode.RESTORE_FINGERPRINT_MISMATCH,
                        stagedManifest.manifestHash(),
                        List.of("expectedManifest=" + expected.manifest().manifestHash(),
                                "stagedManifest=" + stagedManifest.manifestHash()),
                        "The disposable restored file tree does not match the backup manifest",
                        "Reject and remove only the disposable staging tree");
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                cleanup(staging, restoreRoot);
                return refuse(request, FormalBackupFailureCode.RESTORE_DRILL_FAILED,
                        expected.manifest().manifestHash(),
                        List.of("exception=" + exception.getClass().getSimpleName()),
                        "The disposable restore tree could not be atomically published",
                        "Use a single local filesystem that supports atomic directory rename");
            }
            stagingCreated = false;
            Path publishedWorld = target.resolve("saves").resolve(
                    expected.identity().worldIdentity().saveDirectoryName());
            FormalWorldBackupManifest published = FormalBackupSourceGuard.manifestAtTarget(
                    publishedWorld, expected.manifest(), false, false);
            if (!published.equals(expected.manifest())) {
                return refuse(request, FormalBackupFailureCode.RESTORE_FINGERPRINT_MISMATCH,
                        published.manifestHash(),
                        List.of("expectedManifest=" + expected.manifest().manifestHash(),
                                "publishedManifest=" + published.manifestHash()),
                        "The published disposable restore fingerprint is invalid",
                        "Retain the disposable copy for review and do not use it");
            }
            Path audit = repository.resolve("work/formal-restore-audit")
                    .resolve(backupSlug).resolve(directory);
            FormalWorldReadOnlyGuard restoredGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                    "formal-restore-drill-v1", expected.identity().worldIdentity().value(),
                    target, target.resolve("saves"), audit));
            FormalSaveDiscoveryResult discovery = new FormalSaveDiscovery(
                    restoredGuard, request.nbtReadLimits()).discover(target);
            FormalWorldIdentity observedWorld = discovery.selectedCandidate()
                    .orElseThrow(() -> new IOException("restored world was not uniquely reparsed"))
                    .worldIdentity();
            if (!observedWorld.equals(expected.identity().worldIdentity())) {
                return refuse(request, FormalBackupFailureCode.RESTORE_FINGERPRINT_MISMATCH,
                        published.manifestHash(),
                        List.of("expectedWorld=" + expected.identity().worldIdentity().value(),
                                "observedWorld=" + observedWorld.value()),
                        "Offline level.dat reparse produced a different world identity",
                        "Retain the disposable copy for review and reject the drill");
            }
            Instant completedAt = clock.instant();
            String drillIdentity = restoreIdentity(expected.identity(), target, completedAt);
            return FormalRestoreDrillDecision.success(new RestoreDrillResult(
                    drillIdentity, expected.identity(), target, published, observedWorld,
                    List.of(FormalRestoreDrillCheck.values()), completedAt,
                    true, false, false, false, false, true));
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            if (stagingCreated && staging != null) safeCleanup(staging, request.restoreRoot());
            return refuse(request, FormalBackupFailureCode.RESTORE_DRILL_FAILED,
                    NOT_APPLICABLE, List.of("exception=" + exception.getClass().getSimpleName()),
                    "The disposable restore drill failed closed",
                    "Keep the formal source untouched and review only ignored work evidence");
        }
    }

    private static void copyOpaqueBackup(
            Path backupTarget,
            Path restoredWorld,
            FormalWorldBackupManifest manifest) throws IOException {
        Path canonicalRestoreRoot = ordinaryRealDirectory(restoredWorld);
        for (FormalBackupFileEntry entry : manifest.entries()) {
            Path source = safeResolve(backupTarget, entry.relativePath());
            Path target = safeResolve(canonicalRestoreRoot, entry.relativePath());
            prepareOrdinaryParentCanonical(canonicalRestoreRoot, target.getParent());
            Files.copy(source, target);
            Files.setLastModifiedTime(target, FileTime.fromMillis(entry.lastModifiedEpochMillis()));
        }
    }

    private static void prepareOrdinaryParent(Path root, Path parent) throws IOException {
        Path canonicalRoot = ordinaryRealDirectory(root);
        prepareOrdinaryParentCanonical(canonicalRoot, parent);
    }

    private static void prepareOrdinaryParentCanonical(Path canonicalRoot, Path parent) throws IOException {
        Path normalized = parent.toAbsolutePath().normalize();
        if (!normalized.startsWith(canonicalRoot)) throw new IOException("restore parent escaped root");
        Path cursor = canonicalRoot;
        for (Path segment : canonicalRoot.relativize(normalized)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("restore parent is unsafe");
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        FormalBackupFileEntry.relative(relative);
        Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) throw new IOException("restore path escaped root");
        return resolved;
    }

    private static String restoreIdentity(
            FormalWorldBackupIdentity backup,
            Path target,
            Instant completedAt) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        FormalWorldBackupManifest.update(digest, backup.backupIdentity());
        FormalWorldBackupManifest.update(digest, backup.manifestHash());
        FormalWorldBackupManifest.update(digest, identity(target));
        FormalWorldBackupManifest.update(digest, completedAt.toString());
        return "restore-drill:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void cleanup(Path target, Path restoreRoot) throws IOException {
        Path root = ordinaryRealDirectory(restoreRoot);
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("restore cleanup target is unsafe");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("restore cleanup encountered a link");
                Files.delete(path);
            }
        }
    }

    private static void safeCleanup(Path target, Path restoreRoot) {
        try {
            cleanup(target, restoreRoot);
        } catch (IOException ignored) {
            // Unknown disposable paths are retained; the formal source and completed backup stay untouched.
        }
    }

    private static Path ordinaryRealDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparse(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("path is not an ordinary directory");
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static void ensureNoReparse(Path path) throws IOException {
        Path root = path.getRoot();
        if (root == null) throw new IOException("path has no root");
        Path cursor = root;
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)) throw new IOException("path is symbolic");
            BasicFileAttributes attributes = Files.readAttributes(
                    cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther()) throw new IOException("path is reparse-like");
        }
    }

    private static FormalRestoreDrillDecision refuse(
            FormalRestoreDrillRequest request,
            FormalBackupFailureCode code,
            String manifestIdentity,
            List<String> evidence,
            String reason,
            String nextStep) {
        FormalWorldBackupIdentity identity = request.verifiedBackup().identity();
        return FormalRestoreDrillDecision.refuse(new FormalBackupFailure(
                code, FormalBackupStage.RESTORE_DRILL, identity.worldIdentity().value(),
                identity.backupIdentity(), NOT_APPLICABLE,
                FormalRestoreDrillService.identity(request.verificationRequest().formalPlatformRoot()),
                FormalRestoreDrillService.identity(request.restoreRoot()), identity.sourceFingerprint(),
                identity.runtimeFingerprint(), 0, 0, manifestIdentity, "NOT_REQUESTED",
                evidence, reason, nextStep));
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
