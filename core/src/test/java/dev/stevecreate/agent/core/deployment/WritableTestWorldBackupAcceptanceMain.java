package dev.stevecreate.agent.core.deployment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;

/** Explicit development-only entry point for the real IWP-05 closed-world backup. */
public final class WritableTestWorldBackupAcceptanceMain {
    private static final String MARKER_FILE = ".steve-industrial-writable-test-world";
    private static final String MARKER_VALUE = "ISOLATED_WRITABLE_TEST_WORLD";

    private WritableTestWorldBackupAcceptanceMain() {}

    public static void main(String[] args) throws Exception {
        Path project = realDirectory(property("iwpProjectRoot"));
        Path work = realDirectory(project.resolve("work").toString());
        Path instance = realDirectory(property("iwpInstanceRoot"));
        Path world = realDirectory(property("iwpWorldRoot"));
        Path formal = realDirectory(property("iwpFormalRoot"));
        Path backupRoot = realDirectory(property("iwpBackupRoot"));
        Path runRoot = realDirectory(property("iwpRunRoot"));
        String worldIdentity = property("iwpWorldIdentity");
        String backupIdentity = property("iwpBackupIdentity");

        Path expectedInstanceRoot = work.resolve("isolated-player").toRealPath();
        Path expectedSaves = instance.resolve("run/saves").toRealPath();
        if (!instance.startsWith(expectedInstanceRoot) || !world.startsWith(expectedSaves)
                || world.startsWith(formal) || backupRoot.startsWith(formal)
                || runRoot.startsWith(formal) || !runRoot.startsWith(backupRoot)) {
            throw new IllegalStateException("IWP-05 path isolation failed before source traversal");
        }
        Path marker = world.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)
                || !MARKER_VALUE.equals(Files.readString(marker, StandardCharsets.US_ASCII).trim())) {
            throw new IllegalStateException("IWP-05 required world marker is absent or invalid");
        }

        BackupPathPolicy policy = new BackupPathPolicy(
                work, backupRoot, Set.of(formal, work.resolve("formal-backups")));
        Path backupTarget = runRoot.resolve("world-backup");
        Path restoreTarget = runRoot.resolve("restore-drill");
        BackupPlan plan = new BackupPlanner().planIsolated(
                backupIdentity, worldIdentity, world, backupTarget, policy,
                backupIdentity, BackupApprovalState.APPROVED_TEST_ONLY);
        BackupManifest sourcePre = plan.manifest();
        IsolatedBackupService service = new IsolatedBackupService(new BackupVerifier());
        BackupOperationResult created = service.createBackup(plan);
        requireSuccess(created, "backup");
        BackupOperationResult restored = service.verifyRestoreDrill(plan, restoreTarget);
        requireSuccess(restored, "restore drill");

        BackupManifest sourcePost = new BackupManifestBuilder().buildIsolated(world, policy);
        BackupManifest backupReadback = BackupManifestBuilder.buildDirectory(backupTarget);
        BackupManifest restoreReadback = BackupManifestBuilder.buildDirectory(restoreTarget);
        if (!sourcePre.equals(sourcePost) || !sourcePre.equals(backupReadback)
                || !sourcePre.equals(restoreReadback)) {
            throw new IllegalStateException("IWP-05 source/backup/restore manifest mismatch");
        }

        writeManifest(runRoot.resolve("manifest.tsv"), sourcePre);
        String completedAt = Instant.now().toString();
        String completion = "{\n"
                + json("schema", "steve-industrial:iwp-backup-completed/v1") + ",\n"
                + json("backupIdentity", backupIdentity) + ",\n"
                + json("worldIdentity", worldIdentity) + ",\n"
                + json("manifestHash", sourcePre.manifestHash()) + ",\n"
                + "  \"fileCount\": " + sourcePre.entries().size() + ",\n"
                + "  \"totalBytes\": " + sourcePre.totalBytes() + ",\n"
                + json("completedAtUtc", completedAt) + "\n} \n";
        Files.writeString(runRoot.resolve("completed.json"), completion, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String acceptance = "{\n"
                + json("schema", "steve-industrial:iwp-backup-acceptance/v1") + ",\n"
                + json("backupIdentity", backupIdentity) + ",\n"
                + json("worldIdentity", worldIdentity) + ",\n"
                + json("sourceWorld", world.toString()) + ",\n"
                + json("backupTarget", backupTarget.toString()) + ",\n"
                + json("restoreTarget", restoreTarget.toString()) + ",\n"
                + json("sourcePreManifestHash", sourcePre.manifestHash()) + ",\n"
                + json("sourcePostManifestHash", sourcePost.manifestHash()) + ",\n"
                + "  \"fileCount\": " + sourcePre.entries().size() + ",\n"
                + "  \"totalBytes\": " + sourcePre.totalBytes() + ",\n"
                + "  \"backupValid\": true,\n"
                + "  \"restoreDrillPass\": true,\n"
                + "  \"sourceUnchanged\": true,\n"
                + "  \"formalWorldTouched\": false,\n"
                + "  \"minecraftStarted\": false\n} \n";
        Files.writeString(runRoot.resolve("acceptance.json"), acceptance, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("IWP05_BACKUP_ACCEPTANCE_PASS backupIdentity=" + backupIdentity
                + " manifest=" + sourcePre.manifestHash()
                + " files=" + sourcePre.entries().size()
                + " bytes=" + sourcePre.totalBytes()
                + " restoreDrill=true sourceUnchanged=true formalWorldTouched=false");
    }

    private static void requireSuccess(BackupOperationResult result, String operation) {
        if (result.status() != BackupOperationStatus.SUCCESS) {
            throw new IllegalStateException("IWP-05 " + operation + " failed: " + result.detail());
        }
    }

    private static void writeManifest(Path path, BackupManifest manifest) throws Exception {
        StringBuilder value = new StringBuilder("relativePath\tsizeBytes\tsha256\n");
        for (BackupFileEntry entry : manifest.entries()) {
            value.append(entry.relativePath()).append('\t').append(entry.sizeBytes())
                    .append('\t').append(entry.sha256()).append('\n');
        }
        Files.writeString(path, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Path realDirectory(String value) throws Exception {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Required ordinary directory is unavailable: " + path);
        }
        return path.toRealPath();
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String json(String name, String value) {
        return "  \"" + escape(name) + "\": \"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
