package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupPlanVerifierTest {
    @TempDir Path temp;
    private Path isolatedRoot;
    private Path sourceWorld;
    private Path backupRoot;
    private Path forbiddenRoot;
    private BackupPathPolicy pathPolicy;

    @BeforeEach
    void setUp() throws Exception {
        isolatedRoot = Files.createDirectories(temp.resolve("isolated"));
        sourceWorld = Files.createDirectories(isolatedRoot.resolve("world"));
        backupRoot = Files.createDirectories(isolatedRoot.resolve("backups"));
        forbiddenRoot = Files.createDirectories(temp.resolve("formal-reference"));
        Files.createDirectories(sourceWorld.resolve("region"));
        Files.writeString(sourceWorld.resolve("level.dat"), "level-original");
        Files.writeString(sourceWorld.resolve("region/r.0.0.mca"), "region-original");
        Files.writeString(forbiddenRoot.resolve("sentinel.txt"), "formal-untouched");
        pathPolicy = new BackupPathPolicy(isolatedRoot, backupRoot, Set.of(forbiddenRoot));
    }

    @Test
    void plannerCalculatesCompleteManifestCapacityStrategiesAndApprovalRequirement() throws Exception {
        BackupPlan plan = plan("backup-1", backupRoot.resolve("backup-1"));
        BackupVerification verification = new BackupVerifier().verify(plan);

        assertThat(verification.valid()).isTrue();
        assertThat(verification.failures()).isEmpty();
        assertThat(plan.manifest().state()).isEqualTo(BackupManifestState.COMPLETE);
        assertThat(plan.manifest().hasValidHash()).isTrue();
        assertThat(plan.manifest().entries()).extracting(BackupFileEntry::relativePath)
                .containsExactly("level.dat", "region/r.0.0.mca");
        assertThat(plan.estimatedBackupSizeBytes()).isEqualTo(plan.manifest().totalBytes());
        assertThat(plan.requiredAvailableSpaceBytes()).isGreaterThan(plan.estimatedBackupSizeBytes());
        assertThat(plan.observedAvailableSpaceBytes()).isGreaterThanOrEqualTo(
                plan.requiredAvailableSpaceBytes());
        assertThat(plan.targetStrategy()).isEqualTo(BackupTargetStrategy.ISOLATED_BACKUP_ROOT);
        assertThat(plan.atomicityStrategy()).isEqualTo(
                BackupAtomicityStrategy.STAGING_THEN_ATOMIC_RENAME);
        assertThat(plan.consistencyStrategy()).isEqualTo(
                BackupConsistencyStrategy.QUIESCED_WORLD_SNAPSHOT);
        assertThat(plan.failureHandling()).isEqualTo(
                BackupFailureHandling.ABORT_KEEP_SOURCE_DELETE_STAGING);
        assertThat(plan.approvalRequirement()).isEqualTo(
                BackupApprovalRequirement.EXPLICIT_TEST_ONLY);
        assertThat(plan.approvalState()).isEqualTo(BackupApprovalState.APPROVED_TEST_ONLY);
        assertThat(plan.restoreDrillPlan().verifyManifestBeforeRestore()).isTrue();
        assertThat(plan.restoreDrillPlan().verifyFingerprintAfterRestore()).isTrue();
        assertThat(plan.retentionPolicy()).isEqualTo(
                new BackupRetentionPolicy(3, Duration.ofDays(7), true));
    }

    @Test
    void realIsolatedBackupModificationAndRestoreRoundTripMatchesManifestAndJournal() throws Exception {
        BackupPlan plan = plan("backup-2", backupRoot.resolve("backup-2"));
        IsolatedBackupService service = new IsolatedBackupService(new BackupVerifier());

        BackupOperationResult created = service.createBackup(plan);
        Files.writeString(sourceWorld.resolve("level.dat"), "controlled-modification");
        BackupOperationResult restored = service.restoreBackup(plan);
        BackupManifest afterRestore = new BackupManifestBuilder()
                .buildIsolated(sourceWorld, pathPolicy);

        assertThat(created.status()).isEqualTo(BackupOperationStatus.SUCCESS);
        assertThat(restored.status()).isEqualTo(BackupOperationStatus.SUCCESS);
        assertThat(afterRestore.manifestHash()).isEqualTo(plan.manifest().manifestHash());
        assertThat(Files.readString(sourceWorld.resolve("level.dat"))).isEqualTo("level-original");
        assertThat(plan.journalBackupIdentity()).isEqualTo(plan.backupIdentity());
        assertThat(restored.manifestHash()).isEqualTo(plan.manifest().manifestHash());
    }

    @Test
    void disposableRestoreDrillLeavesTheLiveWorldUntouched() throws Exception {
        BackupPlan plan = plan("backup-drill", backupRoot.resolve("backup-drill"));
        Path drill = backupRoot.resolve("restore-drill");
        IsolatedBackupService service = new IsolatedBackupService(new BackupVerifier());

        BackupOperationResult created = service.createBackup(plan);
        BackupOperationResult restored = service.verifyRestoreDrill(plan, drill);

        assertThat(created.status()).isEqualTo(BackupOperationStatus.SUCCESS);
        assertThat(restored.status()).isEqualTo(BackupOperationStatus.SUCCESS);
        assertThat(new BackupManifestBuilder().buildIsolated(sourceWorld, pathPolicy))
                .isEqualTo(plan.manifest());
        assertThat(new BackupManifestBuilder().buildDirectory(drill))
                .isEqualTo(plan.manifest());
        assertThat(Files.readString(sourceWorld.resolve("level.dat")))
                .isEqualTo("level-original");
    }

    @Test
    void incompleteInterruptedBackupAndInsufficientSpaceAreRefusedBeforeCopy() throws Exception {
        BackupPlan base = plan("backup-3", backupRoot.resolve("backup-3"));
        BackupManifest incomplete = new BackupManifest(
                base.manifest().entries(), base.manifest().totalBytes(),
                base.manifest().manifestHash(), BackupManifestState.INCOMPLETE);
        BackupPlan interrupted = copy(
                base, base.environmentClassification(), base.sourceWorldRoot(),
                backupRoot.resolve("interrupted-partial"), incomplete,
                base.observedAvailableSpaceBytes(), base.journalBackupIdentity());
        BackupPlan noSpace = copy(
                base, base.environmentClassification(), base.sourceWorldRoot(),
                backupRoot.resolve("no-space"), base.manifest(), 0,
                base.journalBackupIdentity());
        IsolatedBackupService service = new IsolatedBackupService(new BackupVerifier());

        assertThat(new BackupVerifier().verify(interrupted).failures())
                .containsExactly(BackupVerificationFailure.MANIFEST_INCOMPLETE);
        assertThat(service.createBackup(interrupted).status())
                .isEqualTo(BackupOperationStatus.REFUSED);
        assertThat(new BackupVerifier().verify(noSpace).failures())
                .containsExactly(BackupVerificationFailure.INSUFFICIENT_SPACE);
        assertThat(service.createBackup(noSpace).status())
                .isEqualTo(BackupOperationStatus.REFUSED);
        assertThat(Files.exists(interrupted.backupTarget())).isFalse();
        assertThat(Files.exists(noSpace.backupTarget())).isFalse();
    }

    @Test
    void journalMismatchAndCorruptManifestFailTyped() throws Exception {
        BackupPlan base = plan("backup-4", backupRoot.resolve("backup-4"));
        BackupManifest corrupt = new BackupManifest(
                base.manifest().entries(), base.manifest().totalBytes(),
                "f".repeat(64), BackupManifestState.COMPLETE);
        BackupPlan invalid = copy(
                base, base.environmentClassification(), base.sourceWorldRoot(),
                base.backupTarget(), corrupt, base.observedAvailableSpaceBytes(), "other-backup");

        assertThat(new BackupVerifier().verify(invalid).failures()).containsExactly(
                BackupVerificationFailure.MANIFEST_HASH_INVALID,
                BackupVerificationFailure.JOURNAL_IDENTITY_MISMATCH);
    }

    @Test
    void isolatedSourceAndTargetMustStayInsideTheirCanonicalPolicyRoots() throws Exception {
        BackupPlan base = plan("backup-paths", backupRoot.resolve("backup-paths"));
        BackupPlan badSource = copy(
                base, WorldEnvironmentType.ISOLATED_TEST_WORLD, forbiddenRoot,
                base.backupTarget(), base.manifest(), base.observedAvailableSpaceBytes(),
                base.journalBackupIdentity());
        BackupPlan badTarget = copy(
                base, WorldEnvironmentType.ISOLATED_TEST_WORLD, base.sourceWorldRoot(),
                forbiddenRoot.resolve("outside-backup-root"), base.manifest(),
                base.observedAvailableSpaceBytes(), base.journalBackupIdentity());

        assertThat(new BackupVerifier().verify(badSource).failures())
                .containsExactly(BackupVerificationFailure.SOURCE_OUTSIDE_ISOLATED_ROOT);
        assertThat(new BackupVerifier().verify(badTarget).failures())
                .containsExactly(BackupVerificationFailure.TARGET_OUTSIDE_BACKUP_ROOT);
    }

    @Test
    void explicitlyDisjointExternalBackupRootRetainsExactSourceAndTargetBoundaries() throws Exception {
        Path externalBackupRoot = Files.createDirectories(temp.resolve("portable-backups"));
        BackupPathPolicy externalPolicy = new BackupPathPolicy(
                isolatedRoot, externalBackupRoot, Set.of(forbiddenRoot),
                BackupRootRelationship.EXTERNAL_DISJOINT);
        BackupPlan plan = new BackupPlanner().planIsolated(
                "backup-external", "world:fixture", sourceWorld,
                externalBackupRoot.resolve("backup-external"), externalPolicy,
                "backup-external", BackupApprovalState.APPROVED_TEST_ONLY);

        assertThat(new BackupVerifier().verify(plan).valid()).isTrue();
        assertThat(externalPolicy.allowsSource(sourceWorld)).isTrue();
        assertThat(externalPolicy.allowsSource(forbiddenRoot)).isFalse();
        assertThat(externalPolicy.allowsTarget(externalBackupRoot.resolve("accepted"))).isTrue();
        assertThat(externalPolicy.allowsTarget(isolatedRoot.resolve("not-a-backup"))).isFalse();

        BackupPathPolicy overlapping = new BackupPathPolicy(
                isolatedRoot, backupRoot, Set.of(forbiddenRoot),
                BackupRootRelationship.EXTERNAL_DISJOINT);
        assertThat(overlapping.allowsSource(sourceWorld)).isFalse();
        assertThat(overlapping.allowsTarget(backupRoot.resolve("rejected"))).isFalse();
    }

    @Test
    void formalOrForbiddenWorldIsRefusedWithoutReadingOrWritingItsFiles() throws Exception {
        BackupPlan base = plan("backup-5", backupRoot.resolve("backup-5"));
        Path formalTarget = forbiddenRoot.resolve("must-not-create");
        BackupPlan formal = copy(
                base, WorldEnvironmentType.FORMAL_PLAYER_WORLD, forbiddenRoot,
                formalTarget, base.manifest(), base.observedAvailableSpaceBytes(),
                base.journalBackupIdentity());

        BackupVerification verification = new BackupVerifier().verify(formal);
        BackupOperationResult result = new IsolatedBackupService(new BackupVerifier())
                .createBackup(formal);

        assertThat(verification.failures())
                .containsExactly(BackupVerificationFailure.ENVIRONMENT_NOT_ISOLATED);
        assertThat(result.status()).isEqualTo(BackupOperationStatus.REFUSED);
        assertThat(Files.exists(formalTarget)).isFalse();
        assertThat(Files.readString(forbiddenRoot.resolve("sentinel.txt")))
                .isEqualTo("formal-untouched");
    }

    private BackupPlan plan(String identity, Path target) throws Exception {
        return new BackupPlanner().planIsolated(
                identity, "world:fixture", sourceWorld, target, pathPolicy, identity,
                BackupApprovalState.APPROVED_TEST_ONLY);
    }

    private static BackupPlan copy(
            BackupPlan source,
            WorldEnvironmentType environment,
            Path sourceWorldRoot,
            Path target,
            BackupManifest manifest,
            long availableSpace,
            String journalIdentity) {
        return new BackupPlan(
                source.backupIdentity(), source.worldIdentity(), environment, sourceWorldRoot,
                target, source.pathPolicy(), source.estimatedBackupSizeBytes(),
                source.requiredAvailableSpaceBytes(), availableSpace, manifest,
                source.targetStrategy(), source.atomicityStrategy(), source.consistencyStrategy(),
                source.restoreDrillPlan(), source.retentionPolicy(), source.failureHandling(),
                source.approvalRequirement(), source.approvalState(), journalIdentity);
    }
}
