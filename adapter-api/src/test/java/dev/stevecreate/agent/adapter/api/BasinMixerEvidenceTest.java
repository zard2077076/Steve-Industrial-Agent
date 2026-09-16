package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import dev.stevecreate.agent.core.plan.BasinMixerRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BasinMixerEvidenceTest {
    @Test
    void requiresExactHeatForNoneAndHeatedCycles() {
        BasinMixerPlan none =
                BasinMixerPlan.andesiteAlloy(new BlockPos3i(0, 64, 0));
        BasinMixerPlan heated =
                BasinMixerPlan.brass(new BlockPos3i(10, 64, 0));

        assertThat(evidence(none, "none").heatMode())
                .isEqualTo(BasinHeatMode.NONE);
        assertThat(evidence(heated, "kindled").observedHeatLevel())
                .isEqualTo("kindled");
        assertThatThrownBy(() -> evidence(heated, "seething"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observed heat differs");
    }

    private static BasinMixerEvidence evidence(
            BasinMixerPlan plan, String heatLevel) {
        return new BasinMixerEvidence(
                10,
                20,
                new RuntimeFingerprint(
                        "1.20.1", "forge", "47.4.10",
                        Map.of("create", "6.0.6-150"),
                        "create-v606", 1),
                ResourceId.parse("minecraft:overworld"),
                plan.origin(),
                plan.placements(),
                Map.of(
                        BasinMixerRole.WATER_WHEEL, 8.0D,
                        BasinMixerRole.BOTTOM_GEARBOX, 8.0D,
                        BasinMixerRole.VERTICAL_SHAFT, 8.0D,
                        BasinMixerRole.LARGE_COGWHEEL_INPUT, 8.0D,
                        BasinMixerRole.SMALL_COGWHEEL, 16.0D,
                        BasinMixerRole.LARGE_COGWHEEL_OUTPUT, 16.0D,
                        BasinMixerRole.MECHANICAL_MIXER, 32.0D),
                plan.process().recipeId(),
                plan.process().recipeType(),
                plan.process().itemInputs(),
                plan.process().expectedOutputItem(),
                plan.process().expectedOutputCount(),
                plan.process().heatMode(),
                heatLevel,
                true,
                true,
                true,
                true,
                true);
    }
}
