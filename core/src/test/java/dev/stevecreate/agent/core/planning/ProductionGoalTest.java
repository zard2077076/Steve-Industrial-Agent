package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionGoalTest {
    @Test
    void constructsAnImmutableValidatedItemGoal() {
        Set<String> allowed = new LinkedHashSet<>(Set.of("create"));
        Set<String> forbidden = new LinkedHashSet<>(Set.of("mekanism"));
        Set<ResourceId> forbiddenMaterials = new LinkedHashSet<>(Set.of(id("test:slag")));
        Map<ResourceId, Long> maximumMaterials = new LinkedHashMap<>(Map.of(id("minecraft:iron_ingot"), 64L));
        List<PlanningStrategyPreference> preferences = new ArrayList<>(List.of(
                PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME));
        Map<ResourceId, Long> owned = new LinkedHashMap<>(Map.of(id("minecraft:cobblestone"), 5L));

        ProductionGoal goal = new ProductionGoal(
                id("create:iron_sheet"),
                GenericResourceType.ITEM,
                12,
                allowed,
                forbidden,
                Optional.of(8),
                new MaterialConstraints(forbiddenMaterials, maximumMaterials),
                preferences,
                owned);

        allowed.add("example");
        forbidden.add("other");
        forbiddenMaterials.add(id("test:waste"));
        maximumMaterials.put(id("minecraft:coal"), 2L);
        preferences.clear();
        owned.put(id("minecraft:iron_ingot"), 3L);

        assertThat(goal.target()).isEqualTo(id("create:iron_sheet"));
        assertThat(goal.quantity()).isEqualTo(12);
        assertThat(goal.allowedModIds()).containsExactly("create");
        assertThat(goal.forbiddenModIds()).containsExactly("mekanism");
        assertThat(goal.maximumProcessingDepth()).contains(8);
        assertThat(goal.materialConstraints().forbiddenResources()).containsExactly(id("test:slag"));
        assertThat(goal.materialConstraints().maximumConsumption())
                .containsExactly(Map.entry(id("minecraft:iron_ingot"), 64L));
        assertThat(goal.strategyPreferences()).containsExactly(
                PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME);
        assertThat(goal.ownedResources()).containsExactly(Map.entry(id("minecraft:cobblestone"), 5L));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> goal.allowedModIds().add("bad"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> goal.ownedResources().put(id("test:item"), 1L));
    }

    @Test
    void rejectsInvalidQuantityTypeDepthAndResourceAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> goal(0, GenericResourceType.ITEM, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> goal(-1, GenericResourceType.ITEM, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> goal(1, GenericResourceType.FLUID, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> goal(1, GenericResourceType.ITEM, Optional.of(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> goal(1, GenericResourceType.ITEM, Optional.of(65)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductionGoal(
                id("minecraft:gravel"), GenericResourceType.ITEM, 1,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(), List.of(),
                Map.of(id("minecraft:cobblestone"), 0L)));
    }

    @Test
    void rejectsConflictingOrInvalidModAndMaterialConstraints() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductionGoal(
                id("minecraft:gravel"), GenericResourceType.ITEM, 1,
                Set.of("create"), Set.of("create"), Optional.empty(),
                MaterialConstraints.none(), List.of(), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductionGoal(
                id("minecraft:gravel"), GenericResourceType.ITEM, 1,
                Set.of("Create"), Set.of(), Optional.empty(),
                MaterialConstraints.none(), List.of(), Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MaterialConstraints(
                Set.of(id("minecraft:coal")), Map.of(id("minecraft:coal"), 4L)));
        assertThatIllegalArgumentException().isThrownBy(() -> new MaterialConstraints(
                Set.of(), Map.of(id("minecraft:coal"), -1L)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProductionGoal(
                id("minecraft:gravel"), GenericResourceType.ITEM, 1,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(),
                List.of(PlanningStrategyPreference.MINIMIZE_STEPS,
                        PlanningStrategyPreference.MINIMIZE_STEPS), Map.of()));
    }

    private static ProductionGoal goal(
            long quantity,
            GenericResourceType type,
            Optional<Integer> depth) {
        return new ProductionGoal(
                id("minecraft:gravel"), type, quantity,
                Set.of(), Set.of(), depth, MaterialConstraints.none(), List.of(), Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
