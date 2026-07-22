package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerificationCheck;
import dev.stevecreate.agent.core.layout.PhysicalizationVerificationCheck;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure fail-closed PW-11 gate. Passing consumes approval but never creates an execution session. */
public final class DeploymentReadinessVerifier {
    private static final Set<DeploymentBudgetViolation> RESOURCE_FAILURES = Set.of(
            DeploymentBudgetViolation.RESOURCE_SOURCE_POLICY_MISMATCH,
            DeploymentBudgetViolation.RESOURCE_SOURCE_UNSUPPORTED,
            DeploymentBudgetViolation.RESOURCE_SOURCE_NOT_READ_ONLY,
            DeploymentBudgetViolation.FORMAL_WORLD_AUTO_WITHDRAW_FORBIDDEN);
    private static final Set<DeploymentBudgetViolation> POWER_FAILURES = Set.of(
            DeploymentBudgetViolation.POWER_CAPACITY_INSUFFICIENT,
            DeploymentBudgetViolation.STRESS_BUDGET_EXCEEDED);
    private static final Set<BackupVerificationFailure> MANIFEST_FAILURES = Set.of(
            BackupVerificationFailure.MANIFEST_INCOMPLETE,
            BackupVerificationFailure.MANIFEST_HASH_INVALID);
    private final HumanApprovalGate approvalGate;

    public DeploymentReadinessVerifier(HumanApprovalGate approvalGate) {
        this.approvalGate = java.util.Objects.requireNonNull(approvalGate, "approvalGate");
    }

