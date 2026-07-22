package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.formalbackup.BackupVerificationResult;
import dev.stevecreate.agent.core.formalbackup.FormalBackupCompletionState;
import dev.stevecreate.agent.core.formalbackup.FormalBackupFileEntry;
import dev.stevecreate.agent.core.formalbackup.FormalBackupFilePrivacy;
import dev.stevecreate.agent.core.formalbackup.FormalBackupVerificationCheck;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentArtifactAvailability;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidateBatch;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidateInput;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackage;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackager;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidateStatus;
import dev.stevecreate.agent.core.formalbackup.FormalWorldBackupIdentity;
import dev.stevecreate.agent.core.formalbackup.FormalWorldBackupManifest;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import dev.stevecreate.agent.core.survey.CandidateZoneStatus;
import dev.stevecreate.agent.core.survey.SurveyConfidence;
import dev.stevecreate.agent.core.survey.SurveyCoverage;
import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class FormalDeploymentCandidatePackageTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final String RUNTIME = "runtime:deceasedcraft:verified";
    private static final String SNAPSHOT = "snapshot:formal:read-only";
    private static final Instant EXPIRES = Instant.parse("2026-07-21T00:00:00Z");
    private final FormalDeploymentCandidatePackager packager = new FormalDeploymentCandidatePackager();

    @Test
    void blockedRealStyleCandidatesRemainDeterministicRankedAndUnselected() {
        FormalDeploymentCandidateInput lower = blocked(zone('b', 3), "minecraft:gravel", 3);
        FormalDeploymentCandidateInput higher = blocked(zone('c', 9), "create:iron_sheet", 2);

        FormalDeploymentCandidateBatch first = packager.packageBatch(
                WORLD, backup(), List.of(lower, higher), 8);
        FormalDeploymentCandidateBatch reversed = packager.packageBatch(
                WORLD, backup(), List.of(higher, lower), 8);

        assertThat(first).isEqualTo(reversed);
        assertThat(first.candidates()).extracting(candidate -> candidate.candidateZone().totalScore())
                .containsExactly(9, 3);
        assertThat(first.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.artifactAvailability())
                    .isEqualTo(FormalDeploymentArtifactAvailability.BLOCKED_PREVIEW_UNAVAILABLE);
            assertThat(candidate.previewHash()).isEmpty();
            assertThat(candidate.verifiedPhysicalPlanIdentity()).isEmpty();
            assertThat(candidate.riskAssessment()).isEmpty();
            assertThat(candidate.materialPowerMutationBudget()).isEmpty();
            assertThat(candidate.status()).isEqualTo(FormalDeploymentCandidateStatus.PENDING_USER_SELECTION);
            assertThat(candidate.missingAuthorizations()).contains(
                    "USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "FORMAL_PREVIEW_UNAVAILABLE",
                    "VERIFIED_PHYSICAL_PLAN_UNAVAILABLE", "FORMAL_WORLD_EXECUTION_FORBIDDEN");
            assertThat(candidate.automaticallySelected()).isFalse();
            assertThat(candidate.approvalCreated()).isFalse();
            assertThat(candidate.executionAllowed()).isFalse();
        });
        assertThat(first.userSelectedCandidateIdentity()).isEmpty();
        assertThat(first.topCandidateAutomaticallySelected()).isFalse();
        assertThat(first.approvalDecisionCreated()).isFalse();
        assertThat(first.executionAllowed()).isFalse();
    }

    @Test
    void availableSyntheticDryRunBindsPreviewPhysicalRiskBudgetWorldAndBackup() {
        CandidateIndustrialZone zone = zone('d', 5);
        DeploymentDryRunReport report = dryRun(zone, ResourceId.parse("minecraft:gravel"), 3);
        FormalDeploymentCandidateInput input = new FormalDeploymentCandidateInput(
                WORLD, zone, ResourceId.parse("minecraft:gravel"), 3, Optional.of(report),
                Optional.of(ResourceId.parse("layout:verified_fixture")), RUNTIME, SNAPSHOT,
                backup(), PermissionDecision.UNKNOWN,
                List.of(RegionAuthorizedOperation.DRY_RUN, RegionAuthorizedOperation.PLACE_BLOCK,
                        RegionAuthorizedOperation.CONNECT_POWER, RegionAuthorizedOperation.ROLLBACK),
                List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "REGION_AUTHORIZATION",
                        "HUMAN_APPROVAL", "FORMAL_WORLD_EXECUTION_FORBIDDEN"),
                RollbackPolicy.REQUIRED_VERIFIED, EXPIRES, "Synthetic review-only gravel package");

        FormalDeploymentCandidatePackage result = packager.packageCandidate(input);

        assertThat(result.packageIdentity()).matches("formal-candidate:[0-9a-f]{64}");
        assertThat(result.previewHash()).contains(report.preview().previewHash());
        assertThat(result.verifiedPhysicalPlanIdentity()).contains(ResourceId.parse("layout:verified_fixture"));
        assertThat(result.runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(result.worldSnapshotFingerprint()).isEqualTo(SNAPSHOT);
        assertThat(result.backupIdentity()).isEqualTo(backup().identity());
        assertThat(result.riskAssessment()).contains(report.riskAssessment());
        assertThat(result.materialPowerMutationBudget()).contains(report.budget());
        assertThat(result.exactBoundingBox()).isEqualTo(zone.boundingBox());
        assertThat(result.artifactAvailability())
                .isEqualTo(FormalDeploymentArtifactAvailability.VERIFIED_DRY_RUN_AVAILABLE);
        assertThat(result.status()).isEqualTo(FormalDeploymentCandidateStatus.PENDING_USER_SELECTION);
        assertThat(result.approvalCreated()).isFalse();
        assertThat(result.executionAllowed()).isFalse();
    }

    @Test
    void refusesMismatchedPreviewAndAnyBatchThatClaimsTopOneSelection() {
        CandidateIndustrialZone zone = zone('e', 7);
        DeploymentDryRunReport wrongTarget = dryRun(zone, ResourceId.parse("create:iron_sheet"), 2);
        FormalDeploymentCandidateInput mismatch = new FormalDeploymentCandidateInput(
                WORLD, zone, ResourceId.parse("minecraft:gravel"), 3, Optional.of(wrongTarget),
                Optional.of(ResourceId.parse("layout:verified_fixture")), RUNTIME, SNAPSHOT,
                backup(), PermissionDecision.UNKNOWN, List.of(RegionAuthorizedOperation.DRY_RUN),
                List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN"),
                RollbackPolicy.REQUIRED_VERIFIED, EXPIRES, "Mismatched fixture");
        assertThatThrownBy(() -> packager.packageCandidate(mismatch))
                .isInstanceOf(IllegalArgumentException.class);

        FormalDeploymentCandidatePackage valid = packager.packageCandidate(
                blocked(zone, "minecraft:gravel", 3));
        assertThatThrownBy(() -> new FormalDeploymentCandidateBatch(
                WORLD, backup().identity().backupIdentity(), List.of(valid), 8,
                Optional.of(valid.packageIdentity()), true, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static FormalDeploymentCandidateInput blocked(
            CandidateIndustrialZone zone, String target, long quantity) {
        return new FormalDeploymentCandidateInput(
                WORLD, zone, ResourceId.parse(target), quantity, Optional.empty(), Optional.empty(),
                RUNTIME, SNAPSHOT, backup(), PermissionDecision.UNKNOWN,
                List.of(RegionAuthorizedOperation.DRY_RUN),
                List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "REGION_AUTHORIZATION",
                        "FORMAL_PREVIEW_UNAVAILABLE", "VERIFIED_PHYSICAL_PLAN_UNAVAILABLE",
                        "FORMAL_WORLD_EXECUTION_FORBIDDEN"),
                RollbackPolicy.REQUIRED_VERIFIED, EXPIRES, "Real candidate blocked pending review evidence");
    }

    static CandidateIndustrialZone zone(char hash, int score) {
        BlockPos3i origin = new BlockPos3i(score, 64, 0);
        return new CandidateIndustrialZone("zone:" + String.valueOf(hash).repeat(64),
                ResourceId.parse("minecraft:overworld"), new DeploymentBoundingBox(origin, origin),
                List.of(origin), List.of(), List.of(), Map.of("fixture", score), score,
                SurveyConfidence.UNKNOWN,
                List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "REGION_AUTHORIZATION"),
                new SurveyCoverage(1, 1, 1, 1, 1, Duration.ZERO, false),
                CandidateZoneStatus.PENDING_USER_SELECTION);
    }

    static BackupVerificationResult backup() {
        FormalWorldBackupManifest manifest = FormalWorldBackupManifest.complete(List.of(
                new FormalBackupFileEntry("level.dat", 1, 1, "1".repeat(64),
                        FormalBackupFilePrivacy.ORDINARY)), List.of());
        FormalWorldIdentity world = new FormalWorldIdentity(WORLD, "Formal", "world", 3_465,
                "1.20.1", List.of("minecraft:overworld"));
        FormalWorldBackupIdentity identity = new FormalWorldBackupIdentity(
                "formal-backup:" + "2".repeat(64), world, "3".repeat(64), "4".repeat(64),
                Instant.parse("2026-07-20T00:00:00Z"), manifest.manifestHash(), 1, 1,
                "formal-backup-v1", "steve-agent-0.1", "5".repeat(40),
                FormalBackupCompletionState.COMPLETED, Path.of("work/formal-backups/fixture").toAbsolutePath());
        return new BackupVerificationResult(identity, manifest,
                List.of(FormalBackupVerificationCheck.values()), "6".repeat(64),
                true, false, false, false);
    }

    static DeploymentDryRunReport dryRun(
            CandidateIndustrialZone zone, ResourceId target, long quantity) {
        BlockPos3i anchor = zone.anchorCandidates().get(0);
        DeploymentPreview unsigned = preview(zone, target, quantity, anchor, "");
        String hash = DeploymentPreviewService.sha256(DeploymentPreviewCodec.canonicalJson(unsigned, false));
        DeploymentPreview preview = preview(zone, target, quantity, anchor, hash);
        DeploymentRiskAssessment risk = new DeploymentRiskAssessment(hash, List.of(), RiskSeverity.INFO, false);
        DeploymentBudget budget = new DeploymentBudget(hash, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(target, quantity), 0, 0, OptionalLong.empty(), 1, 0, 0, 0,
                ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN, List.of(), true);
        return new DeploymentDryRunReport(preview, risk, budget, QuarterTurn.ZERO,
                List.of("REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL"),
                List.of("BACKUP_VERIFIED"), List.of("FORMAL_WORLD_EXECUTION_FORBIDDEN"),
                true, false, false, false, false, false, false, false);
    }

    private static DeploymentPreview preview(
            CandidateIndustrialZone zone,
            ResourceId target,
            long quantity,
            BlockPos3i anchor,
            String hash) {
        return new DeploymentPreview(target, quantity,
                List.of(ResourceId.parse(target.path().contains("iron")
                        ? "create:pressing/iron_ingot" : "create:milling/cobblestone")),
                List.of(ResourceId.parse("create:fixture")), anchor, List.of(QuarterTurn.ZERO),
                zone.boundingBox(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(target, quantity),
                OptionalLong.empty(), 0, List.of("offline formal assumption"), 0,
                RollbackClassification.UNSUPPORTED, WorldEnvironmentType.FORMAL_PLAYER_WORLD,
                RUNTIME, SNAPSHOT, List.of("FORMAL_WORLD_PREVIEW_ONLY"),
                List.of("REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL"),
                List.of("ENVIRONMENT_NOT_ALLOWED"), hash);
    }
}
