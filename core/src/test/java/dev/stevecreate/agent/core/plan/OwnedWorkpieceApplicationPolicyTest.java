package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import org.junit.jupiter.api.Test;

class OwnedWorkpieceApplicationPolicyTest {
    private static final ResourceId PLAN = id("plan:c10_owned_workpiece");
    private static final ResourceId SESSION = id("session:c10_owned_workpiece");
    private static final BlockPos3i WORKPIECE = new BlockPos3i(5, 65, 5);
    private static final BlockPos3i MINIMUM = new BlockPos3i(0, 60, 0);
    private static final BlockPos3i MAXIMUM = new BlockPos3i(10, 70, 10);

    @Test
    void reviewedCasingFactoryBindsExactPlanSessionRecipeAndPosition() {
        OwnedWorkpieceApplicationPolicy policy = policy();

        assertThat(policy.verifiedPhysicalPlanId()).isEqualTo(PLAN);
        assertThat(policy.sessionId()).isEqualTo(SESSION);
        assertThat(policy.recipeId())
                .isEqualTo(id("create:item_application/andesite_casing_from_log"));
        assertThat(policy.initialBlock()).isEqualTo(id("minecraft:stripped_oak_log"));
        assertThat(policy.heldItem()).isEqualTo(id("create:andesite_alloy"));
        assertThat(policy.resultBlock()).isEqualTo(id("create:andesite_casing"));
        assertThat(policy.workpiecePosition()).isEqualTo(WORKPIECE);
        assertThat(policy.contains(WORKPIECE)).isTrue();
    }

    @Test
    void everyUnrelatedInteractionSurfaceRemainsForbidden() {
        OwnedWorkpieceApplicationPolicy policy = policy();

        assertThat(policy.arbitraryPositionUseForbidden()).isTrue();
        assertThat(policy.containerOpeningForbidden()).isTrue();
        assertThat(policy.entityInteractionForbidden()).isTrue();
        assertThat(policy.combatForbidden()).isTrue();
        assertThat(policy.playerInventoryForbidden()).isTrue();
        assertThat(policy.privateStorageForbidden()).isTrue();
        assertThat(policy.unknownNbtMutationForbidden()).isTrue();
        assertThat(policy.unknownBlockEntityMutationForbidden()).isTrue();
        assertThat(policy.unknownWorldSideEffectsForbidden()).isTrue();
    }

    @Test
    void unreviewedRecipeOrResourceCombinationIsRejected() {
        assertThatThrownBy(() -> copy(
                id("create:item_application/brass_casing_from_log"),
                id("minecraft:stripped_oak_log"),
                id("create:brass_ingot"),
                id("create:brass_casing"),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only the reviewed");
    }

    @Test
    void unsafeAuthorityOrOutOfRegionWorkpieceIsRejected() {
        assertThatThrownBy(() -> copy(
                id("create:item_application/andesite_casing_from_log"),
                id("minecraft:stripped_oak_log"),
                id("create:andesite_alloy"),
                id("create:andesite_casing"),
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unrelated interaction");
        assertThatThrownBy(() -> OwnedWorkpieceApplicationPolicy.andesiteCasing(
                PLAN, SESSION, new BlockPos3i(11, 65, 5), MINIMUM, MAXIMUM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    private static OwnedWorkpieceApplicationPolicy policy() {
        return OwnedWorkpieceApplicationPolicy.andesiteCasing(
                PLAN, SESSION, WORKPIECE, MINIMUM, MAXIMUM);
    }

    private static OwnedWorkpieceApplicationPolicy copy(
            ResourceId recipe,
            ResourceId initial,
            ResourceId held,
            ResourceId result,
            boolean privateStorageForbidden) {
        return new OwnedWorkpieceApplicationPolicy(
                PLAN, SESSION, recipe, initial, held, result,
                WORKPIECE, MINIMUM, MAXIMUM,
                true, true, true, true, true, privateStorageForbidden,
                true, true, true);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