    public DeploymentReadinessResult verify(DeploymentReadinessContext context) {
        if (context == null) {
            DeploymentReadinessFailure failure =
                    DeploymentReadinessFailure.VERIFIED_PHYSICAL_PLAN_INVALID;
            return new DeploymentReadinessRefusal(List.of(failure), List.of(missingDetail(failure)));
        }
        List<DeploymentReadinessFailure> failures = new ArrayList<>();
        List<DeploymentReadinessEvidence> evidence = new ArrayList<>();
        var execution = context.executionReadyPlan();
        var physical = execution.physicalPlan();
        var graph = physical.unifiedGraph();

        pass(physical.id().equals(context.expectedPhysicalPlanId())
                        && physical.evidence().keySet().equals(EnumSet.allOf(PhysicalizationVerificationCheck.class))
                        && execution.evidence().keySet().equals(
                                EnumSet.allOf(ExecutionReadinessVerificationCheck.class)),
                DeploymentReadinessCheck.VERIFIED_PHYSICAL_PLAN_VALID,
                DeploymentReadinessFailure.VERIFIED_PHYSICAL_PLAN_INVALID,
                "complete physical and execution-readiness evidence retained", failures, evidence);
        pass(graph.id().equals(context.expectedUnifiedGraphId())
                        && graph.equals(physical.candidate().unifiedGraph()) && !graph.nodes().isEmpty()
                        && !graph.ports().isEmpty() && !graph.edges().isEmpty(),
                DeploymentReadinessCheck.UNIFIED_MACHINE_GRAPH_VALID,
                DeploymentReadinessFailure.UNIFIED_MACHINE_GRAPH_INVALID,
                "exact non-empty unified machine graph retained", failures, evidence);

        WorldEnvironmentDescriptor environment = context.environment();
        boolean clearEnvironment = environment.environmentType()
                != WorldEnvironmentType.UNKNOWN_WORLD
                && environment.environmentType() != WorldEnvironmentType.FORBIDDEN_WORLD
                && !environment.classificationEvidence().isEmpty();
        pass(clearEnvironment, DeploymentReadinessCheck.ENVIRONMENT_CLASSIFICATION_CLEAR,
                DeploymentReadinessFailure.ENVIRONMENT_CLASSIFICATION_UNCLEAR,
                "environment=" + environment.environmentType(), failures, evidence);

        DeploymentPolicy policy = context.deploymentPolicy().orElse(null);
        pass(policy != null, DeploymentReadinessCheck.DEPLOYMENT_POLICY_PRESENT,
                DeploymentReadinessFailure.DEPLOYMENT_POLICY_MISSING,
                "deployment policy is present", failures, evidence);
        boolean policySafe = policy != null && policy.dryRunRequired()
                && policy.backupRequired() && policy.humanApprovalRequired()
                && policy.rollbackRequired()
                && policy.allowedEnvironmentTypes().stream().noneMatch(
                        DeploymentReadinessVerifier::dangerous);
        pass(policySafe, DeploymentReadinessCheck.FORMAL_WORLD_POLICY_NOT_RELAXED,
                DeploymentReadinessFailure.FORMAL_WORLD_POLICY_RELAXED,
                "formal, unknown and forbidden execution remain excluded", failures, evidence);
        pass(context.dryRunCompletion() == DryRunCompletion.COMPLETE,
                DeploymentReadinessCheck.DRY_RUN_COMPLETED,
                DeploymentReadinessFailure.DRY_RUN_INCOMPLETE,
                "deterministic dry-run completed", failures, evidence);

        DeploymentPreview preview = context.preview();
        boolean previewValid = preview.hasValidHash()
                && preview.previewHash().equals(context.regionRequest().previewHash())
                && preview.previewHash().equals(context.humanApprovalRequest().previewHash())
                && preview.previewHash().equals(context.deploymentBudget().previewHash())
                && preview.previewHash().equals(context.riskAssessment().previewHash());
        pass(previewValid, DeploymentReadinessCheck.PREVIEW_HASH_VALID,
                DeploymentReadinessFailure.PREVIEW_HASH_INVALID,
                "preview hash is valid and identical across all evidence", failures, evidence);
        Duration snapshotAge = Duration.between(context.snapshotObservedAt(), context.evaluatedAt());
        boolean snapshotFresh = !snapshotAge.isNegative()
                && snapshotAge.compareTo(context.maximumSnapshotAge()) <= 0
                && preview.worldSnapshotFingerprint().equals(
                        context.currentWorldSnapshotFingerprint());
        pass(snapshotFresh, DeploymentReadinessCheck.SNAPSHOT_NOT_STALE,
                DeploymentReadinessFailure.WORLD_SNAPSHOT_STALE,
                "snapshot age=" + snapshotAge, failures, evidence);
        boolean runtimeSame = preview.runtimeFingerprint().equals(context.currentRuntimeFingerprint())
                && physical.candidate().boundPlan().graph().runtimeFingerprint()
                        .equals(context.currentRuntimeFingerprint());
        pass(runtimeSame, DeploymentReadinessCheck.RUNTIME_FINGERPRINT_UNCHANGED,
                DeploymentReadinessFailure.RUNTIME_FINGERPRINT_CHANGED,
                "runtime fingerprint unchanged", failures, evidence);

        RegionAuthorizationCheck regionCheck = new RegionAuthorizationService().check(
                context.regionAuthorization(), context.regionRequest(), context.evaluatedAt());
        boolean regionCovers = regionCheck.allowed()
                && context.regionRequest().requestedBounds().equals(preview.affectedBounds());
        pass(regionCovers,
                DeploymentReadinessCheck.REGION_AUTHORIZATION_COVERS_AFFECTED_REGION,
                DeploymentReadinessFailure.REGION_AUTHORIZATION_INCOMPLETE,
                "authorization covers exact preview bounds and mutation scope", failures, evidence);
        pass(permissionsComplete(context),
                DeploymentReadinessCheck.OPERATION_PERMISSION_COMPLETE,
                DeploymentReadinessFailure.OPERATION_PERMISSION_INCOMPLETE,
                "verified claim evidence covers every requested operation", failures, evidence);

        boolean noCritical = context.riskAssessment().findings().stream()
                .noneMatch(finding -> finding.severity() == RiskSeverity.CRITICAL);
        pass(noCritical, DeploymentReadinessCheck.NO_CRITICAL_RISK,
                DeploymentReadinessFailure.CRITICAL_RISK_PRESENT,
                "risk assessment contains no CRITICAL finding", failures, evidence);
        Set<DeploymentRiskCategory> highRisks = EnumSet.noneOf(DeploymentRiskCategory.class);
        context.riskAssessment().findings().stream()
                .filter(finding -> finding.severity() == RiskSeverity.HIGH)
                .map(DeploymentRiskFinding::category).forEach(highRisks::add);
        pass(context.policyHandledHighRisks().containsAll(highRisks),
                DeploymentReadinessCheck.HIGH_RISK_HANDLED_BY_POLICY,
                DeploymentReadinessFailure.HIGH_RISK_UNHANDLED,
                "all HIGH risks have explicit policy handling", failures, evidence);

        List<DeploymentBudgetViolation> budgetFailures = context.deploymentBudget()
                .policyViolations().stream()
                .filter(item -> !RESOURCE_FAILURES.contains(item) && !POWER_FAILURES.contains(item))
                .toList();
        pass(budgetFailures.isEmpty(), DeploymentReadinessCheck.BUDGET_WITHIN_LIMIT,
                DeploymentReadinessFailure.DEPLOYMENT_BUDGET_EXCEEDED,
                "non-resource and non-power deployment limits are satisfied", failures, evidence);
        pass(context.deploymentBudget().policyViolations().stream()
                        .noneMatch(RESOURCE_FAILURES::contains),
                DeploymentReadinessCheck.RESOURCE_SOURCE_LEGAL,
                DeploymentReadinessFailure.RESOURCE_SOURCE_INVALID,
                "resource source is explicit, legal and read-only", failures, evidence);
        pass(context.deploymentBudget().powerCapacityMargin() >= 0
                        && context.deploymentBudget().policyViolations().stream()
                                .noneMatch(POWER_FAILURES::contains),
                DeploymentReadinessCheck.POWER_CAPACITY_LEGAL,
                DeploymentReadinessFailure.POWER_CAPACITY_INVALID,
                "power capacity and rotational stress remain within limits", failures, evidence);

        boolean backupSatisfied = backupRequirementSatisfied(context, policy);
        pass(backupSatisfied, DeploymentReadinessCheck.BACKUP_REQUIREMENT_SATISFIED,
                DeploymentReadinessFailure.BACKUP_REQUIREMENT_UNSATISFIED,
                "isolated backup evidence satisfies non-manifest requirements", failures, evidence);
        boolean manifestValid = context.backupPlan().map(BackupPlan::manifest)
                .map(manifest -> manifest.state() == BackupManifestState.COMPLETE
                        && manifest.hasValidHash()).orElse(false)
                && context.backupVerification().map(verification -> verification.failures().stream()
                        .noneMatch(MANIFEST_FAILURES::contains)).orElse(false);
        pass(manifestValid, DeploymentReadinessCheck.BACKUP_MANIFEST_VALID,
                DeploymentReadinessFailure.BACKUP_MANIFEST_INVALID,
                "backup manifest is complete and hash-valid", failures, evidence);

        HumanApprovalCheck approval = approvalGate.inspect(
                context.humanApproval(), context.humanApprovalRequest(), context.evaluatedAt());
        pass(!approval.failures().contains(HumanApprovalFailure.APPROVAL_NOT_GRANTED)
                        && !approval.failures().contains(HumanApprovalFailure.TEST_ONLY_SCOPE_FORBIDDEN),
                DeploymentReadinessCheck.HUMAN_APPROVAL_VALID,
                DeploymentReadinessFailure.HUMAN_APPROVAL_INVALID,
                "structured approval is explicitly granted", failures, evidence);
        pass(!approval.failures().contains(HumanApprovalFailure.APPROVAL_EXPIRED),
                DeploymentReadinessCheck.HUMAN_APPROVAL_NOT_EXPIRED,
                DeploymentReadinessFailure.HUMAN_APPROVAL_EXPIRED,
                "approval remains within its bounded validity window", failures, evidence);
        pass(!approval.failures().contains(HumanApprovalFailure.TOKEN_ALREADY_CONSUMED),
                DeploymentReadinessCheck.HUMAN_APPROVAL_UNUSED,
                DeploymentReadinessFailure.HUMAN_APPROVAL_ALREADY_USED,
                "one-time approval is unused", failures, evidence);
        boolean approvalScope = approval.failures().stream().allMatch(failure ->
                failure == HumanApprovalFailure.APPROVAL_NOT_GRANTED
                        || failure == HumanApprovalFailure.APPROVAL_EXPIRED
                        || failure == HumanApprovalFailure.TEST_ONLY_SCOPE_FORBIDDEN
                        || failure == HumanApprovalFailure.TOKEN_ALREADY_CONSUMED);
        pass(approvalScope, DeploymentReadinessCheck.HUMAN_APPROVAL_EXACT_SCOPE,
                DeploymentReadinessFailure.HUMAN_APPROVAL_SCOPE_MISMATCH,
                "approval exactly matches plan, world, region, runtime and policy", failures, evidence);

        boolean rollbackSatisfied = rollbackSatisfied(context, policy);
        pass(rollbackSatisfied, DeploymentReadinessCheck.ROLLBACK_POLICY_SATISFIED,
                DeploymentReadinessFailure.ROLLBACK_POLICY_UNSATISFIED,
                "rollback policy and verified backup are satisfied", failures, evidence);
        boolean hardGated = policy != null && !policy.forbiddenRootIdentities().isEmpty()
                && policy.forbiddenRootIdentities().stream()
                .map(DeploymentReadinessVerifier::pathKey)
                .allMatch(root -> !root.isBlank())
                && policy.forbiddenRootIdentities().stream()
                .map(DeploymentReadinessVerifier::pathKey)
                .noneMatch(root -> covers(root, pathKey(environment.gameDirectoryIdentity())));
        pass(hardGated, DeploymentReadinessCheck.FORMAL_PCL2_HARD_GATED,
                DeploymentReadinessFailure.FORMAL_WORLD_HARD_GATE_MISSING,
                "configured formal roots remain explicitly forbidden and exclude the active game directory",
                failures, evidence);
        boolean executionAllowed = policy != null && policy.executionPermitted()
                && environment.environmentType() == WorldEnvironmentType.ISOLATED_TEST_WORLD
                && environment.disposable() && environment.writable() && environment.executionAllowed()
                && policy.allowsEnvironment(environment.environmentType(),
                        environment.gameDirectoryIdentity());
        pass(executionAllowed, DeploymentReadinessCheck.EXECUTION_ENVIRONMENT_ALLOWED,
                DeploymentReadinessFailure.EXECUTION_ENVIRONMENT_NOT_ALLOWED,
                "only disposable isolated repository environment is allowed", failures, evidence);

        if (!failures.isEmpty()) {
            return refusal(context, failures);
        }
        HumanApprovalCheck consumed = approvalGate.verifyAndConsume(
                context.humanApproval(), context.humanApprovalRequest(), context.evaluatedAt());
        if (!consumed.accepted()) {
            DeploymentReadinessFailure failure =
                    consumed.failures().contains(HumanApprovalFailure.TOKEN_ALREADY_CONSUMED)
                            ? DeploymentReadinessFailure.HUMAN_APPROVAL_ALREADY_USED
                            : DeploymentReadinessFailure.HUMAN_APPROVAL_INVALID;
            return refusal(context, List.of(failure));
        }
        return new DeploymentReadinessSuccess(new DeploymentReadyPlan(
                physical, preview, environment.environmentId(), evidence,
                List.of("PW-11 all 25 checks passed", "approval consumed exactly once",
                        "isolated gate drill only; no execution authority created")));
    }

