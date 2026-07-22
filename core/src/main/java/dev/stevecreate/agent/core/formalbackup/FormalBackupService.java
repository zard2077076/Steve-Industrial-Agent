package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalWorldFingerprint;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprintService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** FB-02 transaction: verified READ-only source, unique staging, manifest, atomic publish and marker. */
public final class FormalBackupService {
    public static final String COMPLETED_MARKER = ".formal-backup-completed.json";
    public static final String MANIFEST_FILE = ".formal-backup-manifest.tsv";
    private static final String NOT_APPLICABLE = "not-applicable";
    private static final Duration MAXIMUM_QUIESCENCE_AGE = Duration.ofMinutes(5);
    private final Clock clock;
    private final CopyObserver observer;
    private final FormalWorldFingerprintService fingerprintService = new FormalWorldFingerprintService();
    private final FormalWorldBackupManifestCodec manifestCodec = new FormalWorldBackupManifestCodec();
    private final FormalBackupCompletionMarkerCodec markerCodec = new FormalBackupCompletionMarkerCodec();

    public FormalBackupService() {
        this(Clock.systemUTC(), () -> {});
    }

    FormalBackupService(Clock clock, CopyObserver observer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public FormalBackupCopyDecision create(FormalBackupCopyRequest request) {
        Objects.requireNonNull(request, "request");
        FormalBackupDestinationPlan plan = request.plan();
        Path staging = plan.plannedStaging();
        boolean stagingCreated = false;
        try {
            validateCurrentPlan(request);
            Instant operationStartedAt = clock.instant();
            if (!request.quiescenceEvidence().quiescent()
                    || !request.quiescenceEvidence().freshAt(
                            operationStartedAt, MAXIMUM_QUIESCENCE_AGE)) {
                return refuse(plan, FormalBackupFailureCode.BACKUP_COPY_FAILED,
                        FormalBackupStage.SOURCE_PREFLIGHT, NOT_APPLICABLE,
                        List.of("processScanComplete=" + request.quiescenceEvidence().processScanComplete(),
                                "sourceHandleScanComplete=" + request.quiescenceEvidence().sourceHandleScanComplete(),
                                "formalWorldJavaProcessCount="
                                        + request.quiescenceEvidence().formalWorldJavaProcessIds().size(),
                                "sourceHandleOwnerCount=" + request.quiescenceEvidence().formalSourceHandleOwners().size(),
                                "observedAt=" + request.quiescenceEvidence().observedAt(),
                                "operationStartedAt=" + operationStartedAt),
                        "Fresh formal source quiescence was not proven immediately before backup",
                        "Stop Minecraft, Forge and Java processes and repeat complete process/handle scans");
            }
            FormalWorldFingerprint pre = fingerprintService.capture(
                    request.fingerprintGuard(), plan.worldIdentity(), request.fingerprintPolicy());
            if (!pre.worldIdentity().equals(plan.worldIdentity())
                    || !pre.worldFingerprint().equals(plan.sourceFingerprint())
                    || pre.fileCount() != plan.sourceFileCount()
                    || pre.totalBytes() != plan.estimatedSourceBytes()) {
                return refuse(plan, FormalBackupFailureCode.BACKUP_SOURCE_WORLD_MISMATCH,
                        FormalBackupStage.SOURCE_PREFLIGHT, NOT_APPLICABLE,
                        List.of("observedWorld=" + pre.worldIdentity(),
                                "observedFingerprint=" + pre.worldFingerprint(),
                                "observedFiles=" + pre.fileCount(),
                                "observedBytes=" + pre.totalBytes()),
                        "The current guarded source no longer matches the destination plan",
                        "Repeat the guarded source fingerprint and create a new destination plan");
            }

            FormalBackupSourceGuard source = new FormalBackupSourceGuard(plan);
            FormalBackupSourceSnapshot opaquePre = source.snapshot(
                    request.maxSourceFiles(), request.maxSourceBytes());
            prepareOrdinaryDestinationParent(plan);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(plan.plannedTarget(), LinkOption.NOFOLLOW_LINKS)) {
                return refuse(plan, FormalBackupFailureCode.BACKUP_ALREADY_EXISTS,
                        FormalBackupStage.SOURCE_PREFLIGHT, opaquePre.manifest().manifestHash(),
                        List.of("targetExists=" + Files.exists(plan.plannedTarget(), LinkOption.NOFOLLOW_LINKS),
                                "stagingExists=" + Files.exists(staging, LinkOption.NOFOLLOW_LINKS)),
                        "The immutable target or staging path appeared after planning",
                        "Do not overwrite it; create a fresh timestamped destination plan");
            }
            Files.createDirectory(staging);
            stagingCreated = true;
            for (FormalBackupFileEntry entry : opaquePre.manifest().entries()) {
                source.copyOpaqueToStaging(entry);
            }
            FormalWorldBackupManifest stagingManifest = FormalBackupSourceGuard.manifestAtTarget(
                    staging, opaquePre.manifest(), false, false);
            if (!stagingManifest.equals(opaquePre.manifest())) {
                cleanupStaging(staging, plan);
                return refuse(plan, FormalBackupFailureCode.BACKUP_MANIFEST_INVALID,
                        FormalBackupStage.VERIFICATION, stagingManifest.manifestHash(),
                        List.of("expectedManifest=" + opaquePre.manifest().manifestHash(),
                                "observedManifest=" + stagingManifest.manifestHash()),
                        "The staging tree does not exactly match the complete source manifest",
                        "Keep the formal source untouched and investigate the disposable staging copy");
            }
            Path persistedManifest = staging.resolve(MANIFEST_FILE);
            Files.write(persistedManifest, manifestCodec.encode(opaquePre.manifest()),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            FormalWorldBackupManifest manifestReadback = manifestCodec.decode(Files.readAllBytes(persistedManifest));
            if (!manifestReadback.equals(opaquePre.manifest())) {
                cleanupStaging(staging, plan);
                return refuse(plan, FormalBackupFailureCode.BACKUP_MANIFEST_INVALID,
                        FormalBackupStage.MANIFEST, manifestReadback.manifestHash(),
                        List.of("expectedManifest=" + opaquePre.manifest().manifestHash(),
                                "readbackManifest=" + manifestReadback.manifestHash()),
                        "The persisted staging manifest did not round-trip exactly",
                        "Keep the source untouched and reject the disposable staging transaction");
            }

            observer.afterStagingVerified();
            FormalBackupSourceSnapshot opaqueCopyPost = source.snapshot(
                    request.maxSourceFiles(), request.maxSourceBytes());
            FormalWorldFingerprint copyPost = fingerprintService.capture(
                    request.fingerprintGuard(), plan.worldIdentity(), request.fingerprintPolicy());
            if (!pre.exactlyMatches(copyPost) || !opaquePre.equals(opaqueCopyPost)) {
                cleanupStaging(staging, plan);
                return refuse(plan, FormalBackupFailureCode.BACKUP_SOURCE_CHANGED_DURING_COPY,
                        FormalBackupStage.VERIFICATION, opaquePre.manifest().manifestHash(),
                        List.of("preFingerprint=" + pre.worldFingerprint(),
                                "postFingerprint=" + copyPost.worldFingerprint(),
                                "opaquePre=" + opaquePre.snapshotFingerprint(),
                                "opaquePost=" + opaqueCopyPost.snapshotFingerprint()),
                        "The formal source changed during the backup transaction",
                        "Stop and repeat only after the formal world is quiescent");
            }

            try {
                Files.move(staging, plan.plannedTarget(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                cleanupStaging(staging, plan);
                return refuse(plan, FormalBackupFailureCode.BACKUP_COPY_FAILED,
                        FormalBackupStage.PUBLICATION, opaquePre.manifest().manifestHash(),
                        List.of("exception=" + exception.getClass().getSimpleName()),
                        "The destination filesystem cannot atomically publish the verified staging tree",
                        "Use one local filesystem that supports atomic directory rename");
            }
            stagingCreated = false;
            Instant completedAt = clock.instant();
            String backupIdentity = FormalBackupIdentityHasher.hash(
                    plan.planIdentity(), plan.worldIdentity(), plan.sourceFingerprint(),
                    plan.runtimeFingerprint(), opaquePre.manifest().manifestHash(), completedAt,
                    plan.policyVersion(), plan.toolVersion(), plan.gitHead());
            Path marker = plan.plannedTarget().resolve(COMPLETED_MARKER);
            FormalWorldBackupManifest publishedBeforeMarker = FormalBackupSourceGuard.manifestAtTarget(
                    plan.plannedTarget(), opaquePre.manifest(), true, false);
            if (!publishedBeforeMarker.equals(opaquePre.manifest())) {
                return refuse(plan, FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                        FormalBackupStage.VERIFICATION, publishedBeforeMarker.manifestHash(),
                        List.of("expectedManifest=" + opaquePre.manifest().manifestHash(),
                                "publishedManifest=" + publishedBeforeMarker.manifestHash()),
                        "The atomically published backup does not match its complete manifest",
                        "Do not create completion evidence or use the backup for restore");
            }
            FormalBackupCompletionMarker markerEvidence = new FormalBackupCompletionMarker(
                    FormalBackupCompletionState.COMPLETED, backupIdentity, plan.planIdentity(),
                    plan.worldIdentity(), plan.sourceFingerprint(), plan.runtimeFingerprint(),
                    opaquePre.manifest().manifestHash(), opaquePre.manifest().entries().size(),
                    opaquePre.manifest().totalBytes(), completedAt, plan.policyVersion(),
                    plan.toolVersion(), plan.gitHead());
            Files.write(marker, markerCodec.encode(markerEvidence),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!markerCodec.decode(Files.readAllBytes(marker)).equals(markerEvidence)) {
                Files.deleteIfExists(marker);
                return refuse(plan, FormalBackupFailureCode.BACKUP_INCOMPLETE,
                        FormalBackupStage.PUBLICATION, opaquePre.manifest().manifestHash(),
                        List.of("markerReadback=false"),
                        "The completion marker did not round-trip exactly",
                        "Keep the published tree invalid and create a fresh backup plan");
            }
            FormalWorldBackupManifest published = FormalBackupSourceGuard.manifestAtTarget(
                    plan.plannedTarget(), opaquePre.manifest(), true, true);
            if (!published.equals(opaquePre.manifest())) {
                return refuse(plan, FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                        FormalBackupStage.VERIFICATION, published.manifestHash(),
                        List.of("expectedManifest=" + opaquePre.manifest().manifestHash(),
                                "publishedManifest=" + published.manifestHash()),
                        "The published backup does not match its completed manifest",
                        "Do not use the backup for restore or approval identity");
            }
            FormalBackupSourceSnapshot opaquePost = source.snapshot(
                    request.maxSourceFiles(), request.maxSourceBytes());
            FormalWorldFingerprint post = fingerprintService.capture(
                    request.fingerprintGuard(), plan.worldIdentity(), request.fingerprintPolicy());
            if (!pre.exactlyMatches(post) || !opaquePre.equals(opaquePost)) {
                Files.deleteIfExists(marker);
                return refuse(plan, FormalBackupFailureCode.BACKUP_SOURCE_CHANGED_DURING_COPY,
                        FormalBackupStage.VERIFICATION, opaquePre.manifest().manifestHash(),
                        List.of("preFingerprint=" + pre.worldFingerprint(),
                                "postFingerprint=" + post.worldFingerprint(),
                                "opaquePre=" + opaquePre.snapshotFingerprint(),
                                "opaquePost=" + opaquePost.snapshotFingerprint(),
                                "completionMarkerRemoved=true"),
                        "The formal source changed before the completed backup could be accepted",
                        "Keep the unmarked published copy invalid and create a fresh plan when quiescent");
            }
            return FormalBackupCopyDecision.success(new FormalBackupCopyResult(
                    backupIdentity, plan, published, pre, post,
                    opaquePre.snapshotFingerprint(), opaquePost.snapshotFingerprint(),
                    plan.plannedTarget(), marker, completedAt,
                    true, true, true, false, false, false, false));
        } catch (IOException | ArithmeticException | SecurityException exception) {
            if (stagingCreated) safeCleanup(staging, plan);
            return refuse(plan, FormalBackupFailureCode.BACKUP_COPY_FAILED,
                    FormalBackupStage.COPY, NOT_APPLICABLE,
                    List.of("exception=" + exception.getClass().getSimpleName()),
                    "The guarded formal backup transaction failed closed",
                    "Keep the source untouched; review the typed evidence and use a fresh plan");
        }
    }

    private static void validateCurrentPlan(FormalBackupCopyRequest request) throws IOException {
        FormalBackupDestinationPlan plan = request.plan();
        Path source = plan.canonicalSourceRoot().toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path backup = plan.canonicalBackupRoot().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!request.fingerprintGuard().worldIdentity().equals(plan.worldIdentity())
                || !Path.of(request.fingerprintGuard().approvedSaveRootIdentity()).toRealPath().equals(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)
                || !plan.plannedTarget().startsWith(backup)
                || !plan.plannedStaging().startsWith(backup)
                || plan.plannedTarget().startsWith(source)
                || plan.plannedStaging().startsWith(source)) {
            throw new IOException("current backup plan roots are unsafe or mismatched");
        }
        if (Files.exists(plan.plannedTarget(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(plan.plannedStaging(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("immutable backup target already exists");
        }
        long available = Files.getFileStore(backup).getUsableSpace();
        if (available < plan.requiredSpaceBytes()) throw new IOException("current backup space is insufficient");
    }

    private static void prepareOrdinaryDestinationParent(FormalBackupDestinationPlan plan) throws IOException {
        Path backup = plan.canonicalBackupRoot().toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path parent = plan.plannedStaging().getParent().toAbsolutePath().normalize();
        if (!parent.equals(plan.plannedTarget().getParent().toAbsolutePath().normalize())
                || !parent.startsWith(backup)) {
            throw new IOException("destination parent escaped the exact backup root");
        }
        Path cursor = backup;
        for (Path segment : backup.relativize(parent)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("destination parent is reparse-like or non-directory");
                }
            } else {
                Files.createDirectory(cursor);
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("created destination parent is unsafe");
                }
            }
        }
        if (!parent.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(backup)) {
            throw new IOException("destination parent resolved outside backup root");
        }
    }

    private static void cleanupStaging(Path staging, FormalBackupDestinationPlan plan) throws IOException {
        Path normalized = staging.toAbsolutePath().normalize();
        Path backup = plan.canonicalBackupRoot().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!normalized.equals(plan.plannedStaging().toAbsolutePath().normalize())
                || !normalized.startsWith(backup)
                || normalized.startsWith(plan.canonicalSourceRoot().toAbsolutePath().normalize())) {
            throw new IOException("staging cleanup target is unsafe");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("staging cleanup encountered a link");
                Files.delete(path);
            }
        }
    }

    private static void safeCleanup(Path staging, FormalBackupDestinationPlan plan) {
        try {
            cleanupStaging(staging, plan);
        } catch (IOException ignored) {
            // Unknown staging is retained for manual review; the formal source is never touched.
        }
    }

    private static FormalBackupCopyDecision refuse(
            FormalBackupDestinationPlan plan,
            FormalBackupFailureCode code,
            FormalBackupStage stage,
            String manifestIdentity,
            List<String> evidence,
            String reason,
            String nextStep) {
        return FormalBackupCopyDecision.refuse(new FormalBackupFailure(
                code, stage, plan.worldIdentity(), NOT_APPLICABLE, NOT_APPLICABLE,
                identity(plan.canonicalSourceRoot()), identity(plan.plannedTarget()),
                plan.sourceFingerprint(), plan.runtimeFingerprint(), plan.requiredSpaceBytes(),
                plan.observedUsableSpaceBytes(), manifestIdentity, "NOT_REQUESTED",
                evidence, reason, nextStep));
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    @FunctionalInterface
    interface CopyObserver {
        void afterStagingVerified() throws IOException;
    }
}
