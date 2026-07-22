package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Filesystem operations are hard-limited to verifier-approved isolated plans. */
public final class IsolatedBackupService {
    private final BackupVerifier verifier;

    public IsolatedBackupService(BackupVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public BackupOperationResult createBackup(BackupPlan plan) {
        BackupVerification verification = verifier.verify(plan);
        if (!verification.valid()) return refused(plan, verification.failures().toString());
        Path staging = null;
        try {
            Path source = plan.pathPolicy().canonicalSource(plan.sourceWorldRoot());
            Path target = plan.pathPolicy().canonicalTarget(plan.backupTarget());
            if (Files.exists(target)) return failed(plan, "backup target already exists");
            long currentAvailable = Files.getFileStore(
                    plan.pathPolicy().canonicalBackupRoot()).getUsableSpace();
            if (currentAvailable < plan.requiredAvailableSpaceBytes()) {
                return refused(plan, "current backup space is insufficient");
            }
            BackupManifest current = BackupManifestBuilder.buildDirectory(source);
            if (!current.equals(plan.manifest())) return refused(plan, "source manifest changed");
            staging = target.resolveSibling(target.getFileName() + ".staging-" + plan.backupIdentity());
            staging = plan.pathPolicy().canonicalTarget(staging);
            if (Files.exists(staging)) return failed(plan, "staging target already exists");
            Files.createDirectory(staging);
            copyManifest(source, staging, plan.manifest());
            if (!BackupManifestBuilder.buildDirectory(staging).equals(plan.manifest())) {
                cleanup(staging);
                return failed(plan, "staging manifest mismatch");
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                cleanup(staging);
                return failed(plan, "atomic move unsupported");
            }
            return success(plan, "isolated backup created");
        } catch (IOException exception) {
            safeCleanup(staging, plan);
            return failed(plan, "backup failed: " + exception.getClass().getSimpleName());
        }
    }

    public BackupOperationResult restoreBackup(BackupPlan plan) {
        BackupVerification verification = verifier.verify(plan);
        if (!verification.valid()) return refused(plan, verification.failures().toString());
        try {
            Path source = plan.pathPolicy().canonicalSource(plan.sourceWorldRoot());
            Path target = plan.pathPolicy().canonicalTarget(plan.backupTarget());
            if (!Files.isDirectory(target)) return failed(plan, "verified backup target is missing");
            BackupManifest backupManifest = BackupManifestBuilder.buildDirectory(target);
            if (!backupManifest.equals(plan.manifest())) {
                return refused(plan, "backup manifest changed");
            }
            removeUnexpectedFiles(source, plan.manifest());
            copyManifest(target, source, plan.manifest());
            BackupManifest restored = BackupManifestBuilder.buildDirectory(source);
            if (!restored.equals(plan.manifest())) return failed(plan, "restore fingerprint mismatch");
            return success(plan, "isolated restore verified");
        } catch (IOException exception) {
            return failed(plan, "restore failed: " + exception.getClass().getSimpleName());
        }
    }

    /** Restores into a new disposable backup-root child and never writes the live source. */
    public BackupOperationResult verifyRestoreDrill(BackupPlan plan, Path restoreTarget) {
        BackupVerification verification = verifier.verify(plan);
        if (!verification.valid()) return refused(plan, verification.failures().toString());
        Path staging = null;
        try {
            Path source = plan.pathPolicy().canonicalSource(plan.sourceWorldRoot());
            Path backup = plan.pathPolicy().canonicalTarget(plan.backupTarget());
            Path restore = plan.pathPolicy().canonicalTarget(restoreTarget);
            if (restore.equals(backup) || restore.startsWith(backup) || backup.startsWith(restore)) {
                return refused(plan, "restore drill target overlaps the backup target");
            }
            if (!Files.isDirectory(backup)) return failed(plan, "verified backup target is missing");
            if (Files.exists(restore)) return failed(plan, "restore drill target already exists");
            BackupManifest sourceBefore = BackupManifestBuilder.buildDirectory(source);
            BackupManifest backupManifest = BackupManifestBuilder.buildDirectory(backup);
            if (!sourceBefore.equals(plan.manifest())) {
                return refused(plan, "live source changed before restore drill");
            }
            if (!backupManifest.equals(plan.manifest())) {
                return refused(plan, "backup manifest changed before restore drill");
            }
            staging = restore.resolveSibling(
                    restore.getFileName() + ".staging-" + plan.backupIdentity());
            staging = plan.pathPolicy().canonicalTarget(staging);
            if (Files.exists(staging)) return failed(plan, "restore staging target already exists");
            Files.createDirectory(staging);
            copyManifest(backup, staging, plan.manifest());
            if (!BackupManifestBuilder.buildDirectory(staging).equals(plan.manifest())) {
                cleanup(staging);
                return failed(plan, "restore staging manifest mismatch");
            }
            try {
                Files.move(staging, restore, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                cleanup(staging);
                return failed(plan, "atomic restore publication unsupported");
            }
            BackupManifest restored = BackupManifestBuilder.buildDirectory(restore);
            BackupManifest sourceAfter = BackupManifestBuilder.buildDirectory(source);
            if (!restored.equals(plan.manifest())) {
                return failed(plan, "restore drill fingerprint mismatch");
            }
            if (!sourceAfter.equals(sourceBefore)) {
                return failed(plan, "live source changed during restore drill");
            }
            return success(plan, "isolated disposable restore drill verified");
        } catch (IOException exception) {
            safeCleanup(staging, plan);
            return failed(plan, "restore drill failed: " + exception.getClass().getSimpleName());
        }
    }

    private static void copyManifest(Path from, Path to, BackupManifest manifest) throws IOException {
        for (BackupFileEntry entry : manifest.entries()) {
            Path source = safeResolve(from, entry.relativePath());
            Path target = safeResolve(to, entry.relativePath());
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void removeUnexpectedFiles(Path root, BackupManifest manifest) throws IOException {
        Set<String> expected = new HashSet<>();
        for (BackupFileEntry entry : manifest.entries()) expected.add(entry.relativePath());
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(root)) continue;
                if (Files.isSymbolicLink(path)) {
                    Files.delete(path);
                } else if (Files.isRegularFile(path)) {
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (!expected.contains(relative)) Files.delete(path);
                } else if (Files.isDirectory(path) && isEmpty(path)) {
                    Files.delete(path);
                }
            }
        }
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("manifest path escaped root");
        return resolved;
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var stream = Files.list(directory)) { return stream.findAny().isEmpty(); }
    }

    private static void cleanup(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void safeCleanup(Path staging, BackupPlan plan) {
        if (staging == null) return;
        try {
            Path safe = plan.pathPolicy().canonicalTarget(staging);
            cleanup(safe);
        } catch (IOException ignored) {
            // The source remains untouched; unknown staging is deliberately not deleted.
        }
    }

    private static BackupOperationResult success(BackupPlan plan, String detail) {
        return result(plan, BackupOperationStatus.SUCCESS, detail);
    }

    private static BackupOperationResult refused(BackupPlan plan, String detail) {
        return result(plan, BackupOperationStatus.REFUSED, detail);
    }

    private static BackupOperationResult failed(BackupPlan plan, String detail) {
        return result(plan, BackupOperationStatus.FAILED, detail);
    }

    private static BackupOperationResult result(
            BackupPlan plan, BackupOperationStatus status, String detail) {
        return new BackupOperationResult(
                status, plan.backupIdentity(), plan.manifest().manifestHash(), detail);
    }
}
