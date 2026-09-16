package dev.stevecreate.agent.core.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class GoalCatalogEntryTest {
    @Test
    void estimatesWholeBatchesWithoutHidingOverproduction() {
        GoalCatalogEntry entry = entry(3, Map.of(id("minecraft:andesite"), 2L));

        var estimate = entry.estimate(4);

        assertThat(estimate.batches()).isEqualTo(2);
        assertThat(estimate.plannedOutput()).isEqualTo(6);
        assertThat(estimate.requiredInputs()).containsEntry(id("minecraft:andesite"), 4L);
    }

    @Test
    void rejectsQuantityAndMaterialBudgetsBeyondOneBoundedProjectStack() {
        GoalCatalogEntry entry = entry(1, Map.of(id("minecraft:andesite"), 2L));

        assertThatThrownBy(() -> entry.estimate(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.estimate(64)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material estimate");
    }

    @Test
    void retainsBoundedFluidInputsSeparatelyAndRejectsInvalidAmounts() {
        ResourceId water = id("minecraft:water");
        GoalCatalogEntry entry = fluidEntry(Map.of(water, 250L));

        assertThat(entry.fluidInputsPerBatch()).containsExactlyEntriesOf(Map.of(water, 250L));
        assertThat(entry.inputsPerBatch()).doesNotContainKey(water);
        assertThatThrownBy(() -> fluidEntry(Map.of(water, 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded player project contract");
        assertThatThrownBy(() -> fluidEntry(Map.of(water, 64_001L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded player project contract");
    }

    private static GoalCatalogEntry entry(long output, Map<ResourceId, Long> inputs) {
        return new GoalCatalogEntry(id("minecraft:gravel"), id("create:milling/cobblestone"),
                id("create:milling"), inputs, output, 1, true, true);
    }

    private static GoalCatalogEntry fluidEntry(Map<ResourceId, Long> fluids) {
        return new GoalCatalogEntry(
                id("create:pulp"), id("create:mixing/cardboard_pulp"),
                id("create:mixing"), Map.of(id("minecraft:sugar_cane"), 1L),
                1, 1, true, true, OptionalLong.of(100), Map.of(), fluids);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
