package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeploymentPolicyTest {
    @Test
    void formalWorldDefaultIsDryRunOnlyAndCannotMutateOrWithdraw() {
        DeploymentPolicy policy = DeploymentPolicy.formalWorldDryRunOnly("formal-root");

        assertThat(policy.allowedEnvironmentTypes()).containsExactly(WorldEnvironmentType.FORMAL_PLAYER_WORLD);
        assertThat(policy.executionPermitted()).isFalse();
        assertThat(policy.dryRunRequired()).isTrue();
        assertThat(policy.backupRequired()).isTrue();
        assertThat(policy.humanApprovalRequired()).isTrue();
        assertThat(policy.worldMutationBudget()).isZero();
        assertThat(policy.unknownBlockReplacementPolicy()).isEqualTo(UnknownBlockReplacementPolicy.FORBIDDEN);
        assertThat(policy.playerBuiltBlockProtectionPolicy()).isEqualTo(PlayerBuiltBlockProtectionPolicy.PROTECT);
        assertThat(policy.blockEntityProtectionPolicy()).isEqualTo(BlockEntityProtectionPolicy.PROTECT);
        assertThat(policy.claimPermissionRequirement()).isEqualTo(ClaimPermissionRequirement.REQUIRED);
        assertThat(policy.resourceSourcePolicy()).isEqualTo(ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN);
        assertThat(policy.existingMachineReusePolicy()).isEqualTo(ExistingMachineReusePolicy.FORBIDDEN);
        assertThat(policy.rollbackPolicy()).isEqualTo(RollbackPolicy.DESTRUCTIVE_FORBIDDEN);
    }

    @Test
    void constructorRejectsAnyPolicyThatWouldExecuteAFormalUnknownOrForbiddenWorld() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Set.of(WorldEnvironmentType.FORMAL_PLAYER_WORLD), true, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Set.of(WorldEnvironmentType.UNKNOWN_WORLD), true, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Set.of(WorldEnvironmentType.FORBIDDEN_WORLD), true, 1));
    }

    @Test
    void mutationAndConstructionBoundsCannotExceedOrContradictEachOther() {
        assertThatIllegalArgumentException().isThrownBy(() -> policy(
                Set.of(WorldEnvironmentType.ISOLATED_TEST_WORLD), true, 129));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeploymentPolicy(
                "bad", Set.of(WorldEnvironmentType.ISOLATED_TEST_WORLD), Set.of("forbidden-root"),
                Set.of("allowed-root"), 4_097, 1, 1, 1, 1, Set.of(), Set.of(), Set.of(), Set.of(),
                Optional.empty(), true, false, true, false, Duration.ofMinutes(5), true,
                RollbackPolicy.REQUIRED_VERIFIED, UnknownBlockReplacementPolicy.FORBIDDEN,
                PlayerBuiltBlockProtectionPolicy.PROTECT, BlockEntityProtectionPolicy.PROTECT,
                ClaimPermissionRequirement.REQUIRED, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED,
                ExistingMachineReusePolicy.FORBIDDEN, 1));
    }

    @Test
    void policyIsImmutableAndTimeWindowIsExplicitlyBounded() {
        DeploymentPolicy policy = new DeploymentPolicy(
                "isolated", Set.of(WorldEnvironmentType.ISOLATED_TEST_WORLD), Set.of("forbidden-root"),
                Set.of("allowed-root"), 128, 4_096, 64, 512, 1_024,
                Set.of(id("steve:create")), Set.of(id("create:mechanical_press")),
                Set.of(id("create:pressing")), Set.of(id("minecraft:overworld")),
                Optional.of(new DeploymentTimeWindow(Instant.parse("2026-07-17T00:00:00Z"),
                        Instant.parse("2026-07-17T01:00:00Z"))), true, false, true, false,
                Duration.ofMinutes(10), true, RollbackPolicy.REQUIRED_VERIFIED,
                UnknownBlockReplacementPolicy.FORBIDDEN, PlayerBuiltBlockProtectionPolicy.PROTECT,
                BlockEntityProtectionPolicy.PROTECT, ClaimPermissionRequirement.REQUIRED,
                ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, ExistingMachineReusePolicy.ALLOWED_READ_ONLY,
                128);

        assertThat(policy.isWithinTimeWindow(Instant.parse("2026-07-17T00:30:00Z"))).isTrue();
        assertThat(policy.isWithinTimeWindow(Instant.parse("2026-07-17T02:00:00Z"))).isFalse();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> policy.allowedAdapterIds().add(id("test:bad")));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeploymentTimeWindow(
                Instant.parse("2026-07-17T01:00:00Z"), Instant.parse("2026-07-17T00:00:00Z")));
    }

    @Test
    void forbiddenRootAlwaysOverridesAnOtherwiseAllowedEnvironment() {
        DeploymentPolicy policy = DeploymentPolicy.formalWorldDryRunOnly("formal-root");
        assertThat(policy.allowsEnvironment(WorldEnvironmentType.FORMAL_PLAYER_WORLD, "formal-root/world")).isFalse();
        assertThat(policy.allowsEnvironment(WorldEnvironmentType.FORMAL_PLAYER_WORLD, "other-root/world")).isTrue();
    }

    private static DeploymentPolicy policy(
            Set<WorldEnvironmentType> environments,
            boolean executionPermitted,
            int mutationBudget) {
        return new DeploymentPolicy(
                "test", environments, Set.of("forbidden-root"), Set.of("allowed-root"),
                128, 4_096, 64, 512, 1_024, Set.of(), Set.of(), Set.of(), Set.of(),
                Optional.empty(), executionPermitted, false, true, false, Duration.ofMinutes(5), true,
                RollbackPolicy.REQUIRED_VERIFIED, UnknownBlockReplacementPolicy.FORBIDDEN,
                PlayerBuiltBlockProtectionPolicy.PROTECT, BlockEntityProtectionPolicy.PROTECT,
                ClaimPermissionRequirement.REQUIRED, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED,
                ExistingMachineReusePolicy.FORBIDDEN, mutationBudget);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
