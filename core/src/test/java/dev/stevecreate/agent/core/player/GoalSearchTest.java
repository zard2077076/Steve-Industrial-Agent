package dev.stevecreate.agent.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Deciding what a player sees when the catalog no longer fits on a screen. */
class GoalSearchTest {
    private static final List<GoalCatalogEntry> REVIEWED = List.of(
            entry("minecraft:gravel", "create:milling/cobblestone"),
            entry("create:iron_sheet", "create:pressing/iron_ingot"));
    private static final List<GoalCatalogEntry> DERIVED = List.of(
            entry("minecraft:baked_potato", "minecraft:baked_potato_from_smoking"),
            entry("create:zinc_ingot", "create:blasting/raw_zinc"),
            entry("minecraft:gravel", "create:crushing/cobblestone"),
            entry("minecraft:bread", "minecraft:bread_from_smoking"));

    /**
     * Nothing typed means the curated list, not everything. Two hundred goals in no
     * particular order is not a menu.
     */
    @Test
    void anEmptyQueryShowsTheReviewedListAlone() {
        List<GoalCatalogEntry> result = GoalSearch.matching(REVIEWED, DERIVED, "  ", 20);

        assertThat(result).extracting(entry -> entry.target().toString())
                .containsExactly("create:iron_sheet", "minecraft:gravel");
    }

    @Test
    void aQueryReachesTheDerivedCatalog() {
        List<GoalCatalogEntry> result = GoalSearch.matching(REVIEWED, DERIVED, "bread", 20);

        assertThat(result).extracting(entry -> entry.target().toString())
                .containsExactly("minecraft:bread");
    }

    /** A reviewed goal has physical evidence; a projected one does not. */
    @Test
    void reviewedEntriesOutrankDerivedOnesWhateverTheySortAs() {
        List<GoalCatalogEntry> result = GoalSearch.matching(REVIEWED, DERIVED, "e", 20);

        assertThat(result.subList(0, 2)).extracting(entry -> entry.target().toString())
                .containsExactly("create:iron_sheet", "minecraft:gravel");
    }

    /**
     * The same product can be both reviewed and derivable. Offering both would put a
     * worse copy of a verified goal next to it.
     */
    @Test
    void doesNotOfferADerivedTwinOfAReviewedGoal() {
        List<GoalCatalogEntry> result = GoalSearch.matching(REVIEWED, DERIVED, "gravel", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).recipe().toString()).isEqualTo("create:milling/cobblestone");
    }

    @Test
    void matchesTheRecipeIdAndIgnoresCase() {
        assertThat(GoalSearch.matching(REVIEWED, DERIVED, "SMOKING", 20))
                .extracting(entry -> entry.target().toString())
                .containsExactly("minecraft:baked_potato", "minecraft:bread");
    }

    @Test
    void neverReturnsMoreThanTheWireCanCarry() {
        assertThat(GoalSearch.matching(REVIEWED, DERIVED, "", 1)).hasSize(1);
        assertThat(GoalSearch.matching(REVIEWED, DERIVED, "a", 2)).hasSize(2);
    }

    @Test
    void refusesAListingWithNoRoom() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> GoalSearch.matching(REVIEWED, DERIVED, "", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GoalCatalogEntry entry(String target, String recipe) {
        return new GoalCatalogEntry(
                ResourceId.parse(target), ResourceId.parse(recipe),
                ResourceId.parse("steve_industrial:capability/item_processing"),
                Map.of(ResourceId.parse("minecraft:stone"), 1L), 1, 1, true, false);
    }
}
