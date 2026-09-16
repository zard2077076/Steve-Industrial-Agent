package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class DeployerPlanTest {
    @Test
    void fixesTheDownwardOwnedDepotItemApplicationTopology() {
        DeployerPlan plan =
                DeployerPlan.cogwheel(new BlockPos3i(10, 20, 30));

        assertThat(plan.placements()).hasSize(22);
        assertThat(plan.placement(DeployerRole.DEPLOYER).facing())
                .isEqualTo(PlanBlockFacing.DOWN);
        assertThat(plan.placement(DeployerRole.DEPLOYER).rotationAxis())
                .isEqualTo(PlanBlockAxis.Z);
        assertThat(plan.interactionPosition())
                .isEqualTo(new BlockPos3i(11, 24, 28));
        assertThat(plan.placement(DeployerRole.WATER_WHEEL).position())
                .isEqualTo(new BlockPos3i(10, 23, 30));
        assertThat(plan.placement(DeployerRole.INPUT_DEPOT).position())
                .isEqualTo(new BlockPos3i(11, 23, 28));
        assertThat(new HashSet<>(plan.placementTargets()))
                .hasSize(plan.placements().size());
        assertThat(plan.process().recipeType())
                .isEqualTo(ResourceId.parse("create:deploying"));
        assertThat(plan.process().heldItemDisposition())
                .isEqualTo(HeldItemDisposition.CONSUMED);
    }

    @Test
    void supportsAnExactRetainedToolWithoutPlayerInventoryAuthority() {
        DeployingProcessSpec retained = new DeployingProcessSpec(
                ResourceId.parse("test:deploying/tool"),
                ResourceId.parse("minecraft:iron_ingot"),
                1,
                ResourceId.parse("minecraft:flint_and_steel"),
                HeldItemDisposition.RETAINED,
                ResourceId.parse("test:processed"),
                1,
                DeployerInteractionPolicy.safeDepotItemOnly(),
                100,
                200);

        assertThat(retained.heldItemDisposition())
                .isEqualTo(HeldItemDisposition.RETAINED);
        assertThat(retained.interactionPolicy().playerInventoryForbidden())
                .isTrue();
    }

    @Test
    void rejectsUnknownNbtOrArbitraryWorldAuthorityAtConstruction() {
        assertThatThrownBy(() -> new DeployerInteractionPolicy(
                        DeployerInteractionPolicy.Target.OWNED_DEPOT_ITEM,
                        PlanBlockFacing.DOWN,
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeployerInteractionPolicy(
                        DeployerInteractionPolicy.Target.OWNED_DEPOT_ITEM,
                        PlanBlockFacing.DOWN,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
