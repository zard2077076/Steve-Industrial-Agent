package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifiedProjectMaterialPlanFactoryTest {
    @Test
    void producesCompleteDeterministicBomAndSubtractsOnlyVerifiedReuse() {
        BlockPos3i reused = new BlockPos3i(1, 2, 3);
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_c03"),
                List.of(
                        component("create:millstone", reused),
                        component("create:millstone", new BlockPos3i(2, 2, 3)),
                        component("create:water_wheel", new BlockPos3i(3, 2, 3))),
                List.of(new BlockPos3i(4, 2, 3), new BlockPos3i(5, 2, 3)),
                List.of(new BlockPos3i(3, 3, 3), new BlockPos3i(4, 3, 3)));
        var request = new VerifiedProjectMaterialPlanFactory.Request(
                id("project:c03"), id("minecraft:wheat_flour"), 1,
                "forge47.4.10|create6.0.6|reload0", snapshot,
                Map.of(id("minecraft:wheat"), 1L),
                Map.of(id("create:belt_connector"), 1L),
                Map.of(id("create:shaft"), 2L),
                Map.of(), Map.of(),
                Map.of(id("minecraft:wrench"), 1L), Set.of(reused));

        VerifiedProjectMaterialPlan first = new VerifiedProjectMaterialPlanFactory().create(request);
        VerifiedProjectMaterialPlan second = new VerifiedProjectMaterialPlanFactory().create(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.legacyRequirementTotals()).containsExactlyInAnyOrderEntriesOf(Map.of(
                id("create:millstone"), 1L,
                id("create:water_wheel"), 1L,
                id("create:belt_connector"), 1L,
                id("create:shaft"), 2L,
                id("minecraft:wheat"), 1L,
                id("minecraft:wrench"), 1L));
        assertThat(first.installedTotals()).containsEntry(id("create:millstone"), 1L)
                .containsEntry(id("create:shaft"), 2L);
        assertThat(first.consumedTotals()).containsOnlyKeys(id("minecraft:wheat"));
        assertThat(first.leasedTotals()).containsOnlyKeys(id("minecraft:wrench"));
        assertThat(first.planSha256()).hasSize(64);
    }

    @Test
    void refusesUncostedRoutesAndUnverifiedReuse() {
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.fixture(
                id("layout:verified_c04"),
                List.of(component("create:mechanical_press", new BlockPos3i(0, 0, 0))),
                List.of(new BlockPos3i(1, 0, 0)), List.of(new BlockPos3i(2, 0, 0)));

        assertThatThrownBy(() -> new VerifiedProjectMaterialPlanFactory().create(request(
                snapshot, Map.of(), Map.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routes require explicit");
        assertThatThrownBy(() -> request(snapshot,
                Map.of(id("create:belt_connector"), 1L),
                Map.of(id("create:shaft"), 1L),
                Set.of(new BlockPos3i(99, 99, 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a verified component");
    }

    private static VerifiedProjectMaterialPlanFactory.Request request(
            VerifiedPlanMaterialSnapshot snapshot,
            Map<ResourceId, Long> routes,
            Map<ResourceId, Long> power,
            Set<BlockPos3i> reuse) {
        return new VerifiedProjectMaterialPlanFactory.Request(
                id("project:test"), id("create:iron_sheet"), 1, "runtime", snapshot,
                Map.of(id("minecraft:iron_ingot"), 1L), routes, power,
                Map.of(), Map.of(), Map.of(), reuse);
    }

    private static ResolvedGeometryComponent component(String id, BlockPos3i position) {
        return new ResolvedGeometryComponent(
                ResourceId.parse("fixture:component"), ResourceId.parse(id), position, Map.of());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
