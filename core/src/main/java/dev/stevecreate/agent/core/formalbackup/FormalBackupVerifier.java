package dev.stevecreate.agent.core.formalbackup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Verifies a completed external backup without reading or resolving the formal source world. */
public final class FormalBackupVerifier {
    private static final String NOT_APPLICABLE = "not-applicable";
    private final FormalWorldBackupManifestCodec manifestCodec = new FormalWorldBackupManifestCodec();
    private final FormalBackupCompletionMarkerCodec markerCodec = new FormalBackupCompletionMarkerCodec();

    public FormalBackupVerificationDecision verify(FormalBackupVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            Path platform = ordinaryRealDirectory(request.formalPlatformRoot());
            Path repository = ordinaryRealDirectory(request.repositoryRoot());
            Path backupRoot = ordinaryRealDirectory(request.backupRoot());
            Path target = ordinaryRealDirectory(request.backupTarget());
            FormalBackupDestinationPlan expectedPlan = request.expectedPlan();
            if (!backupRoot.equals(repository.resolve("work/formal-backups").toRealPath(LinkOption.NOFOLLOW_LINKS))
                    || !target.startsWith(backupRoot) || target.equals(backupRoot)
                    || target.startsWith(platform) || backupRoot.startsWith(platform)
                    || !target.equals(expectedPlan.plannedTarget().toRealPath(LinkOption.NOFOLLOW_LINKS))
                    || !backupRoot.equals(expectedPlan.canonicalBackupRoot())) {
                return refuse(request, FormalBackupFailureCode.BACKUP_DESTINATION_UNSAFE,
                        NOT_APPLICABLE, NOT_APPLICABLE,
                        List.of("targetInsideRegisteredRoot=" + target.startsWith(backupRoot),
                                "targetInsideFormalPlatform=" + target.startsWith(platform)),
                        "The completed backup target is outside its exact safe root",
                        "Reject the target and use only a registered ignored formal backup");
            }
            Path manifestPath = ordinaryRealFile(target.resolve(FormalBackupService.MANIFEST_FILE));
            Path markerPath = ordinaryRealFile(target.resolve(FormalBackupService.COMPLETED_MARKER));
            byte[] manifestBytes = readBounded(manifestPath, 256L * 1_024 * 1_024, "manifest");
            byte[] markerBytes = readBounded(markerPath, 64L * 1_024, "completion marker");
            FormalWorldBackupManifest manifest = manifestCodec.decode(manifestBytes);
            FormalBackupCompletionMarker marker = markerCodec.decode(markerBytes);
            if (!marker.backupIdentity().equals(FormalBackupIdentityHasher.hash(
                    marker.planIdentity(), marker.worldIdentity(), marker.sourceFingerprint(),
                    marker.runtimeFingerprint(), marker.manifestHash(), marker.completedAt(),
                    marker.policyVersion(), marker.toolVersion(), marker.gitHead()))
                    || !marker.worldIdentity().equals(request.expectedWorldIdentity().value())
                    || !marker.planIdentity().equals(expectedPlan.planIdentity())
                    || !marker.sourceFingerprint().equals(expectedPlan.sourceFingerprint())
                    || !marker.runtimeFingerprint().equals(expectedPlan.runtimeFingerprint())
                    || !marker.policyVersion().equals(expectedPlan.policyVersion())
                    || !marker.toolVersion().equals(expectedPlan.toolVersion())
                    || !marker.gitHead().equals(expectedPlan.gitHead())
                    || !marker.manifestHash().equals(manifest.manifestHash())
                    || marker.fileCount() != manifest.entries().size()
                    || marker.totalBytes() != manifest.totalBytes()) {
                return refuse(request, FormalBackupFailureCode.BACKUP_MANIFEST_INVALID,
                        marker.backupIdentity(), manifest.manifestHash(),
                        List.of("worldIdentityMatch="
                                        + marker.worldIdentity().equals(request.expectedWorldIdentity().value()),
                                "expectedPlanMatch=" + marker.planIdentity().equals(expectedPlan.planIdentity()),
                                "sourceFingerprintMatch="
                                        + marker.sourceFingerprint().equals(expectedPlan.sourceFingerprint()),
                                "runtimeFingerprintMatch="
                                        + marker.runtimeFingerprint().equals(expectedPlan.runtimeFingerprint()),
                                "manifestHashMatch=" + marker.manifestHash().equals(manifest.manifestHash()),
                                "fileCountMatch=" + (marker.fileCount() == manifest.entries().size()),
                                "totalBytesMatch=" + (marker.totalBytes() == manifest.totalBytes())),
                        "Completion identity and canonical manifest do not agree",
                        "Do not restore or bind approval to this backup");
            }
            FormalWorldBackupManifest observed = FormalBackupSourceGuard.manifestAtTarget(
                    target, manifest, true, true);
            if (!observed.equals(manifest)) {
                return refuse(request, FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                        marker.backupIdentity(), observed.manifestHash(),
                        List.of("expectedManifest=" + manifest.manifestHash(),
                                "observedManifest=" + observed.manifestHash()),
                        "The completed backup tree changed after publication",
                        "Do not restore from the changed backup");
            }
            FormalWorldBackupIdentity identity = new FormalWorldBackupIdentity(
                    marker.backupIdentity(), request.expectedWorldIdentity(), marker.sourceFingerprint(),
                    marker.runtimeFingerprint(), marker.completedAt(), marker.manifestHash(),
                    marker.fileCount(), marker.totalBytes(), marker.policyVersion(), marker.toolVersion(),
                    marker.gitHead(), marker.state(), target);
            return FormalBackupVerificationDecision.success(new BackupVerificationResult(
                    identity, manifest, List.of(FormalBackupVerificationCheck.values()),
                    sha256(markerBytes), true, false, false, false));
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return refuse(request, FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                    NOT_APPLICABLE, NOT_APPLICABLE,
                    List.of("exception=" + exception.getClass().getSimpleName()),
                    "The completed backup could not be verified exactly",
                    "Keep the formal source untouched and reject this backup");
        }
    }

    private static FormalBackupVerificationDecision refuse(
            FormalBackupVerificationRequest request,
            FormalBackupFailureCode code,
            String backupIdentity,
            String manifestIdentity,
            List<String> evidence,
            String reason,
            String nextStep) {
        return FormalBackupVerificationDecision.refuse(new FormalBackupFailure(
                code, FormalBackupStage.VERIFICATION, request.expectedWorldIdentity().value(),
                backupIdentity, NOT_APPLICABLE, identity(request.formalPlatformRoot()),
                identity(request.backupTarget()), NOT_APPLICABLE, NOT_APPLICABLE,
                0, 0, manifestIdentity, "NOT_REQUESTED", evidence, reason, nextStep));
    }

    private static Path ordinaryRealDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparse(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("path is not an ordinary directory");
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path ordinaryRealFile(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureNoReparse(normalized);
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("path is not an ordinary file");
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

    private static String sha256(byte[] bytes) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static byte[] readBounded(Path path, long maximumBytes, String name) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new IOException(name + " size is outside its bound");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != size) throw new IOException(name + " changed while reading");
        return bytes;
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
