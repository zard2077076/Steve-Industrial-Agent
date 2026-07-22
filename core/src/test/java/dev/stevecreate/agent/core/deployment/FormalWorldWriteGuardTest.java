package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormalWorldWriteGuardTest {
    @Test
    void everyMutationEntryPointRejectsFormalWorldWithTheSameTypedCode() {
        DeploymentPolicy policy = DeploymentPolicy.formalWorldDryRunOnly("formal-root");
        for (DeploymentWriteEntryPoint entryPoint : DeploymentWriteEntryPoint.values()) {
            DeploymentGuardResult result = new FormalWorldWriteGuard().verify(new DeploymentWriteRequest(
                    descriptor(WorldEnvironmentType.FORMAL_PLAYER_WORLD, false, false), policy,
                    entryPoint, true, true, true, true, true));

            assertThat(result).isInstanceOf(DeploymentGuardFailure.class);
            assertThat(((DeploymentGuardFailure) result).code())
                    .as(entryPoint.name()).isEqualTo(DeploymentFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN);
        }
    }

    @Test
    void forbiddenAndUnknownClassificationTakePriorityOverClaimedEvidence() {
        DeploymentPolicy policy = DeploymentPolicy.formalWorldDryRunOnly("formal-root");
        for (WorldEnvironmentType type : List.of(
                WorldEnvironmentType.FORBIDDEN_WORLD, WorldEnvironmentType.UNKNOWN_WORLD)) {
            DeploymentGuardResult result = new FormalWorldWriteGuard().verify(new DeploymentWriteRequest(
                    descriptor(type, false, false), policy, DeploymentWriteEntryPoint.ADAPTER_HANDLER,
                    true, true, true, true, true));
            assertThat(((DeploymentGuardFailure) result).code())
                    .isEqualTo(DeploymentFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN);
        }
    }

    @Test
    void isolatedWritePermitRequiresEveryCurrentGate() {
        DeploymentPolicy policy = DeploymentPolicy.isolatedTestDefault("isolated-root", "formal-root");
        WorldEnvironmentDescriptor descriptor = descriptor(
                WorldEnvironmentType.ISOLATED_TEST_WORLD, true, true);
        FormalWorldWriteGuard guard = new FormalWorldWriteGuard();

        assertThat(failure(guard, descriptor, policy, false, true, true, true, true))
                .isEqualTo(DeploymentFailureCode.HUMAN_APPROVAL_REQUIRED);
        assertThat(failure(guard, descriptor, policy, true, false, true, true, true))
                .isEqualTo(DeploymentFailureCode.BACKUP_REQUIRED);
        assertThat(failure(guard, descriptor, policy, true, true, false, true, true))
                .isEqualTo(DeploymentFailureCode.WORLD_SNAPSHOT_STALE);
        assertThat(failure(guard, descriptor, policy, true, true, true, false, true))
                .isEqualTo(DeploymentFailureCode.REGION_AUTHORIZATION_MISSING);
        assertThat(failure(guard, descriptor, policy, true, true, true, true, false))
                .isEqualTo(DeploymentFailureCode.MUTATION_BUDGET_EXCEEDED);

        DeploymentGuardResult success = guard.verify(new DeploymentWriteRequest(
                descriptor, policy, DeploymentWriteEntryPoint.EXECUTOR,
                true, true, true, true, true));
        assertThat(success).isInstanceOf(DeploymentWritePermit.class);
    }

    private static DeploymentFailureCode failure(
            FormalWorldWriteGuard guard,
            WorldEnvironmentDescriptor descriptor,
            DeploymentPolicy policy,
            boolean approval,
            boolean backup,
            boolean snapshot,
            boolean region,
            boolean budget) {
        return ((DeploymentGuardFailure) guard.verify(new DeploymentWriteRequest(
                descriptor, policy, DeploymentWriteEntryPoint.EXECUTOR,
                approval, backup, snapshot, region, budget))).code();
    }

    private static WorldEnvironmentDescriptor descriptor(
            WorldEnvironmentType type,
            boolean writable,
            boolean executable) {
        return new WorldEnvironmentDescriptor(
                "environment", type, "world", "isolated-root/world", "isolated-root",
                "1.20.1", "forge", "runtime", "save", "server", type == WorldEnvironmentType.ISOLATED_TEST_WORLD,
                writable, type != WorldEnvironmentType.ISOLATED_TEST_WORLD,
                type != WorldEnvironmentType.ISOLATED_TEST_WORLD, executable,
                "test", List.of("typed fixture"), List.of());
    }
}
