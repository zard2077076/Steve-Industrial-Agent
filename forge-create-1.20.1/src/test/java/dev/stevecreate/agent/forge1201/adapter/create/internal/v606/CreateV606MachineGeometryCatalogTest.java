package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CreateV606MachineGeometryCatalogTest {
    private static final String FINGERPRINT = "create=6.0.6;forge=47.4.10;minecraft=1.20.1";

    @Test
    void publishesOnlyTheTwoPhysicallyVerifiedImplementations() {
        var catalog = CreateV606MachineGeometryCatalog.create(FINGERPRINT);

        assertThat(catalog.runtimeFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(catalog.descriptors()).containsOnlyKeys(
                CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID);
        assertThat(catalog.descriptors().values()).allSatisfy(descriptor -> {
            assertThat(descriptor.supportedOrientations()).containsExactlyInAnyOrder(
                    QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270);
            assertThat(descriptor.portRules()).extracting(value -> value.resourceType().serializedName())
                    .contains("item", "rotational_power");
            assertThat(descriptor.footprint().cells()).isEqualTo(
                    descriptor.components().stream()
                            .map(value -> value.relativePosition()).collect(Collectors.toSet()));
        });
    }

    @Test
    void millstoneGeometryMatchesTheAcceptedC03PhysicalTopology() {
        MachineGeometryDescriptor geometry = CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID)
                .orElseThrow();
        WaterWheelMillstonePlan c03 = WaterWheelMillstonePlan.at(new BlockPos3i(20, 70, 20));

        assertThat(geometry.components()).hasSize(c03.placements().size());
        assertThat(geometry.components().stream().map(value -> value.blockId()).collect(Collectors.toSet()))
                .isEqualTo(c03.placements().stream().map(value -> value.blockId()).collect(Collectors.toSet()));
        assertThat(geometry.footprint().cells()).isEqualTo(
                c03.placements().stream()
                        .map(value -> value.position().translate(-20, -70, -20))
                        .collect(Collectors.toSet()));
        assertThat(geometry.topologyContract())
                .contains("water_wheel", "gearbox", "vertical_shaft", "millstone");
    }

    @Test
    void pressGeometryMatchesTheAcceptedC04FinalTopologyWithoutCopyingItsBuildPlan() {
        MachineGeometryDescriptor geometry = CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID)
                .orElseThrow();
        BeltPressPlan c04 = BeltPressPlan.at(new BlockPos3i(30, 80, 30));

        Set<BlockPos3i> c04Relative = c04.finalPlacements().stream()
                .map(value -> value.position().translate(-30, -80, -30))
                .collect(Collectors.toSet());
        assertThat(geometry.components()).hasSize(c04.finalPlacements().size());
        assertThat(geometry.footprint().cells()).isEqualTo(c04Relative);
        assertThat(geometry.components().stream().map(value -> value.blockId()).collect(Collectors.toSet()))
                .isEqualTo(c04.finalPlacements().stream().map(value -> value.blockId()).collect(Collectors.toSet()));
        assertThat(geometry.topologyContract())
                .contains("three_segment_belt", "press_motor", "funnel", "chest");
    }
}