    private static boolean permissionsComplete(DeploymentReadinessContext context) {
        for (RegionAuthorizedOperation operation : context.regionRequest().requestedOperations()) {
            PermissionEvidence evidence = context.operationPermissions().get(operation);
            if (evidence == null || !evidence.permitsConstruction()) return false;
            PermissionQuery query = evidence.query();
            if (query.operation() != operation
                    || !query.region().equals(context.regionRequest().requestedBounds())
                    || !query.worldIdentity().equals(context.regionRequest().worldIdentity())
                    || query.environmentClassification()
                            != context.regionRequest().environmentClassification()
                    || !query.dimensionId().equals(context.regionRequest().dimensionId())
                    || !query.fingerprint().equals(context.currentRuntimeFingerprint())) {
                return false;
            }
        }
        return context.operationPermissions().keySet().containsAll(
                context.regionRequest().requestedOperations());
    }

    private static DeploymentReadinessRefusal refusal(
            DeploymentReadinessContext context,
            List<DeploymentReadinessFailure> failures) {
        return new DeploymentReadinessRefusal(
                failures, failures.stream().map(failure -> detail(context, failure)).toList());
    }

    private static DeploymentFailure detail(
            DeploymentReadinessContext context,
            DeploymentReadinessFailure failure) {
        DeploymentPolicy policy = context.deploymentPolicy().orElse(null);
        String risk = context.riskAssessment().highestSeverity() + ":"
                + context.riskAssessment().findings().stream()
                        .map(finding -> finding.category().name()).toList();
        String backup = context.backupPlan().map(BackupPlan::backupIdentity).orElse("MISSING");
        return new DeploymentFailure(
                code(failure), stage(failure), context.environment().worldIdentity(),
                context.environment().environmentType(),
                context.environment().gameDirectoryIdentity(), context.preview().target(),
                normalizedHash(context.preview().previewHash()),
                context.preview().affectedBounds(), policy == null ? "MISSING" : policy.policyId(),
                risk, context.humanApproval().oneTimeTokenHash(), backup,
                context.currentRuntimeFingerprint(),
                List.of("PW-11", failure.name(),
                        "executionSession=" + context.executionReadyPlan().sessionId()),
                reason(failure), userActionRequired(failure), safeNextStep(failure));
    }

