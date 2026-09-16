package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedMaterialManifestTest {
    private static final ResourceId ANDESITE = ResourceId.parse("minecraft:andesite");
    private static final ResourceId SHAFT = ResourceId.parse("create:shaft");
    private static final ResourceId PLANKS = ResourceId.parse("minecraft:oak_planks");

    @Test
    void canonicalizesEveryExactInputAndComputesTheBoundedTotal() {
        Map<ResourceId, Long> unordered = new LinkedHashMap<>();
        unordered.put(PLANKS, 1L);
        unordered.put(SHAFT, 2L);
        unordered.put(ANDESITE, 3L);

        BoundedMaterialManifest manifest = BoundedMaterialManifest.from(unordered);

        assertThat(manifest.entries())
                .containsExactly(
                        new BoundedMaterialManifest.Entry(SHAFT, 2),
                        new BoundedMaterialManifest.Entry(ANDESITE, 3),
                        new BoundedMaterialManifest.Entry(PLANKS, 1));
        assertThat(manifest.totalQuantity()).isEqualTo(6);
        assertThat(manifest.entries()).isUnmodifiable();
    }

    @Test
    void rejectsEmptyDuplicateOversizedAndNonPositiveBoundaries() {
        assertThatThrownBy(() -> BoundedMaterialManifest.from(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedMaterialManifest(List.of(
                new BoundedMaterialManifest.Entry(SHAFT, 1),
                new BoundedMaterialManifest.Entry(SHAFT, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedMaterialManifest.Entry(SHAFT, 65))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedMaterialManifest.Entry(SHAFT, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreThanTheBoundedNumberOfDistinctResources() {
        Map<ResourceId, Long> excessive = new LinkedHashMap<>();
        for (int index = 0; index <= BoundedMaterialManifest.MAX_DISTINCT_RESOURCES; index++) {
            excessive.put(ResourceId.parse("test:resource_" + index), 1L);
        }

        assertThatThrownBy(() -> BoundedMaterialManifest.from(excessive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-16");
    }
}
