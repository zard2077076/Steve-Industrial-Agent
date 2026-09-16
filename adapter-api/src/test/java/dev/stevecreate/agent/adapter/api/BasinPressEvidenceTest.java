package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinPressPlan;
import dev.stevecreate.agent.core.plan.BasinPressRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BasinPressEvidenceTest {
    @Test
    void requiresRealPressCycleBasinAndFluidSafetyEvidence() {
        BasinPressPlan plan =
                BasinPressPlan.blazeCakeBase(new BlockPos3i(0, 64, 0));
        RuntimeFingerprint runtime = new RuntimeFingerprint(
                "1.20.1", "forge", "47.4.10",
                Map.of("create", "6.0.6-150"), "create-v606", 1);

        assertThatThrownBy(() -> evidence(plan, runtime, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fluid-safety");
        assertThat(evidence(plan, runtime, true).consumedInputs()).hasSize(3);
    }

    private static BasinPressEvidence evidence(
            BasinPressPlan plan, RuntimeFingerprint runtime, boolean fluidEmpty) {
        return new BasinPressEvidence(
                10, 80, runtime, ResourceId.parse("minecraft:overworld"),
                plan.origin(), plan.placements(),
                Map.of(
                        BasinPressRole.WATER_WHEEL, 8.0D,
                        BasinPressRole.BOTTOM_GEARBOX, 8.0D,
                        BasinPressRole.VERTICAL_SHAFT, 8.0D,
                        BasinPressRole.TOP_GEARBOX, 8.0D,
                        BasinPressRole.HORIZONTAL_SHAFT, 8.0D,
                        BasinPressRole.MECHANICAL_PRESS, 8.0D),
                plan.process().recipeId(), plan.process().recipeType(),
                plan.process().itemInputs(), plan.process().expectedOutputItem(), 1,
                BasinHeatMode.NONE, true, true, true, fluidEmpty);
    }
}