    private static DeploymentFailure missingDetail(DeploymentReadinessFailure failure) {
        return new DeploymentFailure(
                code(failure), stage(failure), "MISSING", WorldEnvironmentType.UNKNOWN_WORLD,
                "MISSING", dev.stevecreate.agent.core.model.ResourceId.parse("deployment:missing"),
                "0".repeat(64), new DeploymentBoundingBox(
                        new dev.stevecreate.agent.core.model.BlockPos3i(0, 0, 0),
                        new dev.stevecreate.agent.core.model.BlockPos3i(0, 0, 0)),
                "MISSING", "UNKNOWN", "MISSING", "MISSING", "MISSING",
                List.of("PW-11", failure.name()), reason(failure), true, safeNextStep(failure));
    }

    private static String normalizedHash(String value) {
        return value.matches("[0-9a-f]{64}") ? value : "0".repeat(64);
    }

    private static DeploymentFailureCode code(DeploymentReadinessFailure failure) {
        return switch (failure) {
            case VERIFIED_PHYSICAL_PLAN_INVALID, UNIFIED_MACHINE_GRAPH_INVALID ->
                    DeploymentFailureCode.DEPLOYMENT_NOT_READY;
            case ENVIRONMENT_CLASSIFICATION_UNCLEAR ->
                    DeploymentFailureCode.WORLD_ENVIRONMENT_UNKNOWN;
            case DEPLOYMENT_POLICY_MISSING -> DeploymentFailureCode.DEPLOYMENT_POLICY_MISSING;
            case FORMAL_WORLD_POLICY_RELAXED, HIGH_RISK_UNHANDLED,
                    RESOURCE_SOURCE_INVALID -> DeploymentFailureCode.DEPLOYMENT_POLICY_VIOLATION;
            case DRY_RUN_INCOMPLETE -> DeploymentFailureCode.DRY_RUN_REQUIRED;
            case PREVIEW_HASH_INVALID -> DeploymentFailureCode.DEPLOYMENT_PREVIEW_INVALID;
            case WORLD_SNAPSHOT_STALE -> DeploymentFailureCode.WORLD_SNAPSHOT_STALE;
            case RUNTIME_FINGERPRINT_CHANGED ->
                    DeploymentFailureCode.RUNTIME_FINGERPRINT_MISMATCH;
            case REGION_AUTHORIZATION_INCOMPLETE ->
                    DeploymentFailureCode.REGION_AUTHORIZATION_INSUFFICIENT;
            case OPERATION_PERMISSION_INCOMPLETE -> DeploymentFailureCode.CLAIM_PERMISSION_UNKNOWN;
            case CRITICAL_RISK_PRESENT -> DeploymentFailureCode.CRITICAL_DEPLOYMENT_RISK;
            case DEPLOYMENT_BUDGET_EXCEEDED -> DeploymentFailureCode.RESOURCE_BUDGET_EXCEEDED;
            case POWER_CAPACITY_INVALID -> DeploymentFailureCode.POWER_BUDGET_EXCEEDED;
            case BACKUP_REQUIREMENT_UNSATISFIED -> DeploymentFailureCode.BACKUP_REQUIRED;
            case BACKUP_MANIFEST_INVALID -> DeploymentFailureCode.BACKUP_PLAN_INVALID;
            case HUMAN_APPROVAL_INVALID -> DeploymentFailureCode.HUMAN_APPROVAL_INVALID;
            case HUMAN_APPROVAL_EXPIRED -> DeploymentFailureCode.HUMAN_APPROVAL_EXPIRED;
            case HUMAN_APPROVAL_ALREADY_USED -> DeploymentFailureCode.HUMAN_APPROVAL_ALREADY_USED;
            case HUMAN_APPROVAL_SCOPE_MISMATCH ->
                    DeploymentFailureCode.HUMAN_APPROVAL_SCOPE_MISMATCH;
            case ROLLBACK_POLICY_UNSATISFIED ->
                    DeploymentFailureCode.ROLLBACK_POLICY_INSUFFICIENT;
            case FORMAL_WORLD_HARD_GATE_MISSING ->
                    DeploymentFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN;
            case EXECUTION_ENVIRONMENT_NOT_ALLOWED -> DeploymentFailureCode.FORBIDDEN_GAME_DIRECTORY;
        };
    }

