package dev.stevecreate.agent.forge1201.player;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerGoalCatalogTest {
    @Test
    void exposesExactlyElevenReviewedExecutableGoals() {
        assertThat(PlayerGoalCatalog.entries()).hasSize(11)
                .allSatisfy(entry -> {
                    assertThat(entry.executionVerified()).isTrue();
                    assertThat(entry.estimate(1).requiredInputs()).isNotEmpty();
                    assertThat(PlayerGoalCatalog.verifiedQuantity(entry)).isBetween(1, 6);
                    assertThat(PlayerGoalCatalog.find(entry.target())).contains(entry);
                    assertThat(PlayerGoalCatalog.translationKey(entry))
                            .startsWith("goal.steve_create_agent.");
                });
        assertThat(PlayerGoalCatalog.entries()).extracting(entry -> entry.target().toString())
                .doesNotHaveDuplicates()
                .contains("minecraft:cooked_beef", "minecraft:blackstone",
                        "minecraft:iron_ingot", "create:cogwheel", "create:shaft");

        GoalCatalogEntry shaft = PlayerGoalCatalog.find(ResourceId.parse("create:shaft"))
                .orElseThrow();
        assertThat(shaft.recipe()).isEqualTo(ResourceId.parse("create:cutting/andesite_alloy"));
        assertThat(shaft.inputsPerBatch()).containsExactlyEntriesOf(
                Map.of(ResourceId.parse("create:andesite_alloy"), 1L));
        assertThat(shaft.outputPerBatch()).isEqualTo(6);
        assertThat(PlayerGoalCatalog.verifiedQuantity(shaft)).isEqualTo(6);
    }
}
