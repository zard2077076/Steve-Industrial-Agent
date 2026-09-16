package dev.stevecreate.agent.core.formalbackup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.survey.FormalFingerprintPolicy;
import dev.stevecreate.agent.core.survey.FormalReadOnlyPolicy;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprint;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprintService;
import dev.stevecreate.agent.core.survey.FormalWorldReadOnlyGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalBackupServiceTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final String RUNTIME = "b".repeat(64);
    private static final String GIT = "c".repeat(40);
    private static final Instant REQUESTED = Instant.parse("2026-07-20T05:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-07-20T05:01:00Z");

    @TempDir Path temp;
    private Path platform;
    private Path instance;
    private Path source;
    private Path repository;
    private Path backupRoot;
    private FormalWorldReadOnlyGuard fingerprintGuard;
    private FormalFingerprintPolicy fingerprintPolicy;
    private FormalWorldFingerprint fingerprint;
    private FormalBackupDestinationPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        platform = Files.createDirectories(temp.resolve("formal-platform"));
        instance = Files.createDirectories(platform.resolve("instances/pack"));
        source = Files.createDirectories(instance.resolve("saves/world"));
        write("level.dat", "fixture-level");
        write("region/r.0.0.mca", "fixture-region");
        write("playerdata/private-user.dat", "opaque-player-bytes");
        write("stats/private-user.json", "opaque-stats-bytes");
        write("session.lock", "lock-must-not-copy");
        write("region/invalid.tmp", "temporary-must-not-copy");
        repository = Files.createDirectories(temp.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        backupRoot = Files.createDirectories(repository.resolve("work/formal-backups"));
        Path audit = repository.resolve("work/audit");
        fingerprintGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", WORLD, instance, source, audit));
        fingerprintPolicy = new FormalFingerprintPolicy(100, Set.of("level.dat"), true);
        fingerprint = new FormalWorldFingerprintService().capture(
                fingerprintGuard, WORLD, fingerprintPolicy);
        FormalBackupDestinationRequest request = new FormalBackupDestinationRequest(
                WORLD, WORLD, platform, instance, source, repository, backupRoot,
                fingerprint.worldFingerprint(), RUNTIME, fingerprint.fileCount(),
                fingerprint.totalBytes(), 4_096, REQUESTED, "formal-backup-v1",
                "steve-agent-0.1", GIT);
        plan = new FormalBackupDestinationPolicy(ignored -> Long.MAX_VALUE / 2)
                .plan(request).plan().orElseThrow();
    }

    @Test
    void copiesEveryAllowedFileThroughVerifiedStagingAndPublishesMarker() throws Exception {
        Map<String, SourceMetadata> before = sourceMetadata();
        FormalBackupCopyDecision decision = service(() -> {}).create(copyRequest());

        FormalBackupCopyResult result = decision.result().orElseThrow();
        assertThat(result.backupIdentity()).matches("formal-backup:[0-9a-f]{64}");
        assertThat(result.completedTarget()).isEqualTo(plan.plannedTarget()).isDirectory();
        assertThat(result.completedMarker()).isRegularFile();
        assertThat(plan.plannedTarget().resolve(FormalBackupService.MANIFEST_FILE)).isRegularFile();
        assertThat(plan.plannedStaging()).doesNotExist();
        assertThat(result.sourcePreFingerprint()).isEqualTo(result.sourcePostFingerprint());
        assertThat(result.opaqueSourcePreFingerprint()).isEqualTo(result.opaqueSourcePostFingerprint());
        assertThat(result.manifestVerified()).isTrue();
        assertThat(result.atomicPublish()).isTrue();
        assertThat(result.sourceUnchanged()).isTrue();
        assertThat(result.privateContentParsed()).isFalse();
        assertThat(result.sourceAttributesModified()).isFalse();
        assertThat(result.formalWorldWrite()).isFalse();
        assertThat(result.externalMutation()).isFalse();
        assertThat(sourceMetadata()).isEqualTo(before);

        assertThat(result.manifest().entries()).extracting(FormalBackupFileEntry::relativePath)
                .containsExactly("level.dat", "playerdata/private-user.dat", "region/r.0.0.mca",
                        "stats/private-user.json");
        assertThat(result.manifest().entries())
                .filteredOn(entry -> entry.privacy() == FormalBackupFilePrivacy.OPAQUE_PRIVATE)
                .extracting(FormalBackupFileEntry::relativePath)
                .containsExactly("playerdata/private-user.dat", "stats/private-user.json");
        assertThat(result.manifest().exclusions()).extracting(FormalBackupExcludedEntry::relativePath)
                .containsExactly("region/invalid.tmp", "session.lock");
        assertThat(result.manifest().exclusions()).extracting(FormalBackupExcludedEntry::reason)
                .containsExactly(FormalBackupExclusionReason.INVALID_TEMPORARY_FILE,
                        FormalBackupExclusionReason.ACTIVE_SESSION_LOCK);
        assertThat(plan.plannedTarget().resolve("session.lock")).doesNotExist();
        assertThat(plan.plannedTarget().resolve("region/invalid.tmp")).doesNotExist();
        String marker = Files.readString(result.completedMarker());
        assertThat(marker).contains("\"state\": \"COMPLETED\"", result.backupIdentity(),
                result.manifest().manifestHash()).doesNotContain("playerdata", "private-user");
        assertThat(new FormalWorldBackupManifestCodec().decode(Files.readAllBytes(
                plan.plannedTarget().resolve(FormalBackupService.MANIFEST_FILE))))
                .isEqualTo(result.manifest());
    }

    @Test
    void sourceChangeAfterVerifiedCopyFailsTypedAndCleansOnlyStaging() throws Exception {
        Path changed = source.resolve("region/r.0.0.mca");
        FormalBackupCopyDecision decision = service(() -> Files.writeString(
                changed, "changed-by-test-only", StandardOpenOptionForTest)).create(copyRequest());

        FormalBackupFailure failure = decision.failure().orElseThrow();
        assertThat(failure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_SOURCE_CHANGED_DURING_COPY);
        assertThat(failure.stage()).isEqualTo(FormalBackupStage.VERIFICATION);
        assertThat(failure.evidence()).anyMatch(value -> value.startsWith("opaquePre="));
        assertThat(plan.plannedStaging()).doesNotExist();
        assertThat(plan.plannedTarget()).doesNotExist();
        assertThat(changed).hasContent("changed-by-test-only");
        assertThat(source.resolve("level.dat")).isRegularFile();
    }

    @Test
    void processOrSourceHandleEvidenceMustBeCompleteAndEmptyBeforeAnyCopy() {
        FormalBackupCopyRequest blocked = new FormalBackupCopyRequest(
                plan, fingerprintGuard, fingerprintPolicy,
                new FormalBackupQuiescenceEvidence(REQUESTED, "test-process-scan-v1",
                        java.util.List.of(42L), java.util.List.of("formal-save-handle"), true, true),
                100, Math.multiplyExact(fingerprint.totalBytes(), 2));

        FormalBackupFailure failure = service(() -> {}).create(blocked).failure().orElseThrow();

        assertThat(failure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_COPY_FAILED);
        assertThat(failure.stage()).isEqualTo(FormalBackupStage.SOURCE_PREFLIGHT);
        assertThat(failure.evidence()).contains("formalWorldJavaProcessCount=1", "sourceHandleOwnerCount=1");
        assertThat(plan.plannedStaging()).doesNotExist();
        assertThat(plan.plannedTarget()).doesNotExist();
    }

    @Test
    void manifestRejectsTraversalDuplicateOrderAndTampering() {
        assertThatThrownBy(() -> new FormalBackupFileEntry("../escape", 1, 1,
                "d".repeat(64), FormalBackupFilePrivacy.ORDINARY))
                .isInstanceOf(IllegalArgumentException.class);
        FormalBackupFileEntry first = new FormalBackupFileEntry(
                "a.dat", 1, 1, "d".repeat(64), FormalBackupFilePrivacy.ORDINARY);
        FormalWorldBackupManifest valid = FormalWorldBackupManifest.complete(java.util.List.of(first), java.util.List.of());
        assertThat(valid.complete()).isTrue();
        assertThatThrownBy(() -> new FormalWorldBackupManifest(valid.entries(), valid.exclusions(),
                valid.totalBytes(), "e".repeat(64), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormalWorldBackupManifest(
                java.util.List.of(first, first), java.util.List.of(), 2,
                valid.manifestHash(), true)).isInstanceOf(IllegalArgumentException.class);
    }

    private FormalBackupCopyRequest copyRequest() {
        return new FormalBackupCopyRequest(plan, fingerprintGuard, fingerprintPolicy,
                new FormalBackupQuiescenceEvidence(REQUESTED.minusSeconds(30), "test-process-scan-v1",
                        java.util.List.of(), java.util.List.of(), true, true),
                100, Math.multiplyExact(fingerprint.totalBytes(), 2));
    }

    private FormalBackupService service(FormalBackupService.CopyObserver observer) {
        return new FormalBackupService(Clock.fixed(COMPLETED, ZoneOffset.UTC), observer);
    }

    private void write(String relative, String content) throws Exception {
        Path file = source.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + relative.length()));
    }

    private Map<String, SourceMetadata> sourceMetadata() throws Exception {
        Map<String, SourceMetadata> result = new LinkedHashMap<>();
        try (var stream = Files.walk(source)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparing(path ->
                            source.relativize(path).toString())).toList()) {
                String relative = source.relativize(path).toString().replace('\\', '/');
                result.put(relative, new SourceMetadata(
                        Files.size(path), Files.getLastModifiedTime(path).toMillis()));
            }
        }
        return result;
    }

    private record SourceMetadata(long size, long mtime) {}

    /** Avoids COPY_ATTRIBUTES or any source metadata-writing option in production code. */
    private static final java.nio.file.OpenOption[] StandardOpenOptionForTest = {
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
    };
}
