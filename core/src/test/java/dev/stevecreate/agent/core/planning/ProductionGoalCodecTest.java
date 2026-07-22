package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionGoalCodecTest {
    @Test
    void roundTripsCanonicallyAcrossSetAndMapInsertionOrder() {
        ProductionGoal first = goal(
                new LinkedHashSet<>(List.of("create", "example")),
                linkedMap(id("minecraft:cobblestone"), 8L, id("minecraft:iron_ingot"), 3L));
        ProductionGoal reordered = goal(
                new LinkedHashSet<>(List.of("example", "create")),
                linkedMap(id("minecraft:iron_ingot"), 3L, id("minecraft:cobblestone"), 8L));

        byte[] encoded = ProductionGoalCodec.encode(first);
        ProductionGoal decoded = ProductionGoalCodec.decode(encoded);

        assertThat(decoded).isEqualTo(first).isEqualTo(reordered);
        assertThat(ProductionGoalCodec.encode(decoded)).containsExactly(encoded);
        assertThat(ProductionGoalCodec.encode(reordered)).containsExactly(encoded);
    }

    @Test
    void rejectsCorruptTruncatedTrailingAndOversizedFrames() {
        byte[] encoded = ProductionGoalCodec.encode(goal(Set.of("create"), Map.of()));
        byte[] corrupt = Arrays.copyOf(encoded, encoded.length);
        corrupt[corrupt.length - 1] ^= 0x01;

        assertThatIllegalArgumentException().isThrownBy(() -> ProductionGoalCodec.decode(corrupt));
        assertThatIllegalArgumentException().isThrownBy(() -> ProductionGoalCodec.decode(
                Arrays.copyOf(encoded, encoded.length - 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> ProductionGoalCodec.decode(
                Arrays.copyOf(encoded, encoded.length + 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> ProductionGoalCodec.decode(
                new byte[ProductionGoalCodec.MAX_ENCODED_BYTES + 32]));
    }

    private static ProductionGoal goal(Set<String> allowed, Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id("create:iron_sheet"),
                GenericResourceType.ITEM,
                7,
                allowed,
                Set.of("mekanism"),
                Optional.of(6),
                new MaterialConstraints(
                        Set.of(id("test:forbidden_dust")),
                        Map.of(id("minecraft:iron_ingot"), 32L)),
                List.of(
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                        PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME),
                owned);
    }

    private static Map<ResourceId, Long> linkedMap(
            ResourceId firstKey, long firstValue,
            ResourceId secondKey, long secondValue) {
        Map<ResourceId, Long> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
