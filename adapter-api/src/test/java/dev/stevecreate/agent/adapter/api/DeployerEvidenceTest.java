package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import dev.stevecreate.agent.core.plan.DeployerRole;
import dev.stevecreate.agent.core.plan.HeldItemDisposition;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeployerEvidenceTest {
    @Test
    void requiresExactHeldItemDeltaAndEveryImmediateSafetySignal() {
        DeployerPlan plan =
                DeployerPlan.cogwheel(new BlockPos3i(0, 64, 0));
        RuntimeFingerprint runtime = new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                Map.of("create", "6.0.6-150"),
                "create-v606",
                1);

        assertThat(evidence(plan, runtime, true).heldItemAfterCount())
                .isZero();
        assertThatThrownBy(() -> evidence(plan, runtime, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immediate safety");
    }

    private static DeployerEvidence evidence(
            DeployerPlan plan,
            RuntimeFingerprint runtime,
            boolean noUnknownNbtMutation) {
        return new DeployerEvidence(
                10,
                80,
                runtime,
                ResourceId.parse("minecraft:overworld"),
                plan.origin(),
                plan.placements(),
                Map.of(
                        DeployerRole.WATER_WHEEL, 8.0D,
                        DeployerRole.BOTTOM_GEARBOX, 8.0D,
                        DeployerRole.VERTICAL_SHAFT, 8.0D,
                        DeployerRole.TOP_GEARBOX, 8.0D,
                        DeployerRole.HORIZONTAL_SHAFT, 8.0D,
                        DeployerRole.DEPLOYER, 8.0D),
                plan.process().recipeId(),
                plan.process().recipeType(),
                plan.process().processedItem(),
                1,
                plan.process().heldItem(),
                1,
                0,
                HeldItemDisposition.CONSUMED,
                plan.process().expectedOutputItem(),
                1,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                noUnknownNbtMutation,
                true);
    }
}
