package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerMappingV1;
import org.junit.jupiter.api.Test;

class PlayerVerifiedMaterialPlanResolverTest {
    @Test
    void reviewedMatrixLeavesNoAdapterLocalCreativePowerBlocker() {
        assertNull(reason("create:creative_motor"));
        assertNull(reason("minecraft:stone"));
        assertThat(PlayerVerifiedMaterialPlanResolver.creativeOnlyResources()).isEmpty();
        assertEquals(CreateSurvivalPowerMappingV1.unresolvedPowerResources(),
                PlayerVerifiedMaterialPlanResolver.creativeOnlyResources());
    }

    /**
     * Belt, water, lava, fire and soul fire used to fail closed here.
     *
     * <p>They were refused for having no survival item form, which was true of the block
     * and never true of the transaction: a player hands over a full bucket, a flint and
     * steel, a belt connector. They are now bought by name, so the policy must not refuse
     * them a second time — a stale entry here would keep sixty-five products unbuildable
     * while the bill said they were payable.</p>
     */
    @Test
    void doesNotRefuseABlockThatIsNowBoughtByAnItem() {
        for (ResourceId block : PlacementItemBinding.boundBlocks()) {
            assertThat(PlayerVerifiedMaterialPlanResolver.knownUnsupportedReason(block))
                    .as("%s is bound to %s", block, PlacementItemBinding.itemFor(block))
                    .isNull();
        }
    }

    private static String reason(String id) {
        return PlayerVerifiedMaterialPlanResolver.knownUnsupportedReason(ResourceId.parse(id));
    }
}
