package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.deployment.BackupApprovalState;
import dev.stevecreate.agent.core.deployment.BackupManifest;
import dev.stevecreate.agent.core.deployment.BackupManifestState;
import dev.stevecreate.agent.core.deployment.BackupPathPolicy;
import dev.stevecreate.agent.core.deployment.BackupPlan;
import dev.stevecreate.agent.core.deployment.BackupPlanner;
import dev.stevecreate.agent.core.deployment.BackupVerification;
import dev.stevecreate.agent.core.deployment.BackupVerifier;
import dev.stevecreate.agent.core.deployment.DeploymentBudget;
import dev.stevecreate.agent.core.deployment.DeploymentBudgetViolation;
import dev.stevecreate.agent.core.deployment.DeploymentFailure;
import dev.stevecreate.agent.core.deployment.DeploymentFailureCode;
import dev.stevecreate.agent.core.deployment.DeploymentPolicy;
import dev.stevecreate.agent.core.deployment.DeploymentPreview;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessCheck;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessContext;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessFailure;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessRefusal;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessResult;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessSuccess;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessVerifier;
import dev.stevecreate.agent.core.deployment.DeploymentReadyPlan;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessment;
import dev.stevecreate.agent.core.deployment.DeploymentRiskCategory;
import dev.stevecreate.agent.core.deployment.DeploymentRiskFinding;
import dev.stevecreate.agent.core.deployment.DryRunCompletion;
import dev.stevecreate.agent.core.deployment.HumanApprovalAuthorizerType;
import dev.stevecreate.agent.core.deployment.HumanApprovalDecision;
import dev.stevecreate.agent.core.deployment.HumanApprovalGate;
import dev.stevecreate.agent.core.deployment.HumanApprovalRequest;
import dev.stevecreate.agent.core.deployment.HumanApprovalToken;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.deployment.RegionApprovalState;
import dev.stevecreate.agent.core.deployment.RegionAuthorization;
import dev.stevecreate.agent.core.deployment.RegionAuthorizationRequest;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.RegionRevocationState;
import dev.stevecreate.agent.core.deployment.RegionUseState;
import dev.stevecreate.agent.core.deployment.ResourceSourcePolicy;
import dev.stevecreate.agent.core.deployment.RiskSeverity;
import dev.stevecreate.agent.core.deployment.RollbackClassification;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentDescriptor;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessSuccess;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerifier;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeploymentReadinessVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-17T14:00:00Z");
    private static final String TOKEN = "a".repeat(64);
    @TempDir Path temp;

    @Test
    void allTwentyFiveChecksProduceOnlyAnIsolatedNonExecutableReadyArtifact() throws Exception {
        Facts facts = new Facts(temp);
        DeploymentReadinessResult result = facts.verifier().verify(facts.context());

        assertThat(result).isInstanceOf(DeploymentReadinessSuccess.class);
        DeploymentReadyPlan ready = ((DeploymentReadinessSuccess) result).plan();
        assertThat(ready.evidence().keySet())
                .containsExactly(DeploymentReadinessCheck.values());
        assertThat(ready.evidence()).hasSize(25);
        assertThat(ready.environmentId()).isEqualTo("isolated");
        assertThat(ready.trace()).contains("approval consumed exactly once");
        assertThat(facts.verifier().verify(facts.context()))
                .isInstanceOf(DeploymentReadinessRefusal.class);
        assertThat(failures(facts.verifier().verify(facts.context())))
                .contains(DeploymentReadinessFailure.HUMAN_APPROVAL_ALREADY_USED);
    }

    @ParameterizedTest
    @EnumSource(DeploymentReadinessFailure.class)
    void everyPw11GateHasAnIndependentFailClosedMutation(
            DeploymentReadinessFailure expected) throws Exception {
        Facts facts = new Facts(temp.resolve(expected.name()));
        facts.mutate(expected);

        DeploymentReadinessResult result = facts.verifier().verify(facts.context());

        assertThat(result).isInstanceOf(DeploymentReadinessRefusal.class);
        assertThat(failures(result)).contains(expected);
        DeploymentFailure detail = ((DeploymentReadinessRefusal) result).details().stream()
                .filter(item -> item.trace().contains(expected.name()))
                .findFirst().orElseThrow();
        assertThat(detail.worldIdentity()).isNotBlank();
        assertThat(detail.gameDirectoryIdentity()).isNotBlank();
        assertThat(detail.previewHash()).hasSize(64);
        assertThat(detail.policy()).isNotBlank();
        assertThat(detail.risk()).isNotBlank();
        assertThat(detail.approval()).isNotBlank();
        assertThat(detail.backupIdentity()).isNotBlank();
        assertThat(detail.runtimeFingerprint()).isNotBlank();
        assertThat(detail.reason()).isNotBlank();
        assertThat(detail.safeNextStep()).isNotBlank();
    }

    @Test
    void readinessTypeCannotBecomeAParallelExecutionOrWorldAuthority() {
        assertThat(DeploymentFailureCode.values()).hasSize(36);
        assertThat(DeploymentReadyPlan.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(DeploymentReadyPlan.class.getDeclaredFields())
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.contains("ExecutionReadyPlan")
                        || name.contains("GenericExecutionSession")
                        || name.contains("WorldResourceBuffer")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
        assertThat(Arrays.stream(DeploymentReadyPlan.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase()))
                .noneMatch(name -> name.contains("execute") || name.contains("session")
                        || name.contains("withdraw") || name.contains("mutate")
                        || name.contains("startmachine"));
    }

    private static List<DeploymentReadinessFailure> failures(DeploymentReadinessResult result) {
        return ((DeploymentReadinessRefusal) result).failures();
    }

    private static final class Facts {
        private final HumanApprovalGate gate = new HumanApprovalGate();
        private final ExecutionReadyPlan execution;
        private final WorldEnvironmentDescriptor baseEnvironment;
        private final DeploymentPreview basePreview;
        private final BackupPlan baseBackup;
        private final BackupVerification baseBackupVerification;
        private ResourceId expectedPhysical;
        private ResourceId expectedGraph;
        private WorldEnvironmentDescriptor environment;
        private Optional<DeploymentPolicy> policy;
        private DeploymentPreview preview;
        private DryRunCompletion dryRun = DryRunCompletion.COMPLETE;
        private Instant snapshotObserved = NOW.minusSeconds(10);
        private String currentSnapshot;
        private String currentRuntime;
        private RegionAuthorization authorization;
        private RegionAuthorizationRequest regionRequest;
        private Map<RegionAuthorizedOperation, PermissionEvidence> permissions;
        private DeploymentRiskAssessment risks;
        private Set<DeploymentRiskCategory> handledHigh = Set.of();
        private DeploymentBudget budget;
        private Optional<BackupPlan> backup;
        private Optional<BackupVerification> backupVerification;
        private HumanApprovalDecision approvalDecision = HumanApprovalDecision.APPROVED;
        private Instant approvalExpiry = NOW.plusSeconds(300);
        private ResourceId approvalRequestTarget;

        Facts(Path root) throws Exception {
            Files.createDirectories(root);
            var physical = DeploymentPreviewServiceTest.physicalPlan();
            execution = ((ExecutionReadinessSuccess) new ExecutionReadinessVerifier().verify(
                    physical, new PhysicalizationServiceTest.ReadyFacts(physical).context())).plan();
            expectedPhysical = physical.id();
            expectedGraph = physical.unifiedGraph().id();
            basePreview = new dev.stevecreate.agent.core.deployment.DeploymentPreviewService()
                    .preview(physical, DeploymentPreviewServiceTest.context(Map.of()));
            preview = basePreview;
            currentSnapshot = preview.worldSnapshotFingerprint();
            currentRuntime = preview.runtimeFingerprint();
            baseEnvironment = new WorldEnvironmentDescriptor(
                    "isolated", WorldEnvironmentType.ISOLATED_TEST_WORLD, "world",
                    "isolated-root/world", "isolated-root", "1.20.1", "forge",
                    currentRuntime, "save", "server", true, true, true, true, true,
                    "test", List.of("repository isolated marker"), List.of());
            environment = baseEnvironment;
            policy = Optional.of(DeploymentPolicy.isolatedTestDefault(
                    "isolated-root", "formal-root"));
            Set<RegionAuthorizedOperation> operations = Set.of(
                    RegionAuthorizedOperation.PLACE_BLOCK,
                    RegionAuthorizedOperation.CONNECT_POWER,
                    RegionAuthorizedOperation.ROLLBACK);
            int mutations = preview.plannedPlacements().size()
                    + preview.plannedRemovals().size() + preview.plannedReplacements().size();
            regionRequest = new RegionAuthorizationRequest(
                    environment.worldIdentity(), environment.environmentType(), id("minecraft:overworld"),
                    preview.affectedBounds(), Optional.empty(), operations, mutations,
                    preview.previewHash(), currentSnapshot, currentRuntime);
            authorization = authorization(RegionUseState.UNUSED);
            permissions = permissions(operations);
            risks = new DeploymentRiskAssessment(
                    preview.previewHash(), List.of(), RiskSeverity.INFO, false);
            budget = budget(List.of(), 64);

            Path isolated = Files.createDirectories(root.resolve("isolated"));
            Path world = Files.createDirectories(isolated.resolve("world"));
            Path backups = Files.createDirectories(isolated.resolve("backups"));
            Path formal = Files.createDirectories(root.resolve("formal-reference"));
            Files.writeString(world.resolve("level.dat"), "isolated-fixture");
            BackupPathPolicy pathPolicy = new BackupPathPolicy(isolated, backups, Set.of(formal));
            baseBackup = new BackupPlanner().planIsolated(
                    "pw11-backup", environment.worldIdentity(), world,
                    backups.resolve("pw11-backup"), pathPolicy, "pw11-backup",
                    BackupApprovalState.APPROVED_TEST_ONLY);
            baseBackupVerification = new BackupVerifier().verify(baseBackup);
            backup = Optional.of(baseBackup);
            backupVerification = Optional.of(baseBackupVerification);
            approvalRequestTarget = preview.target();
        }

        DeploymentReadinessVerifier verifier() {
            return new DeploymentReadinessVerifier(gate);
        }

        DeploymentReadinessContext context() {
            DeploymentPolicy exactPolicy = policy.orElseGet(() ->
                    DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root"));
            HumanApprovalRequest request = approvalRequest(exactPolicy);
            return new DeploymentReadinessContext(
                    execution, expectedPhysical, expectedGraph, environment, policy, preview,
                    dryRun, snapshotObserved, Duration.ofMinutes(1), currentSnapshot,
                    currentRuntime, authorization, regionRequest, permissions, risks,
                    handledHigh, budget, backup, backupVerification,
                    approval(exactPolicy), request, NOW);
        }

        void mutate(DeploymentReadinessFailure failure) {
            switch (failure) {
                case VERIFIED_PHYSICAL_PLAN_INVALID -> expectedPhysical = id("fixture:wrong_plan");
                case UNIFIED_MACHINE_GRAPH_INVALID -> expectedGraph = id("fixture:wrong_graph");
                case ENVIRONMENT_CLASSIFICATION_UNCLEAR -> environment = environment(
                        WorldEnvironmentType.UNKNOWN_WORLD, false, false, false);
                case DEPLOYMENT_POLICY_MISSING -> policy = Optional.empty();
                case FORMAL_WORLD_POLICY_RELAXED -> policy = Optional.of(relaxedPolicy());
                case DRY_RUN_INCOMPLETE -> dryRun = DryRunCompletion.INCOMPLETE;
                case PREVIEW_HASH_INVALID -> preview = previewWith(
                        "0".repeat(64), preview.rollbackClassification());
                case WORLD_SNAPSHOT_STALE -> snapshotObserved = NOW.minusSeconds(120);
                case RUNTIME_FINGERPRINT_CHANGED -> currentRuntime = "runtime:changed";
                case REGION_AUTHORIZATION_INCOMPLETE -> authorization = authorization(
                        RegionUseState.CONSUMED);
                case OPERATION_PERMISSION_INCOMPLETE -> permissions = Map.of();
                case CRITICAL_RISK_PRESENT -> risks = risk(
                        DeploymentRiskCategory.FIRE_OR_LAVA, RiskSeverity.CRITICAL);
                case HIGH_RISK_UNHANDLED -> risks = risk(
                        DeploymentRiskCategory.POWER_OVERLOAD, RiskSeverity.HIGH);
                case DEPLOYMENT_BUDGET_EXCEEDED -> budget = budget(
                        List.of(DeploymentBudgetViolation.AFFECTED_BLOCK_LIMIT_EXCEEDED), 64);
                case RESOURCE_SOURCE_INVALID -> budget = budget(
                        List.of(DeploymentBudgetViolation.RESOURCE_SOURCE_UNSUPPORTED), 64);
                case POWER_CAPACITY_INVALID -> budget = budget(
                        List.of(DeploymentBudgetViolation.POWER_CAPACITY_INSUFFICIENT), -1);
                case BACKUP_REQUIREMENT_UNSATISFIED -> backup = Optional.empty();
                case BACKUP_MANIFEST_INVALID -> invalidateManifest();
                case HUMAN_APPROVAL_INVALID -> approvalDecision = HumanApprovalDecision.REJECTED;
                case HUMAN_APPROVAL_EXPIRED -> approvalExpiry = NOW;
                case HUMAN_APPROVAL_ALREADY_USED -> gate.verifyAndConsume(
                        approval(policy.orElseThrow()), approvalRequest(policy.orElseThrow()), NOW);
                case HUMAN_APPROVAL_SCOPE_MISMATCH -> approvalRequestTarget = id("fixture:other_target");
                case ROLLBACK_POLICY_UNSATISFIED -> preview = previewWith(
                        preview.previewHash(), RollbackClassification.UNSUPPORTED);
                case FORMAL_WORLD_HARD_GATE_MISSING -> policy = Optional.of(
                        DeploymentPolicy.isolatedTestDefault("isolated-root", "isolated-root"));
                case EXECUTION_ENVIRONMENT_NOT_ALLOWED -> environment = environment(
                        WorldEnvironmentType.ISOLATED_TEST_WORLD, true, true, false);
            }
        }

        private DeploymentPolicy relaxedPolicy() {
            DeploymentPolicy value = policy.orElseThrow();
            return new DeploymentPolicy(
                    value.policyId(), value.allowedEnvironmentTypes(), value.forbiddenRootIdentities(),
                    value.allowedRootIdentities(), value.maximumAffectedBlocks(),
                    value.maximumBoundingVolume(), value.maximumRouteLength(),
                    value.maximumMaterialCost(), value.maximumRotationalStressDemand(),
                    value.allowedAdapterIds(), value.allowedImplementationIds(),
                    value.allowedRecipeTypes(), value.allowedDimensionIds(), value.allowedTimeWindow(),
                    value.executionPermitted(), false, value.dryRunRequired(),
                    value.humanApprovalRequired(), value.approvalExpiry(), value.rollbackRequired(),
                    value.rollbackPolicy(), value.unknownBlockReplacementPolicy(),
                    value.playerBuiltBlockProtectionPolicy(), value.blockEntityProtectionPolicy(),
                    value.claimPermissionRequirement(), value.resourceSourcePolicy(),
                    value.existingMachineReusePolicy(), value.worldMutationBudget());
        }

        private RegionAuthorization authorization(RegionUseState use) {
            return new RegionAuthorization(
                    "authorization:pw11", environment.worldIdentity(),
                    WorldEnvironmentType.ISOLATED_TEST_WORLD, id("minecraft:overworld"),
                    preview.affectedBounds(), Optional.empty(), "authorizer:test-only",
                    regionRequest.requestedOperations(), regionRequest.requestedBlockMutations(),
                    NOW.plusSeconds(300), preview.previewHash(), currentSnapshot, currentRuntime,
                    true, RegionApprovalState.APPROVED, RegionRevocationState.ACTIVE, use,
                    "fixture:pw11");
        }

        private Map<RegionAuthorizedOperation, PermissionEvidence> permissions(
                Set<RegionAuthorizedOperation> operations) {
            EnumMap<RegionAuthorizedOperation, PermissionEvidence> values =
                    new EnumMap<>(RegionAuthorizedOperation.class);
            for (RegionAuthorizedOperation operation : operations) {
                PermissionQuery query = new PermissionQuery(
                        "query:" + operation.name().toLowerCase(), operation, "actor:test",
                        preview.affectedBounds(), environment.worldIdentity(),
                        WorldEnvironmentType.ISOLATED_TEST_WORLD, id("minecraft:overworld"),
                        "fixture", NOW.minusSeconds(30), 0, currentRuntime);
                values.put(operation, new PermissionEvidence(
                        query, PermissionDecision.ALLOWED, id("fixture:claim_adapter"),
                        PermissionEvidenceState.VERIFIED, "fixture", NOW.minusSeconds(20),
                        0, currentRuntime, "fixture:verified"));
            }
            return Map.copyOf(values);
        }

        private DeploymentRiskAssessment risk(
                DeploymentRiskCategory category,
                RiskSeverity severity) {
            DeploymentRiskFinding finding = new DeploymentRiskFinding(
                    category, severity, "fixture", "mutation", "handle explicitly",
                    severity == RiskSeverity.CRITICAL);
            return new DeploymentRiskAssessment(
                    preview.previewHash(), List.of(finding), severity,
                    severity == RiskSeverity.CRITICAL);
        }

        private DeploymentBudget budget(
                List<DeploymentBudgetViolation> violations,
                long powerMargin) {
            return new DeploymentBudget(
                    preview.previewHash(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), preview.stressDemand(), powerMargin, OptionalLong.of(20),
                    100, preview.plannedPlacements().size(), 1_024, 512,
                    ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, violations, violations.isEmpty());
        }

        private void invalidateManifest() {
            BackupManifest original = baseBackup.manifest();
            BackupManifest incomplete = new BackupManifest(
                    original.entries(), original.totalBytes(), original.manifestHash(),
                    BackupManifestState.INCOMPLETE);
            BackupPlan invalid = copyBackup(baseBackup, incomplete);
            backup = Optional.of(invalid);
            backupVerification = Optional.of(new BackupVerifier().verify(invalid));
        }

        private HumanApprovalToken approval(DeploymentPolicy exactPolicy) {
            return new HumanApprovalToken(
                    TOKEN, approvalDecision, HumanApprovalAuthorizerType.TEST_ONLY, "TEST_ONLY",
                    WorldEnvironmentType.ISOLATED_TEST_WORLD, preview.previewHash(),
                    environment.worldIdentity(), currentSnapshot, currentRuntime, 0,
                    preview.affectedBounds(), preview.target(), preview.quantity(),
                    regionRequest.requestedBlockMutations(), exactPolicy, approvalExpiry,
                    "fixture:pw11-test-only");
        }

        private HumanApprovalRequest approvalRequest(DeploymentPolicy exactPolicy) {
            return new HumanApprovalRequest(
                    WorldEnvironmentType.ISOLATED_TEST_WORLD, preview.previewHash(),
                    environment.worldIdentity(), currentSnapshot, currentRuntime, 0,
                    preview.affectedBounds(), approvalRequestTarget, preview.quantity(),
                    regionRequest.requestedBlockMutations(), exactPolicy);
        }

        private WorldEnvironmentDescriptor environment(
                WorldEnvironmentType type,
                boolean disposable,
                boolean writable,
                boolean executionAllowed) {
            return new WorldEnvironmentDescriptor(
                    baseEnvironment.environmentId(), type, baseEnvironment.worldIdentity(),
                    baseEnvironment.worldRootIdentity(), baseEnvironment.gameDirectoryIdentity(),
                    baseEnvironment.minecraftVersion(), baseEnvironment.loaderAndModFingerprint(),
                    baseEnvironment.runtimeFingerprint(), baseEnvironment.saveFingerprint(),
                    baseEnvironment.serverIdentity(), disposable, writable, true, true,
                    executionAllowed, baseEnvironment.provenance(),
                    baseEnvironment.classificationEvidence(), baseEnvironment.warnings());
        }

        private DeploymentPreview previewWith(
                String hash,
                RollbackClassification rollback) {
            DeploymentPreview p = preview;
            return new DeploymentPreview(
                    p.target(), p.quantity(), p.recipeIds(), p.implementationIds(), p.anchor(),
                    p.orientations(), p.affectedBounds(), p.plannedPlacements(), p.plannedRemovals(),
                    p.plannedReplacements(), p.protectedBlocksEncountered(),
                    p.blockEntitiesEncountered(), p.itemRoutes(), p.rotationalPowerRoutes(),
                    p.materialBillOfMaterials(), p.requiredInputResources(),
                    p.machineConstructionMaterials(), p.expectedOutput(), p.estimatedTicks(),
                    p.stressDemand(), p.powerSourceAssumptions(), p.journalEstimate(), rollback,
                    p.environmentClassification(), p.runtimeFingerprint(),
                    p.worldSnapshotFingerprint(), p.riskFindings(), p.requiredApprovals(),
                    p.policyViolations(), hash);
        }

        private static BackupPlan copyBackup(BackupPlan value, BackupManifest manifest) {
            return new BackupPlan(
                    value.backupIdentity(), value.worldIdentity(), value.environmentClassification(),
                    value.sourceWorldRoot(), value.backupTarget(), value.pathPolicy(),
                    value.estimatedBackupSizeBytes(), value.requiredAvailableSpaceBytes(),
                    value.observedAvailableSpaceBytes(), manifest, value.targetStrategy(),
                    value.atomicityStrategy(), value.consistencyStrategy(), value.restoreDrillPlan(),
                    value.retentionPolicy(), value.failureHandling(), value.approvalRequirement(),
                    value.approvalState(), value.journalBackupIdentity());
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
