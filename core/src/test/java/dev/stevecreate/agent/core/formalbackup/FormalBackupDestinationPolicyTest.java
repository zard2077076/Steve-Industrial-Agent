package dev.stevecreate.agent.core.formalbackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalBackupDestinationPolicyTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final String SOURCE = "b".repeat(64);
    private static final String RUNTIME = "c".repeat(64);
    private static final String GIT = "d".repeat(40);
    private static final Instant REQUESTED = Instant.parse("2026-07-20T04:00:00Z");

    @TempDir Path temp;
    private Path platform;
    private Path instance;
    private Path source;
    private Path repository;
    private Path backupRoot;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        platform = Files.createDirectories(temp.resolve("formal-platform"));
        instance = Files.createDirectories(platform.resolve("instances/pack"));
        source = Files.createDirectories(instance.resolve("saves/world"));
        Files.writeString(source.resolve("level.dat"), "source fixture");
        repository = Files.createDirectories(temp.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        backupRoot = Files.createDirectories(repository.resolve("work/formal-backups"));
    }

    @Test
    void deterministicPlanBindsExactRootsIdentitiesCapacityAndZeroAuthority() throws Exception {
        long beforeEntries = countEntries(repository);
        FormalBackupDestinationPolicy policy = new FormalBackupDestinationPolicy(ignored -> 10_000);

        FormalBackupDestinationPlan first = policy.plan(request()).plan().orElseThrow();
        FormalBackupDestinationPlan second = policy.plan(request()).plan().orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(first.planIdentity()).matches("backup-plan:[0-9a-f]{64}");
        assertThat(first.worldIdentity()).isEqualTo(WORLD);
        assertThat(first.sourceFingerprint()).isEqualTo(SOURCE);
        assertThat(first.runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(first.canonicalSourceRoot()).isEqualTo(source.toRealPath());
        assertThat(first.canonicalBackupRoot()).isEqualTo(backupRoot.toRealPath());
        assertThat(first.plannedTarget().startsWith(backupRoot.toRealPath())).isTrue();
        assertThat(first.plannedStaging().startsWith(backupRoot.toRealPath())).isTrue();
        assertThat(first.plannedTarget()).doesNotExist();
        assertThat(first.plannedStaging()).doesNotExist();
        assertThat(first.requiredSpaceBytes()).isEqualTo(1_250);
        assertThat(first.observedUsableSpaceBytes()).isEqualTo(10_000);
        assertThat(first.gitIgnoredOutput()).isTrue();
        assertThat(first.outsideFormalPlatform()).isTrue();
        assertThat(first.overwriteAllowed()).isFalse();
        assertThat(first.cloudUploadAllowed()).isFalse();
        assertThat(first.backupCreationExecuted()).isFalse();
        assertThat(first.formalWorldWriteAllowed()).isFalse();
        assertThat(countEntries(repository)).isEqualTo(beforeEntries);
    }

    @Test
    void worldMismatchAndArbitraryDestinationFailBeforeCreatingAnything() throws Exception {
        FormalBackupDestinationPolicy policy = new FormalBackupDestinationPolicy(ignored -> 10_000);
        FormalBackupDestinationRequest mismatch = copy(request(), "world:" + "e".repeat(64),
                repository, backupRoot);
        Path arbitrary = Files.createDirectories(temp.resolve("arbitrary-backups"));
        FormalBackupDestinationRequest unsafe = copy(request(), WORLD, repository, arbitrary);

        FormalBackupFailure mismatchFailure = policy.plan(mismatch).failure().orElseThrow();
        FormalBackupFailure unsafeFailure = policy.plan(unsafe).failure().orElseThrow();

        assertThat(mismatchFailure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_SOURCE_WORLD_MISMATCH);
        assertThat(unsafeFailure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_DESTINATION_UNSAFE);
        assertComplete(mismatchFailure);
        assertComplete(unsafeFailure);
        try (var stream = Files.list(backupRoot)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void destinationInsideFormalPlatformIsRefusedEvenWhenItMatchesRepositoryShape() throws Exception {
        Path unsafeRepository = Files.createDirectories(platform.resolve("repository"));
        Files.createDirectory(unsafeRepository.resolve(".git"));
        Path unsafeBackup = Files.createDirectories(unsafeRepository.resolve("work/formal-backups"));
        FormalBackupDestinationRequest inside = copy(request(), WORLD, unsafeRepository, unsafeBackup);

        FormalBackupFailure failure = new FormalBackupDestinationPolicy(ignored -> 10_000)
                .plan(inside).failure().orElseThrow();

        assertThat(failure.code())
                .isEqualTo(FormalBackupFailureCode.BACKUP_DESTINATION_INSIDE_FORMAL_ROOT);
        assertThat(failure.canonicalDestinationPath()).startsWith(identity(platform));
        assertComplete(failure);
    }

    @Test
    void insufficientSpaceAndExistingImmutableTargetAreTyped() throws Exception {
        FormalBackupFailure noSpace = new FormalBackupDestinationPolicy(ignored -> 1_249)
                .plan(request()).failure().orElseThrow();
        FormalBackupDestinationPolicy enough = new FormalBackupDestinationPolicy(ignored -> 10_000);
        FormalBackupDestinationPlan planned = enough.plan(request()).plan().orElseThrow();
        Files.createDirectories(planned.plannedTarget());

        FormalBackupFailure exists = enough.plan(request()).failure().orElseThrow();

        assertThat(noSpace.code()).isEqualTo(FormalBackupFailureCode.BACKUP_DISK_SPACE_INSUFFICIENT);
        assertThat(noSpace.requiredSpaceBytes()).isEqualTo(1_250);
        assertThat(noSpace.actualSpaceBytes()).isEqualTo(1_249);
        assertThat(exists.code()).isEqualTo(FormalBackupFailureCode.BACKUP_ALREADY_EXISTS);
        assertThat(exists.evidence()).contains("targetExists=true", "stagingExists=false");
        assertComplete(noSpace);
        assertComplete(exists);
    }

    @Test
    void symbolicOrReparseBackupRootFailsClosed() throws Exception {
        Path linkRepository = Files.createDirectories(temp.resolve("link-repository"));
        Files.createDirectory(linkRepository.resolve(".git"));
        Path work = Files.createDirectories(linkRepository.resolve("work"));
        Path actual = Files.createDirectories(temp.resolve("actual-backups"));
        Path link = work.resolve("formal-backups");
        FormalBackupFailure failure;
        try {
            Files.createSymbolicLink(link, actual);
            FormalBackupDestinationRequest linked = copy(request(), WORLD, linkRepository, link);
            failure = new FormalBackupDestinationPolicy(ignored -> 10_000)
                    .plan(linked).failure().orElseThrow();
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            FormalBackupDestinationRequest missing = copy(request(), WORLD, linkRepository, link);
            failure = new FormalBackupDestinationPolicy(ignored -> 10_000)
                    .plan(missing).failure().orElseThrow();
        }

        assertThat(failure.code()).isEqualTo(FormalBackupFailureCode.BACKUP_REPARSE_POINT_FORBIDDEN);
        assertComplete(failure);
    }

    @Test
    void failureVocabularyContainsEveryRequiredStageBCode() {
        assertThat(Arrays.stream(FormalBackupFailureCode.values()).map(Enum::name)).containsExactly(
                "BACKUP_SOURCE_WORLD_MISMATCH", "BACKUP_DESTINATION_UNSAFE",
                "BACKUP_DESTINATION_INSIDE_FORMAL_ROOT", "BACKUP_REPARSE_POINT_FORBIDDEN",
                "BACKUP_DISK_SPACE_INSUFFICIENT", "BACKUP_SOURCE_CHANGED_DURING_COPY",
                "BACKUP_COPY_FAILED", "BACKUP_MANIFEST_INVALID", "BACKUP_INCOMPLETE",
                "BACKUP_VERIFICATION_FAILED", "BACKUP_ALREADY_EXISTS", "RESTORE_TARGET_UNSAFE",
                "RESTORE_DRILL_FAILED", "RESTORE_FINGERPRINT_MISMATCH", "DEPLOYMENT_CANDIDATE_MISSING",
                "DEPLOYMENT_CANDIDATE_AMBIGUOUS", "FORMAL_REGION_NOT_SELECTED",
                "APPROVAL_REQUEST_INVALID", "APPROVAL_DECISION_REQUIRED", "FORMAL_APPROVAL_NOT_GRANTED",
                "FORMAL_RESOURCE_INVENTORY_NOT_AUTHORIZED", "CLAIM_PERMISSION_UNKNOWN",
                "FORMAL_WORLD_EXECUTION_FORBIDDEN");
    }

    @Test
    void planIdentityChangesWithEveryRequiredSourceRuntimeTimePolicyAndGitBinding() {
        FormalBackupDestinationPolicy policy = new FormalBackupDestinationPolicy(ignored -> 10_000);
        String base = policy.plan(request()).plan().orElseThrow().planIdentity();

        assertThat(java.util.stream.Stream.of(
                policy.plan(request("e".repeat(64), RUNTIME, REQUESTED,
                        "formal-backup-v1", GIT)).plan().orElseThrow().planIdentity(),
                policy.plan(request(SOURCE, "f".repeat(64), REQUESTED,
                        "formal-backup-v1", GIT)).plan().orElseThrow().planIdentity(),
                policy.plan(request(SOURCE, RUNTIME, REQUESTED.plusSeconds(1),
                        "formal-backup-v1", GIT)).plan().orElseThrow().planIdentity(),
                policy.plan(request(SOURCE, RUNTIME, REQUESTED,
                        "formal-backup-v2", GIT)).plan().orElseThrow().planIdentity(),
                policy.plan(request(SOURCE, RUNTIME, REQUESTED,
                        "formal-backup-v1", "1".repeat(40))).plan().orElseThrow().planIdentity()))
                .doesNotContain(base).doesNotHaveDuplicates();
    }

    private FormalBackupDestinationRequest request() {
        return request(SOURCE, RUNTIME, REQUESTED, "formal-backup-v1", GIT);
    }

    private FormalBackupDestinationRequest request(
            String sourceFingerprint,
            String runtimeFingerprint,
            Instant requested,
            String policyVersion,
            String gitHead) {
        return new FormalBackupDestinationRequest(WORLD, WORLD, platform, instance, source,
                repository, backupRoot, sourceFingerprint, runtimeFingerprint, 10, 1_000, 250,
                requested, policyVersion, "steve-agent-0.1", gitHead);
    }

    private static FormalBackupDestinationRequest copy(
            FormalBackupDestinationRequest source,
            String observedWorld,
            Path repository,
            Path backupRoot) {
        return new FormalBackupDestinationRequest(source.expectedWorldIdentity(), observedWorld,
                source.formalPlatformRoot(), source.formalInstanceRoot(), source.sourceWorldRoot(),
                repository, backupRoot, source.sourceFingerprint(), source.runtimeFingerprint(),
                source.sourceFileCount(), source.estimatedSourceBytes(), source.safetyMarginBytes(),
                source.requestedAt(), source.policyVersion(), source.toolVersion(), source.gitHead());
    }

    private static void assertComplete(FormalBackupFailure failure) {
        assertThat(failure.stage()).isEqualTo(FormalBackupStage.DESTINATION_POLICY);
        assertThat(failure.worldIdentity()).isNotBlank();
        assertThat(failure.backupIdentity()).isNotBlank();
        assertThat(failure.candidateIdentity()).isNotBlank();
        assertThat(failure.canonicalSourcePath()).isNotBlank();
        assertThat(failure.canonicalDestinationPath()).isNotBlank();
        assertThat(failure.sourceFingerprint()).isEqualTo(SOURCE);
        assertThat(failure.runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(failure.manifestIdentity()).isNotBlank();
        assertThat(failure.approvalState()).isNotBlank();
        assertThat(failure.evidence()).isNotEmpty();
        assertThat(failure.reason()).isNotBlank();
        assertThat(failure.safeNextStep()).isNotBlank();
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static long countEntries(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.count();
        }
    }
}
