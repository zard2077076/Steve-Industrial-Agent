package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeployerInteractionPolicyTest {
    @Test
    void permitsOnlyDownwardProcessingOfTheOwnedDepotItem() {
        var policy = DeployerInteractionPolicy.safeDepotItemOnly();
        assertThat(policy.target())
                .isEqualTo(
                        DeployerInteractionPolicy.Target.OWNED_DEPOT_ITEM);
        assertThat(policy.interactionFace())
                .isEqualTo(PlanBlockFacing.DOWN);
    }

    @Test
    void rejectsEveryForbiddenAuthorityIndependently() {
        for (int unsafe = 0; unsafe < 8; unsafe++) {
            int field = unsafe;
            assertThatThrownBy(() -> policyWithOneUnsafeField(field))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("C-10 Phase I permits only");
        }
    }

    @Test
    void rejectsAnyNonDownwardInteractionFace() {
        assertThatThrownBy(() -> new DeployerInteractionPolicy(
                        DeployerInteractionPolicy.Target.OWNED_DEPOT_ITEM,
                        PlanBlockFacing.NORTH,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeployerInteractionPolicy policyWithOneUnsafeField(
            int field) {
        return new DeployerInteractionPolicy(
                DeployerInteractionPolicy.Target.OWNED_DEPOT_ITEM,
                PlanBlockFacing.DOWN,
                field != 0,
                field != 1,
                field != 2,
                field != 3,
                field != 4,
                field != 5,
                field != 6,
                field != 7);
    }
}
