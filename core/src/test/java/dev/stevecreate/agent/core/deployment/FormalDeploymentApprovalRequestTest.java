package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.formalbackup.ApprovalScope;
import dev.stevecreate.agent.core.formalbackup.ApprovalUseRecord;
import dev.stevecreate.agent.core.formalbackup.FormalApprovalDecisionState;
import dev.stevecreate.agent.core.formalbackup.FormalApprovalEnvironment;
import dev.stevecreate.agent.core.formalbackup.FormalApprovalRequestStatus;
import dev.stevecreate.agent.core.formalbackup.FormalBackupFailureCode;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentApprovalDecision;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentApprovalRequest;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentApprovalRequestFactory;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidateInput;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackage;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackager;
import dev.stevecreate.agent.core.formalbackup.FormalWorldExecutionHardStop;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FormalDeploymentApprovalRequestTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final String RUNTIME = "runtime:deceasedcraft:verified";
    private static final String SNAPSHOT = "snapshot:formal:read-only";
    private static final Instant CREATED = Instant.parse("2026-07-20T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-21T00:00:00Z");
    private final FormalDeploymentCandidatePackager packager = new FormalDeploymentCandidatePackager();
    private final FormalDeploymentApprovalRequestFactory factory = new FormalDeploymentApprovalRequestFactory();

    @Test
    void realBlockedCandidateCreatesOnlyAwaitingSelectionRequestWithAbsentDecision() {
        FormalDeploymentCandidatePackage candidate = packager.packageCandidate(
                FormalDeploymentCandidatePackageTest.blocked(
                        FormalDeploymentCandidatePackageTest.zone('f', 4), "minecraft:gravel", 3));

        FormalDeploymentApprovalRequest request = factory.awaitUserSelection(candidate, CREATED);

        assertThat(request.requestIdentity()).matches("formal-approval-request:[0-9a-f]{64}");
        assertThat(request.status()).isEqualTo(FormalApprovalRequestStatus.AWAITING_USER_SELECTION);
        assertThat(request.scope()).isEmpty();
        assertThat(request.evidence().userSelectionPresent()).isFalse();
        assertThat(request.missingAuthorizations()).contains(
                "USER_SELECTION", "FORMAL_PREVIEW_UNAVAILABLE", "CLAIM_PERMISSION_UNKNOWN",
                "FORMAL_WORLD_EXECUTION_FORBIDDEN");
        assertThat(request.decision().state()).isEqualTo(FormalApprovalDecisionState.ABSENT);
        assertThat(request.automaticallyApproved()).isFalse();
        assertThat(request.executionAllowed()).isFalse();
    }

    @Test
    void explicitSelectedFixtureBindsExactOneTimeScopeAndRejectsScopeDrift() {
        FormalDeploymentCandidatePackage candidate = available('9');

        FormalDeploymentApprovalRequest request = factory.forUserSelectedCandidate(
                candidate, candidate.packageIdentity(), CREATED);

        ApprovalScope scope = request.scope().orElseThrow();
        assertThat(request.status()).isEqualTo(FormalApprovalRequestStatus.PENDING_USER_APPROVAL);
        assertThat(scope.worldIdentity()).isEqualTo(WORLD);
        assertThat(scope.candidatePackageIdentity()).isEqualTo(candidate.packageIdentity());
        assertThat(scope.userSelectedCandidateIdentity()).isEqualTo(candidate.packageIdentity());
        assertThat(scope.previewHash()).isEqualTo(candidate.previewHash().orElseThrow());
        assertThat(scope.target()).isEqualTo(candidate.target());
        assertThat(scope.quantity()).isEqualTo(candidate.quantity());
        assertThat(scope.backupIdentity()).isEqualTo(candidate.backupIdentity().backupIdentity());
        assertThat(scope.sourceFingerprint()).isEqualTo(candidate.backupIdentity().sourceFingerprint());
        assertThat(scope.runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(scope.worldSnapshotFingerprint()).isEqualTo(SNAPSHOT);
        assertThat(scope.materialBudgetHash()).matches("[0-9a-f]{64}");
        assertThat(scope.powerBudgetHash()).matches("[0-9a-f]{64}");
        assertThat(scope.oneTimeUse()).isTrue();
        assertThat(request.decision().state()).isEqualTo(FormalApprovalDecisionState.ABSENT);

        ApprovalScope stale = new ApprovalScope(scope.worldIdentity(), scope.candidatePackageIdentity(),
                scope.candidateZoneIdentity(), scope.userSelectedCandidateIdentity(), scope.previewHash(),
                scope.target(), scope.quantity(), scope.backupIdentity(), scope.sourceFingerprint(),
                scope.worldSnapshotFingerprint(), "runtime:stale", scope.maximumAffectedBlocks(),
                scope.materialBudgetHash(), scope.powerBudgetHash(), scope.allowedOperations(),
                scope.expiresAt(), true);
        assertThatThrownBy(() -> new FormalDeploymentApprovalRequest(
                request.requestIdentity(), candidate, Optional.of(stale), request.status(), request.evidence(),
                request.missingAuthorizations(), request.createdAt(), request.decision(), false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.forUserSelectedCandidate(candidate,
                "formal-candidate:" + "0".repeat(64), CREATED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOnlyCannotCopyIntoFormalAndHardStopAlwaysReturnsForbidden() {
        assertThatThrownBy(() -> new FormalDeploymentApprovalDecision(
                FormalApprovalDecisionState.TEST_ONLY_ALLOWED, FormalApprovalEnvironment.FORMAL_SOURCE,
                Optional.of(CREATED), Optional.of("TEST_ONLY"), false))
                .isInstanceOf(IllegalArgumentException.class);
        FormalDeploymentApprovalDecision drillOnly = new FormalDeploymentApprovalDecision(
                FormalApprovalDecisionState.TEST_ONLY_ALLOWED, FormalApprovalEnvironment.RESTORE_DRILL_COPY,
                Optional.of(CREATED), Optional.of("TEST_ONLY"), false);
        assertThat(drillOnly.formalExecutionAllowed()).isFalse();
        assertThatThrownBy(() -> new ApprovalUseRecord(
                "formal-approval-request:" + "1".repeat(64), FormalApprovalEnvironment.FORMAL_SOURCE,
                true, Optional.of(CREATED), false)).isInstanceOf(IllegalArgumentException.class);

        FormalDeploymentApprovalRequest awaiting = factory.awaitUserSelection(
                packager.packageCandidate(FormalDeploymentCandidatePackageTest.blocked(
                        FormalDeploymentCandidatePackageTest.zone('8', 6), "minecraft:gravel", 3)), CREATED);
        var stopped = new FormalWorldExecutionHardStop().evaluate(
                WORLD, Optional.of(awaiting), Optional.of(awaiting.decision()));
        assertThat(stopped.failure().code())
                .isEqualTo(FormalBackupFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN);
        assertThat(stopped.failure().evidence()).contains(
                "formalSource=true", "separateExecutionPilotApprovalRequired=true",
                "writeGuardBypassAvailable=false");
        assertThat(stopped.executionAllowed()).isFalse();
        assertThat(stopped.formalWorldWriteAllowed()).isFalse();
    }

    private FormalDeploymentCandidatePackage available(char hash) {
        CandidateIndustrialZone zone = FormalDeploymentCandidatePackageTest.zone(hash, 5);
        DeploymentDryRunReport report = FormalDeploymentCandidatePackageTest.dryRun(
                zone, ResourceId.parse("minecraft:gravel"), 3);
        FormalDeploymentCandidateInput input = new FormalDeploymentCandidateInput(
                WORLD, zone, ResourceId.parse("minecraft:gravel"), 3, Optional.of(report),
                Optional.of(ResourceId.parse("layout:verified_fixture")), RUNTIME, SNAPSHOT,
                FormalDeploymentCandidatePackageTest.backup(), PermissionDecision.UNKNOWN,
                List.of(RegionAuthorizedOperation.DRY_RUN, RegionAuthorizedOperation.PLACE_BLOCK,
                        RegionAuthorizedOperation.CONNECT_POWER, RegionAuthorizedOperation.ROLLBACK),
                List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "REGION_AUTHORIZATION",
                        "HUMAN_APPROVAL", "FORMAL_WORLD_EXECUTION_FORBIDDEN"),
                RollbackPolicy.REQUIRED_VERIFIED, EXPIRES, "Selected fixture candidate");
        return packager.packageCandidate(input);
    }
}
