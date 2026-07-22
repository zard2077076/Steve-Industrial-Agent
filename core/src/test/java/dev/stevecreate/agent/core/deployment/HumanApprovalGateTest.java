package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HumanApprovalGateTest {
    private static final Instant NOW = Instant.parse("2026-07-17T14:00:00Z");
    private static final String PREVIEW = "a".repeat(64);
    private static final String TOKEN = "b".repeat(64);

    @Test
    void explicitTestOnlyApprovalMatchesExactScopeAndIsConsumedOnce() {
        DeploymentPolicy policy = policy();
        HumanApprovalToken approval = approval(
                HumanApprovalDecision.APPROVED, NOW.plusSeconds(300), 7, policy);
        HumanApprovalRequest request = request(
                WorldEnvironmentType.ISOLATED_TEST_WORLD, "world:isolated", "snapshot:one",
                "runtime:one", 7, box(0, 0, 0, 9, 9, 9), id("fixture:target"), 3,
                8, policy, PREVIEW);
        HumanApprovalGate gate = new HumanApprovalGate();

        HumanApprovalCheck first = gate.verifyAndConsume(approval, request, NOW);
        HumanApprovalCheck repeated = gate.verifyAndConsume(approval, request, NOW);

        assertThat(first.accepted()).isTrue();
        assertThat(first.failures()).isEmpty();
        assertThat(repeated.accepted()).isFalse();
        assertThat(repeated.failures())
                .containsExactly(HumanApprovalFailure.TOKEN_ALREADY_CONSUMED);
        assertThat(approval.authorizerType()).isEqualTo(HumanApprovalAuthorizerType.TEST_ONLY);
        assertThat(approval.authorizerIdentity()).isEqualTo("TEST_ONLY");
    }

    @Test
    void planWorldRuntimeRegionTargetQuantityAndMutationChangesCannotReuseApproval() {
        DeploymentPolicy policy = policy();
        HumanApprovalToken approval = approval(
                HumanApprovalDecision.APPROVED, NOW.plusSeconds(300), 7, policy);
        HumanApprovalRequest changed = request(
                WorldEnvironmentType.FORMAL_PLAYER_WORLD, "world:other", "snapshot:changed",
                "runtime:changed", 8, box(-1, 0, 0, 9, 9, 9), id("fixture:other"), 4,
                9, policy, "c".repeat(64));

        assertThat(new HumanApprovalGate().verifyAndConsume(approval, changed, NOW).failures())
                .containsExactly(
                        HumanApprovalFailure.ENVIRONMENT_MISMATCH,
                        HumanApprovalFailure.WORLD_IDENTITY_MISMATCH,
                        HumanApprovalFailure.WORLD_SNAPSHOT_MISMATCH,
                        HumanApprovalFailure.RUNTIME_FINGERPRINT_MISMATCH,
                        HumanApprovalFailure.RELOAD_GENERATION_MISMATCH,
                        HumanApprovalFailure.REGION_MISMATCH,
                        HumanApprovalFailure.TARGET_MISMATCH,
                        HumanApprovalFailure.QUANTITY_MISMATCH,
                        HumanApprovalFailure.MUTATION_BUDGET_MISMATCH,
                        HumanApprovalFailure.PREVIEW_HASH_MISMATCH,
                        HumanApprovalFailure.TEST_ONLY_SCOPE_FORBIDDEN);
    }

    @Test
    void increasingMaterialOrBlockPolicyBudgetInvalidatesApproval() {
        DeploymentPolicy policy = policy();
        HumanApprovalToken approval = approval(
                HumanApprovalDecision.APPROVED, NOW.plusSeconds(300), 7, policy);
        DeploymentPolicy materialIncrease = withBudgets(policy, 256, 4_097);
        DeploymentPolicy blockIncrease = withBudgets(policy, 257, 4_096);

        assertThat(new HumanApprovalGate().verifyAndConsume(
                approval, exactRequest(materialIncrease, 7), NOW).failures())
                .containsExactly(HumanApprovalFailure.DEPLOYMENT_POLICY_MISMATCH);
        assertThat(new HumanApprovalGate().verifyAndConsume(
                approval, exactRequest(blockIncrease, 7), NOW).failures())
                .containsExactly(HumanApprovalFailure.DEPLOYMENT_POLICY_MISMATCH);
    }

    @Test
    void pendingRejectedExpiredAndPostReloadApprovalsFailClosed() {
        DeploymentPolicy policy = policy();
        HumanApprovalGate gate = new HumanApprovalGate();
        HumanApprovalRequest exact = exactRequest(policy, 7);

        assertThat(gate.verifyAndConsume(approval(
                HumanApprovalDecision.PENDING, NOW.plusSeconds(60), 7, policy), exact, NOW)
                .failures()).containsExactly(HumanApprovalFailure.APPROVAL_NOT_GRANTED);
        assertThat(gate.verifyAndConsume(approval(
                HumanApprovalDecision.REJECTED, NOW.plusSeconds(60), 7, policy), exact, NOW)
                .failures()).containsExactly(HumanApprovalFailure.APPROVAL_NOT_GRANTED);
        assertThat(gate.verifyAndConsume(approval(
                HumanApprovalDecision.APPROVED, NOW, 7, policy), exact, NOW)
                .failures()).containsExactly(HumanApprovalFailure.APPROVAL_EXPIRED);

        HumanApprovalToken beforeReload = approval(
                HumanApprovalDecision.APPROVED, NOW.plusSeconds(60), 7, policy);
        assertThat(new HumanApprovalGate().verifyAndConsume(
                beforeReload, exactRequest(policy, 8), NOW).failures())
                .containsExactly(HumanApprovalFailure.RELOAD_GENERATION_MISMATCH);
    }

    @Test
    void concurrentAttemptsConsumeExactlyOneToken() {
        DeploymentPolicy policy = policy();
        HumanApprovalToken approval = approval(
                HumanApprovalDecision.APPROVED, NOW.plusSeconds(60), 7, policy);
        HumanApprovalRequest request = exactRequest(policy, 7);
        HumanApprovalGate gate = new HumanApprovalGate();

        long accepted = IntStream.range(0, 16).parallel()
                .mapToObj(ignored -> gate.verifyAndConsume(approval, request, NOW))
                .filter(HumanApprovalCheck::accepted)
                .count();

        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void testOnlyApprovalCannotBeConstructedForFormalWorldAndContractIsNotBoolean() {
        DeploymentPolicy policy = policy();
        assertThatThrownBy(() -> new HumanApprovalToken(
                TOKEN, HumanApprovalDecision.APPROVED, HumanApprovalAuthorizerType.TEST_ONLY,
                "TEST_ONLY", WorldEnvironmentType.FORMAL_PLAYER_WORLD, PREVIEW, "world:formal",
                "snapshot:formal", "runtime:formal", 1, box(0, 0, 0, 1, 1, 1),
                id("fixture:target"), 1, 0, policy, NOW.plusSeconds(60), "fixture:test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanApprovalToken(
                TOKEN, HumanApprovalDecision.APPROVED, HumanApprovalAuthorizerType.TEST_ONLY,
                "someone", WorldEnvironmentType.ISOLATED_TEST_WORLD, PREVIEW, "world:isolated",
                "snapshot:one", "runtime:one", 1, box(0, 0, 0, 1, 1, 1),
                id("fixture:target"), 1, 0, policy, NOW.plusSeconds(60), "fixture:test"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Arrays.stream(HumanApprovalToken.class.getRecordComponents())
                .map(component -> component.getType()))
                .noneMatch(type -> type == boolean.class || type == Boolean.class);
        assertThat(Arrays.stream(new Class<?>[] {
                    HumanApprovalToken.class, HumanApprovalRequest.class,
                    HumanApprovalCheck.class, HumanApprovalGate.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create")
                        || name.contains("ExecutionReadyPlan")
                        || name.contains("WorldResourceBuffer"));
    }

    private static HumanApprovalToken approval(
            HumanApprovalDecision decision,
            Instant expiresAt,
            long reloadGeneration,
            DeploymentPolicy policy) {
        return new HumanApprovalToken(
                TOKEN, decision, HumanApprovalAuthorizerType.TEST_ONLY, "TEST_ONLY",
                WorldEnvironmentType.ISOLATED_TEST_WORLD, PREVIEW, "world:isolated",
                "snapshot:one", "runtime:one", reloadGeneration, box(0, 0, 0, 9, 9, 9),
                id("fixture:target"), 3, 8, policy, expiresAt, "fixture:test-only-approval");
    }

    private static HumanApprovalRequest exactRequest(
            DeploymentPolicy policy,
            long reloadGeneration) {
        return request(
                WorldEnvironmentType.ISOLATED_TEST_WORLD, "world:isolated", "snapshot:one",
                "runtime:one", reloadGeneration, box(0, 0, 0, 9, 9, 9),
                id("fixture:target"), 3, 8, policy, PREVIEW);
    }

    private static HumanApprovalRequest request(
            WorldEnvironmentType environment,
            String world,
            String snapshot,
            String runtime,
            long reloadGeneration,
            DeploymentBoundingBox region,
            ResourceId target,
            long quantity,
            int mutationBudget,
            DeploymentPolicy policy,
            String previewHash) {
        return new HumanApprovalRequest(
                environment, previewHash, world, snapshot, runtime, reloadGeneration, region,
                target, quantity, mutationBudget, policy);
    }

    private static DeploymentPolicy policy() {
        return DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root");
    }

    private static DeploymentPolicy withBudgets(
            DeploymentPolicy source,
            int maximumAffectedBlocks,
            long maximumMaterialCost) {
        return new DeploymentPolicy(
                source.policyId(), source.allowedEnvironmentTypes(), source.forbiddenRootIdentities(),
                source.allowedRootIdentities(), maximumAffectedBlocks, source.maximumBoundingVolume(),
                source.maximumRouteLength(), maximumMaterialCost,
                source.maximumRotationalStressDemand(), source.allowedAdapterIds(),
                source.allowedImplementationIds(), source.allowedRecipeTypes(),
                source.allowedDimensionIds(), source.allowedTimeWindow(), source.executionPermitted(),
                source.backupRequired(), source.dryRunRequired(), source.humanApprovalRequired(),
                source.approvalExpiry(), source.rollbackRequired(), source.rollbackPolicy(),
                source.unknownBlockReplacementPolicy(), source.playerBuiltBlockProtectionPolicy(),
                source.blockEntityProtectionPolicy(), source.claimPermissionRequirement(),
                source.resourceSourcePolicy(), source.existingMachineReusePolicy(),
                source.worldMutationBudget());
    }

    private static DeploymentBoundingBox box(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new DeploymentBoundingBox(
                new BlockPos3i(minX, minY, minZ), new BlockPos3i(maxX, maxY, maxZ));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
