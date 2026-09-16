package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a live registry is allowed to contribute to an automatically derived plan.
 *
 * <p>Each refusal below corresponds to a property something downstream genuinely needs:
 * a handler that can run the step, one resource per route, an exact item the ledger can
 * reserve. Admitting a recipe that violates one produces a plan that reads correctly and
 * cannot be built.</p>
 */
class RecipeProjectionPolicyTest {
    private static final ResourceId CUTTING = id("create:cutting");
    private static final ResourceId LOG = id("minecraft:oak_log");
    private static final ResourceId STRIPPED = id("minecraft:stripped_oak_log");

    @Test
    void admitsARecipeWhoseTypeHasAVerifiedHandler() {
        RecipeProjectionPolicy.Projection projection =
                RecipeProjectionPolicy.project(candidate(CUTTING, 1, false, false, 1));

        assertThat(projection.admitted()).as(projection.code()).isTrue();
        assertThat(projection.entry().orElseThrow().capability()).isEqualTo(CUTTING);
        assertThat(projection.entry().orElseThrow().target()).isEqualTo(STRIPPED);
        // Admitted is not verified. Nothing has run this recipe, and a player asking
        // about a projected goal is entitled to be told so — this used to say true,
        // which made EXECUTION_NOT_VERIFIED a branch no input could reach.
        assertThat(projection.entry().orElseThrow().executionVerified()).isFalse();
        // The other two defaults, stated so a change to either is deliberate: one
        // machine, and the current deployment flow.
        assertThat(projection.entry().orElseThrow().physicalModuleCount()).isEqualTo(1);
        assertThat(projection.entry().orElseThrow().preparedSiteRequired()).isTrue();
    }

    @Test
    void refusesARecipeTypeWithNoHandler() {
        assertThat(RecipeProjectionPolicy.project(
                candidate(id("minecraft:crafting_shaped"), 1, false, false, 1)).code())
                .startsWith("RECIPE_TYPE_HAS_NO_HANDLER");
        assertThat(RecipeProjectionPolicy.capabilityFor(id("minecraft:crafting_shaped")))
                .isEmpty();
    }

    /** A composite route carries exactly one resource, so byproducts have nowhere to go. */
    @Test
    void refusesARecipeWithByproducts() {
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 1, false, false, 2)).code())
                .startsWith("RECIPE_HAS_MULTIPLE_OUTPUTS");
    }

    /** An exact output count is what the settlement check compares against. */
    @Test
    void refusesAProbabilisticOutput() {
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 1, false, true, 1)).code())
                .startsWith("RECIPE_OUTPUT_IS_PROBABILISTIC");
    }

    /** The ledger reserves exact identities; a tag would mean choosing for the player. */
    @Test
    void refusesATagIngredient() {
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 1, true, false, 1)).code())
                .startsWith("RECIPE_INPUT_IS_A_TAG");
    }

    @Test
    void refusesRecipesWithNoItemInputOrAnUnusableOutputCount() {
        assertThat(RecipeProjectionPolicy.project(new RecipeProjectionPolicy.RecipeCandidate(
                id("create:cutting/empty"), CUTTING, STRIPPED, 1, Map.of(), false, false, 1))
                .code()).startsWith("RECIPE_HAS_NO_ITEM_INPUTS");
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 0, false, false, 1)).code())
                .startsWith("RECIPE_OUTPUT_COUNT_OUT_OF_BOUNDS");
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 65, false, false, 1)).code())
                .startsWith("RECIPE_OUTPUT_COUNT_OUT_OF_BOUNDS");
    }

    /** Every admitted capability must be one the composite catalog already executes. */
    @Test
    void onlyAdmitsCapabilitiesTheProjectAlreadyRuns() {
        for (String type : new String[] {"create:cutting", "create:pressing", "create:milling",
                "create:crushing", "create:mixing", "create:compacting", "create:deploying",
                "create:splashing", "create:haunting", "minecraft:smoking", "minecraft:blasting"}) {
            assertThat(RecipeProjectionPolicy.capabilityFor(id(type)))
                    .as(type).isPresent();
        }
        for (String type : new String[] {"minecraft:crafting_shaped", "minecraft:smelting",
                "create:filling", "create:emptying", "create:sequenced_assembly"}) {
            assertThat(RecipeProjectionPolicy.capabilityFor(id(type))).as(type).isEmpty();
        }
    }

    /** A refusal must say which recipe and why, so a survey can be acted on. */
    @Test
    void namesTheRecipeInEveryRefusal() {
        assertThat(RecipeProjectionPolicy.project(candidate(CUTTING, 1, true, false, 1)).code())
                .contains("create:cutting/oak_log");
    }

    private static RecipeProjectionPolicy.RecipeCandidate candidate(
            ResourceId type, long outputCount, boolean tagInputs, boolean chance, int variants) {
        return new RecipeProjectionPolicy.RecipeCandidate(
                id("create:cutting/oak_log"), type, STRIPPED, outputCount,
                Map.of(LOG, 1L), tagInputs, chance, variants);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