    private static DeploymentFailureStage stage(DeploymentReadinessFailure failure) {
        return switch (failure) {
            case VERIFIED_PHYSICAL_PLAN_INVALID, UNIFIED_MACHINE_GRAPH_INVALID ->
                    DeploymentFailureStage.PLAN;
            case ENVIRONMENT_CLASSIFICATION_UNCLEAR -> DeploymentFailureStage.ENVIRONMENT;
            case DEPLOYMENT_POLICY_MISSING, FORMAL_WORLD_POLICY_RELAXED ->
                    DeploymentFailureStage.POLICY;
            case DRY_RUN_INCOMPLETE, PREVIEW_HASH_INVALID -> DeploymentFailureStage.DRY_RUN;
            case WORLD_SNAPSHOT_STALE, RUNTIME_FINGERPRINT_CHANGED ->
                    DeploymentFailureStage.SNAPSHOT;
            case REGION_AUTHORIZATION_INCOMPLETE, OPERATION_PERMISSION_INCOMPLETE ->
                    DeploymentFailureStage.AUTHORIZATION;
            case CRITICAL_RISK_PRESENT, HIGH_RISK_UNHANDLED -> DeploymentFailureStage.RISK;
            case DEPLOYMENT_BUDGET_EXCEEDED, RESOURCE_SOURCE_INVALID,
                    POWER_CAPACITY_INVALID -> DeploymentFailureStage.BUDGET;
            case BACKUP_REQUIREMENT_UNSATISFIED, BACKUP_MANIFEST_INVALID ->
                    DeploymentFailureStage.BACKUP;
            case HUMAN_APPROVAL_INVALID, HUMAN_APPROVAL_EXPIRED,
                    HUMAN_APPROVAL_ALREADY_USED, HUMAN_APPROVAL_SCOPE_MISMATCH ->
                    DeploymentFailureStage.APPROVAL;
            case ROLLBACK_POLICY_UNSATISFIED -> DeploymentFailureStage.ROLLBACK;
            case FORMAL_WORLD_HARD_GATE_MISSING, EXECUTION_ENVIRONMENT_NOT_ALLOWED ->
                    DeploymentFailureStage.FINAL_GATE;
        };
    }

