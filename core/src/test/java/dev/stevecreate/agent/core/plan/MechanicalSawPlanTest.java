package dev.stevecreate.agent.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class MechanicalSawPlanTest {
    @Test
    void fixesOneUpwardItemOnlySawDepotAndChestTopology() {
        MechanicalSawPlan plan = MechanicalSawPlan.at(new BlockPos3i(10, 20, 30));

        assertEquals(23, plan.placements().size());
        assertEquals(ResourceId.parse("create:water_wheel"),
                plan.placement(MechanicalSawRole.WATER_WHEEL).blockId());
        assertEquals(ResourceId.parse("minecraft:water"),
                plan.placement(MechanicalSawRole.WATER_SOURCE).blockId());
        assertEquals(
                PlanBlockFacing.UP,
                plan.placement(MechanicalSawRole.MECHANICAL_SAW).facing());
        assertEquals(
                ResourceId.parse("create:cutting"),
                plan.process().recipeType());
        assertEquals(
                ResourceId.parse("create:cutting/andesite_alloy"),
                plan.process().recipeId());
        assertEquals(
                ResourceId.parse("create:andesite_alloy"),
                plan.process().inputItem());
        assertEquals(6, plan.process().minimumOutputCount());
        assertEquals(ResourceId.parse("create:shaft"), plan.process().expectedOutputItem());
        assertEquals("forbidden", plan.process().genericSpec().extensionData()
                .get(ResourceId.parse("create:world_cutting")));
        assertEquals(
                plan.placements().size(),
                new HashSet<>(plan.placementTargets()).size());
        assertTrue(plan.preflightPositions().contains(plan.inputEntityPosition()));
    }

    @Test
    void rotatesTheOwnedTopologyWithoutChangingTheSafeUpwardFacing() {
        MechanicalSawPlan right =
                MechanicalSawPlan.at(
                        new BlockPos3i(0, 0, 0), QuarterTurn.CLOCKWISE_90);

        assertEquals(
                PlanBlockFacing.UP,
                right.placement(MechanicalSawRole.MECHANICAL_SAW).facing());
        assertEquals(
                new BlockPos3i(2, 5, 1),
                right.placement(MechanicalSawRole.MECHANICAL_SAW).position());
        assertEquals(
                PlanBlockAxis.X,
                right.placement(MechanicalSawRole.HORIZONTAL_SHAFT).rotationAxis());
    }

    @Test
    void rejectsNonCuttingOrSelfTransformingRecipes() {
        assertThrows(IllegalArgumentException.class, () -> new CuttingProcessSpec(
                ResourceId.parse("create:pressing/iron_ingot"),
                ResourceId.parse("create:pressing"),
                ResourceId.parse("minecraft:iron_ingot"),
                1,
                ResourceId.parse("create:iron_sheet"),
                1,
                100,
                100));
        assertThrows(IllegalArgumentException.class, () -> new CuttingProcessSpec(
                ResourceId.parse("create:cutting/test"),
                ResourceId.parse("create:cutting"),
                ResourceId.parse("minecraft:oak_log"),
                1,
                ResourceId.parse("minecraft:oak_log"),
                1,
                100,
                100));
    }
}
