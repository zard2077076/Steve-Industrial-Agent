package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeIngredientTest {
    @Test
    void exactResourceCarriesOneUnambiguousCandidateAndQuantity() {
        RecipeIngredient ingredient = new RecipeIngredient.ExactResource(id("minecraft:andesite"), 3);

        assertThat(ingredient.kind()).isEqualTo(RecipeIngredientKind.EXACT_RESOURCE);
        assertThat(ingredient.runtimeCandidates()).containsExactly(id("minecraft:andesite"));
        assertThat(ingredient.amount()).isEqualTo(3);
        assertThat(ingredient.canonicalIdentity()).isEqualTo("exact:minecraft:andesite@3");
    }

    @Test
    void anyOfPreservesEveryCandidateInStableOrderWithoutChoosingOne() {
        List<ResourceId> mutable = new ArrayList<>(List.of(
                id("minecraft:granite"), id("minecraft:andesite"), id("minecraft:diorite")));
        RecipeIngredient.AnyOfResources ingredient =
                new RecipeIngredient.AnyOfResources(mutable, 1);
        mutable.clear();

        assertThat(ingredient.kind()).isEqualTo(RecipeIngredientKind.ANY_OF_RESOURCES);
        assertThat(ingredient.resources()).containsExactly(
                id("minecraft:andesite"), id("minecraft:diorite"), id("minecraft:granite"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ingredient.resources().clear());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RecipeIngredient.AnyOfResources(List.of(id("minecraft:stone")), 1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RecipeIngredient.AnyOfResources(
                        List.of(id("minecraft:stone"), id("minecraft:stone")), 1));
    }

    @Test
    void tagKeepsIdentityCandidateSnapshotAndFingerprintWithoutCollapsing() {
        RecipeIngredient.TagReference ingredient = new RecipeIngredient.TagReference(
                id("forge:ingots/iron"),
                List.of(id("example:iron_ingot"), id("minecraft:iron_ingot")),
                "sha256:runtime",
                2);

        assertThat(ingredient.kind()).isEqualTo(RecipeIngredientKind.TAG_REFERENCE);
        assertThat(ingredient.tagId()).isEqualTo(id("forge:ingots/iron"));
        assertThat(ingredient.runtimeCandidates()).containsExactly(
                id("example:iron_ingot"), id("minecraft:iron_ingot"));
        assertThat(ingredient.runtimeFingerprint()).isEqualTo("sha256:runtime");
        assertThat(ingredient.canonicalIdentity()).contains("tag:forge:ingots/iron");
    }

    @Test
    void complexIngredientIsExplicitAndCannotMasqueradeAsAResourceCandidate() {
        RecipeIngredient ingredient = new RecipeIngredient.UnsupportedComplexIngredient(
                "forge:nbt", "Requires an NBT predicate", 1);

        assertThat(ingredient.kind())
                .isEqualTo(RecipeIngredientKind.UNSUPPORTED_COMPLEX_INGREDIENT);
        assertThat(ingredient.runtimeCandidates()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RecipeIngredient.UnsupportedComplexIngredient(" ", "detail", 1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RecipeIngredient.ExactResource(id("minecraft:stone"), 0));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