    private static String reason(DeploymentReadinessFailure failure) {
        return "Deployment readiness check failed: " + failure.name();
    }

    private static boolean userActionRequired(DeploymentReadinessFailure failure) {
        return failure != DeploymentReadinessFailure.VERIFIED_PHYSICAL_PLAN_INVALID
                && failure != DeploymentReadinessFailure.UNIFIED_MACHINE_GRAPH_INVALID;
    }

    private static String safeNextStep(DeploymentReadinessFailure failure) {
        return switch (failure) {
            case FORMAL_WORLD_HARD_GATE_MISSING, EXECUTION_ENVIRONMENT_NOT_ALLOWED ->
                    "Keep execution disabled and use a fresh repository-owned isolated world";
            case HUMAN_APPROVAL_INVALID, HUMAN_APPROVAL_EXPIRED,
                    HUMAN_APPROVAL_ALREADY_USED, HUMAN_APPROVAL_SCOPE_MISMATCH ->
                    "Obtain a new exact-scope TEST_ONLY approval for the isolated preview";
            case BACKUP_REQUIREMENT_UNSATISFIED, BACKUP_MANIFEST_INVALID ->
                    "Create and verify a fresh isolated backup and restore drill";
            default -> "Refresh the failed typed evidence and repeat the dry-run gate";
        };
    }

