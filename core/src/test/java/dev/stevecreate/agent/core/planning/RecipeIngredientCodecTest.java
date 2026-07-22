package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeIngredientCodecTest {
    private final RecipeIngredientCodec codec = new RecipeIngredientCodec();

    @Test
    void roundTripsEveryIngredientKindCanonically() {
        List<RecipeIngredient> ingredients = List.of(
                new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                new RecipeIngredient.AnyOfResources(
                        List.of(id("test:zinc"), id("test:copper")), 2),
                new RecipeIngredient.TagReference(
                        id("forge:ingots/iron"),
                        List.of(id("minecraft:iron_ingot"), id("example:iron_ingot")),
                        "sha256:runtime",
                        1),
                new RecipeIngredient.UnsupportedComplexIngredient(
                        "forge:nbt", "Requires NBT equality", 1));

        for (RecipeIngredient ingredient : ingredients) {
            assertThat(codec.decode(codec.encode(ingredient))).isEqualTo(ingredient);
        }
    }

    @Test
    void candidateInsertionOrderCannotChangeEncoding() {
        RecipeIngredient first = new RecipeIngredient.AnyOfResources(
                List.of(id("test:zinc"), id("test:copper")), 1);
        RecipeIngredient second = new RecipeIngredient.AnyOfResources(
                List.of(id("test:copper"), id("test:zinc")), 1);

        assertThat(codec.encode(first)).containsExactly(codec.encode(second));
    }

    @Test
    void rejectsCorruptTruncatedAndTrailingFrames() {
        byte[] valid = codec.encode(new RecipeIngredient.ExactResource(id("test:item"), 1));
        byte[] corrupt = valid.clone();
        corrupt[12] ^= 0x01;
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(corrupt));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(truncated));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(trailing));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
