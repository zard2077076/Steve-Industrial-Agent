package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import dev.stevecreate.agent.core.plan.FanProcessingPlan;
import dev.stevecreate.agent.core.plan.FanProcessingRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FanProcessingEvidenceTest {
    @Test
    void dangerousMediumRequiresExplicitBotExclusionEvidence() {
        FanProcessingPlan plan = FanProcessingPlan.forProcess(
                dev.stevecreate.agent.core.plan.PlanAnchor.at(new BlockPos3i(0, 64, 0)),
                new dev.stevecreate.agent.core.plan.FanProcessingSpec(
                        ResourceId.parse("create:haunting/blackstone"),
                        FanProcessingMode.HAUNTING.recipeType(),
                        ResourceId.parse("minecraft:cobblestone"), 1,
                        ResourceId.parse("minecraft:blackstone"), 1,
                        100, 200));
        assertThatThrownBy(() -> new FanProcessingEvidence(
                10, 30,
                new RuntimeFingerprint(
                        "1.20.1", "forge", "47.4.10",
                        Map.of("create", "6.0.6-150"), "create-v606", 1),
                ResourceId.parse("minecraft:overworld"),
                plan.origin(),
                FanProcessingMode.HAUNTING,
                plan.placements(),
                Map.of(
                        FanProcessingRole.WATER_WHEEL, 8.0D,
                        FanProcessingRole.BOTTOM_GEARBOX, 8.0D,
                        FanProcessingRole.VERTICAL_SHAFT, 8.0D,
                        FanProcessingRole.TOP_GEARBOX, 8.0D,
                        FanProcessingRole.FAN_DRIVE_SHAFT, 8.0D,
                        FanProcessingRole.ENCASED_FAN, 64.0D),
                "east", 3.0F, ResourceId.parse("minecraft:soul_fire"),
                plan.process().recipeId(), plan.process().recipeType(),
                plan.process().inputItem(), 1,
                plan.process().expectedOutputItem(), 1,
                20, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bot safety");
    }
}
