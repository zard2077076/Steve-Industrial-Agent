package dev.stevecreate.agent.core.formalbackup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.survey.FormalFingerprintPolicy;
import dev.stevecreate.agent.core.survey.FormalReadOnlyPolicy;
import dev.stevecreate.agent.core.survey.FormalSaveDiscovery;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprint;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprintService;
import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import dev.stevecreate.agent.core.survey.FormalWorldReadOnlyGuard;
import dev.stevecreate.agent.core.survey.NbtReadLimits;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalBackupRestoreDrillTest {
    private static final String RUNTIME = "7".repeat(64);
    private static final String GIT = "8".repeat(40);
    private static final Instant PLANNED = Instant.parse("2026-07-20T06:00:00Z");
    private static final Instant BACKED_UP = Instant.parse("2026-07-20T06:01:00Z");
    private static final Instant RESTORE_REQUESTED = Instant.parse("2026-07-20T06:02:00Z");
    private static final Instant RESTORED = Instant.parse("2026-07-20T06:03:00Z");

    @TempDir Path temp;
    private Path platform;
    private Path instance;
    private Path source;
    private Path repository;
    private Path backupRoot;
    private Path restoreRoot;
    private FormalWorldIdentity world;
    private FormalWorldFingerprint sourceFingerprint;
    private FormalBackupCopyResult backup;
    private FormalBackupDestinationPlan plan;
    private FormalBackupVerificationRequest verificationRequest;
    private BackupVerificationResult verification;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        platform = Files.createDirectories(temp.resolve("formal-platform"));
        instance = Files.createDirectories(platform.resolve("instances/pack"));
        Path saves = Files.createDirectories(instance.resolve("saves"));
        source = Files.createDirectories(saves.resolve("world-fixture"));
        write("level.dat", gzip(levelNbt()));
        write("region/r.0.0.mca", "region-fixture".getBytes(StandardCharsets.UTF_8));
        write("playerdata/user.dat", "opaque-player-fixture".getBytes(StandardCharsets.UTF_8));
        write("session.lock", "excluded-lock".getBytes(StandardCharsets.UTF_8));

        repository = Files.createDirectories(temp.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        backupRoot = Files.createDirectories(repository.resolve("work/formal-backups"));
        restoreRoot = Files.createDirectories(repository.resolve("work/formal-restore-drills"));
        FormalWorldReadOnlyGuard discoveryGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-discovery-v1", "world:unselected", instance, saves,
                repository.resolve("work/discovery-audit")));
        world = new FormalSaveDiscovery(discoveryGuard, NbtReadLimits.minecraft1201Defaults())
                .discover(instance).selectedCandidate().orElseThrow().worldIdentity();

        FormalWorldReadOnlyGuard fingerprintGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", world.value(), instance, source,
                repository.resolve("work/fingerprint-audit")));
        FormalFingerprintPolicy fingerprintPolicy = new FormalFingerprintPolicy(
                100, Set.of("level.dat"), true);
        sourceFingerprint = new FormalWorldFingerprintService().capture(
                fingerprintGuard, world.value(), fingerprintPolicy);
        FormalBackupDestinationRequest destination = new FormalBackupDestinationRequest(
                world.value(), world.value(), platform, instance, source, repository, backupRoot,
                sourceFingerprint.worldFingerprint(), RUNTIME, sourceFingerprint.fileCount(),
                sourceFingerprint.totalBytes(), 8_192, PLANNED, "formal-backup-v1",
                "steve-agent-0.1", GIT);
        plan = new FormalBackupDestinationPolicy(ignored -> Long.MAX_VALUE / 2)
                .plan(destination).plan().orElseThrow();
        backup = new FormalBackupService(Clock.fixed(BACKED_UP, ZoneOffset.UTC), () -> {})
                .create(new FormalBackupCopyRequest(plan, fingerprintGuard, fingerprintPolicy,
                        new FormalBackupQuiescenceEvidence(PLANNED, "test-quiescence-v1",
                                List.of(), List.of(), true, true),
                        100, Math.multiplyExact(sourceFingerprint.totalBytes(), 2)))
                .result().orElseThrow();
        verificationRequest = new FormalBackupVerificationRequest(
                platform, repository, backupRoot, backup.completedTarget(), world, plan);
        verification = new FormalBackupVerifier().verify(verificationRequest).result().orElseThrow();
    }

    @Test
    void verifiesCompletedIdentityAndRestoresExactWorldOfflineUnderWork() throws Exception {
        FormalWorldFingerprint before = sourceFingerprint;
        FormalRestoreDrillRequest request = new FormalRestoreDrillRequest(
                verificationRequest, verification, restoreRoot, RESTORE_REQUESTED,
                NbtReadLimits.minecraft1201Defaults());

        RestoreDrillResult result = new FormalRestoreDrillService(
                new FormalBackupVerifier(), Clock.fixed(RESTORED, ZoneOffset.UTC))
                .run(request).result().orElseThrow();

        assertThat(verification.identity().backupIdentity()).isEqualTo(backup.backupIdentity());
        assertThat(verification.identity().worldIdentity()).isEqualTo(world);
        assertThat(verification.identity().sourceFingerprint()).isEqualTo(before.worldFingerprint());
        assertThat(verification.identity().runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(verification.identity().backupTimestamp()).isEqualTo(BACKED_UP);
        assertThat(verification.identity().manifestHash()).isEqualTo(backup.manifest().manifestHash());
        assertThat(verification.identity().fileCount()).isEqualTo(backup.manifest().entries().size());
        assertThat(verification.identity().totalBytes()).isEqualTo(backup.manifest().totalBytes());
        assertThat(verification.identity().completionState()).isEqualTo(FormalBackupCompletionState.COMPLETED);
        assertThat(verification.checks()).containsExactly(FormalBackupVerificationCheck.values());
        assertThat(verification.verified()).isTrue();
        assertThat(verification.privateContentParsed()).isFalse();
        assertThat(verification.formalSourceRead()).isFalse();
        assertThat(verification.formalSourceWrite()).isFalse();

        assertThat(result.restoreDrillIdentity()).matches("restore-drill:[0-9a-f]{64}");
        assertThat(result.disposableRestoreTarget()).isDirectory().startsWith(restoreRoot);
        assertThat(result.restoredManifest()).isEqualTo(backup.manifest());
        assertThat(result.restoredWorldIdentity()).isEqualTo(world);
        assertThat(result.checks()).containsExactly(FormalRestoreDrillCheck.values());
        assertThat(result.passed()).isTrue();
        assertThat(result.minecraftStarted()).isFalse();
        assertThat(result.formalSourceRead()).isFalse();
        assertThat(result.formalSourceWrite()).isFalse();
        assertThat(result.privateContentParsed()).isFalse();
        assertThat(result.disposableCopyRetained()).isTrue();
        assertThat(source.resolve("level.dat")).isRegularFile();
        assertThat(new FormalWorldFingerprintService().capture(
                new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                        "formal-survey-v1", world.value(), instance, source,
                        repository.resolve("work/post-restore-source-audit"))),
                world.value(), new FormalFingerprintPolicy(100, Set.of("level.dat"), true)))
                .isEqualTo(before);

        BackupEvidence evidence = new BackupEvidence(verification, result,
                new BackupRetentionPolicy(3, java.time.Duration.ofDays(30), true, false, false),
                false, false);
        assertThat(evidence.deploymentAuthorityGranted()).isFalse();
        assertThat(evidence.formalExecutionAllowed()).isFalse();
    }

    @Test
    void arbitraryRestoreRootIsTypedAndCreatesNoCopy() throws Exception {
        Path arbitrary = Files.createDirectories(temp.resolve("arbitrary-restore"));
        FormalRestoreDrillRequest request = new FormalRestoreDrillRequest(
                verificationRequest, verification, arbitrary, RESTORE_REQUESTED,
                NbtReadLimits.minecraft1201Defaults());

        FormalBackupFailure failure = new FormalRestoreDrillService(
                new FormalBackupVerifier(), Clock.fixed(RESTORED, ZoneOffset.UTC))
                .run(request).failure().orElseThrow();

        assertThat(failure.code()).isEqualTo(FormalBackupFailureCode.RESTORE_TARGET_UNSAFE);
        assertThat(failure.stage()).isEqualTo(FormalBackupStage.RESTORE_DRILL);
        try (var stream = Files.list(arbitrary)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void changedCompletedBackupFailsFreshVerificationBeforeRestore() throws Exception {
        Files.writeString(backup.completedTarget().resolve("region/r.0.0.mca"), "tampered");
        FormalRestoreDrillRequest request = new FormalRestoreDrillRequest(
                verificationRequest, verification, restoreRoot, RESTORE_REQUESTED,
                NbtReadLimits.minecraft1201Defaults());

        FormalBackupFailure failure = new FormalRestoreDrillService(
                new FormalBackupVerifier(), Clock.fixed(RESTORED, ZoneOffset.UTC))
                .run(request).failure().orElseThrow();

        assertThat(failure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED);
        assertThat(failure.evidence()).contains("freshVerificationMatch=false");
        try (var stream = Files.list(restoreRoot)) {
            assertThat(stream).isEmpty();
        }
    }

    private void write(String relative, byte[] content) throws Exception {
        Path file = source.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_100_000L + relative.length()));
    }

    private static byte[] levelNbt() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            compound(out, "Data", data -> {
                string(data, "LevelName", "Formal Restore Fixture");
                integer(data, "DataVersion", 3_465);
                longNumber(data, "LastPlayed", 1_700_000_000_000L);
                compound(data, "Version", version -> string(version, "Name", "1.20.1"));
                compound(data, "WorldGenSettings", worldGen ->
                        compound(worldGen, "dimensions", dimensions -> {
                            compound(dimensions, "minecraft:overworld", ignored -> {});
                            compound(dimensions, "minecraft:the_nether", ignored -> {});
                            compound(dimensions, "minecraft:the_end", ignored -> {});
                        }));
            });
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(value);
        }
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }

    private static void integer(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void longNumber(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(4);
        out.writeUTF(name);
        out.writeLong(value);
    }

    private static void compound(DataOutputStream out, String name, IoConsumer body) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
        body.accept(out);
        out.writeByte(0);
    }

    @FunctionalInterface
    private interface IoConsumer {
        void accept(DataOutputStream output) throws IOException;
    }
}