    private static boolean backupRequirementSatisfied(
            DeploymentReadinessContext context,
            DeploymentPolicy policy) {
        if (policy == null) return false;
        if (!policy.backupRequired()) return true;
        if (context.backupPlan().isEmpty() || context.backupVerification().isEmpty()) return false;
        BackupPlan plan = context.backupPlan().orElseThrow();
        BackupVerification verification = context.backupVerification().orElseThrow();
        return plan.environmentClassification() == WorldEnvironmentType.ISOLATED_TEST_WORLD
                && plan.worldIdentity().equals(context.environment().worldIdentity())
                && plan.observedAvailableSpaceBytes() >= plan.requiredAvailableSpaceBytes()
                && verification.failures().stream().allMatch(MANIFEST_FAILURES::contains);
    }

    private static boolean rollbackSatisfied(
            DeploymentReadinessContext context,
            DeploymentPolicy policy) {
        if (policy == null || !policy.rollbackRequired()
                || context.backupVerification().map(BackupVerification::valid).orElse(false) == false) {
            return false;
        }
        return switch (policy.rollbackPolicy()) {
            case REQUIRED_VERIFIED -> context.preview().rollbackClassification()
                    == RollbackClassification.FULLY_REVERSIBLE;
            case CONSERVATIVE_JOURNALED_ONLY -> context.preview().rollbackClassification()
                    == RollbackClassification.FULLY_REVERSIBLE
                    || context.preview().rollbackClassification()
                            == RollbackClassification.REVERSIBLE_WITH_RESOURCE_LOSS;
            case DESTRUCTIVE_FORBIDDEN -> false;
        };
    }

    private static boolean dangerous(WorldEnvironmentType type) {
        return type == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                || type == WorldEnvironmentType.UNKNOWN_WORLD
                || type == WorldEnvironmentType.FORBIDDEN_WORLD;
    }

    private static String pathKey(String value) {
        return value.replace('\\', '/').replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    private static boolean covers(String root, String target) {
        return target.equals(root) || target.startsWith(root + "/");
    }

    private static void pass(
            boolean passed,
            DeploymentReadinessCheck check,
            DeploymentReadinessFailure failure,
            String detail,
            List<DeploymentReadinessFailure> failures,
            List<DeploymentReadinessEvidence> evidence) {
        if (passed) evidence.add(new DeploymentReadinessEvidence(check, detail));
        else failures.add(failure);
    }
}
