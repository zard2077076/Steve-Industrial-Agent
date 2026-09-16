package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BasinPressPlan;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import dev.stevecreate.agent.core.plan.FanProcessingPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.player.CreateCapabilityContractV1;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerMappingV1;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CreateV606MachineGeometryCatalogTest {
    private static final String FINGERPRINT = "create=6.0.6;forge=47.4.10;minecraft=1.20.1";

    @Test
    void publishesTheElevenBoundedCreateImplementations() {
        var catalog = CreateV606MachineGeometryCatalog.create(FINGERPRINT);

        assertThat(catalog.runtimeFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(catalog.descriptors()).containsOnlyKeys(
                CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.FAN_WASHING_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.FAN_SMOKING_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.FAN_HAUNTING_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.FAN_BLASTING_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog
                        .BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog
                        .BASIN_MIXER_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog
                        .DEPLOYER_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID,
                CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID);
        assertThat(catalog.descriptors().values()).allSatisfy(descriptor -> {
            assertThat(descriptor.supportedOrientations()).containsExactlyInAnyOrder(
                    QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90,
                    QuarterTurn.CLOCKWISE_180, QuarterTurn.CLOCKWISE_270);
            assertThat(descriptor.portRules()).extracting(value -> value.resourceType().serializedName())
                    .contains("item", "rotational_power");
            assertThat(descriptor.footprint().cells()).isEqualTo(
                    descriptor.components().stream()
                            .map(value -> value.relativePosition()).collect(Collectors.toSet()));
        });
    }

    @Test
    void deployingGeometryLocksDownwardOwnedDepotItemProcessing() {
        MachineGeometryDescriptor geometry =
                CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                        .find(CreateRuntimeMachineImplementationCatalog
                                .DEPLOYER_IMPLEMENTATION_ID)
                        .orElseThrow();
        DeployerPlan c10 =
                DeployerPlan.cogwheel(
                        new BlockPos3i(80, 90, 80));

        assertThat(geometry.components())
                .hasSize(c10.placements().size());
        assertThat(geometry.footprint().cells()).isEqualTo(
                c10.placements().stream()
                        .map(value -> value.position()
                                .translate(-80, -90, -80))
                        .collect(Collectors.toSet()));
        assertThat(geometry.components())
                .filteredOn(value ->
                        value.roleId().path().endsWith("/deployer"))
                .singleElement()
                .satisfies(value -> assertThat(value.blockState())
                        .containsEntry("facing", "down")
                        .containsEntry("axis", "z"));
        assertThat(geometry.topologyContract())
                .contains(
                        "exact_held_item",
                        "owned_depot",
                        "entity_block_container_player_inventory_nbt=forbidden");
    }

    @Test
    void compactingGeometryIsBasinPressAndNeverTheC04BeltTopology() {
        MachineGeometryDescriptor geometry =
                CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                        .find(CreateRuntimeMachineImplementationCatalog
                                .BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID)
                        .orElseThrow();
        BasinPressPlan c09 =
                BasinPressPlan.blazeCakeBase(new BlockPos3i(60, 90, 60));

        assertThat(geometry.components()).hasSize(c09.placements().size());
        assertThat(geometry.footprint().cells()).isEqualTo(
                c09.placements().stream()
                        .map(value -> value.position().translate(-60, -90, -60))
                        .collect(Collectors.toSet()));
        assertThat(geometry.components())
                .extracting(value -> value.blockId().toString())
                .contains("create:basin", "create:mechanical_press")
                .doesNotContain("create:belt");
        assertThat(geometry.topologyContract())
                .contains("item_only_basin", "real_mechanical_press_cycle",
                        "fluids=forbidden", "heat=none",
                        "belt_pressing=forbidden");
    }

    @Test
    void mixingGeometryOwnsMixerBasinAndExactHeatSource() {
        MachineGeometryDescriptor geometry =
                CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                        .find(CreateRuntimeMachineImplementationCatalog
                                .BASIN_MIXER_IMPLEMENTATION_ID)
                        .orElseThrow();
        BasinMixerPlan c08 =
                BasinMixerPlan.andesiteAlloy(
                        new BlockPos3i(70, 90, 70));

        assertThat(geometry.components())
                .hasSize(c08.placements().size());
        assertThat(geometry.footprint().cells()).isEqualTo(
                c08.placements().stream()
                        .map(value -> value.position()
                                .translate(-70, -90, -70))
                        .collect(Collectors.toSet()));
        assertThat(geometry.components())
                .extracting(value -> value.blockId().toString())
                .contains(
                        "create:mechanical_mixer",
                        "create:basin",
                        "create:blaze_burner")
                .doesNotContain("create:mechanical_press");
        assertThat(geometry.topologyContract())
                .contains(
                        "counted_item_only_basin",
                        "heat=none_or_kindled",
                        "superheated=forbidden",
                        "unknown_nbt=forbidden");
    }

    @Test
    void fanGeometryMatchesTheContainedC06TopologyAndMarksDangerousMedia() {
        MachineGeometryDescriptor washing = CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.FAN_WASHING_IMPLEMENTATION_ID)
                .orElseThrow();
        FanProcessingPlan c06 = FanProcessingPlan.washing(new BlockPos3i(50, 90, 50));

        assertThat(washing.components()).hasSize(c06.placements().size());
        assertThat(washing.footprint().cells()).isEqualTo(
                c06.placements().stream()
                        .map(value -> value.position().translate(-50, -90, -50))
                        .collect(Collectors.toSet()));
        assertThat(washing.topologyContract())
                .contains("exact_medium", "contained_depot_processing", "dangerous=false");
        assertThat(CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.FAN_BLASTING_IMPLEMENTATION_ID)
                .orElseThrow().topologyContract()).contains("dangerous=true");
    }

    @Test
    void cuttingGeometryLocksTheSawUpwardAndMatchesTheC07Topology() {
        MachineGeometryDescriptor geometry = CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID)
                .orElseThrow();
        MechanicalSawPlan c07 = MechanicalSawPlan.at(new BlockPos3i(40, 90, 40));

        assertThat(geometry.components()).hasSize(c07.placements().size());
        assertThat(geometry.footprint().cells()).isEqualTo(
                c07.placements().stream()
                        .map(value -> value.position().translate(-40, -90, -40))
                        .collect(Collectors.toSet()));
        assertThat(geometry.components()).filteredOn(value ->
                        value.roleId().path().endsWith("mechanical_saw"))
                .singleElement()
                .satisfies(value -> assertThat(value.blockState())
                        .containsEntry("facing", "up"));
        assertThat(geometry.topologyContract())
                .contains("depot", "upward_saw", "chest", "world_cutting=forbidden");
    }

    @Test
    void crushingGeometryMatchesTheBoundedC05OwnedTopology() {
        MachineGeometryDescriptor geometry = CreateV606MachineGeometryCatalog.create(FINGERPRINT)
                .find(CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID)
                .orElseThrow();
        CrushingWheelPlan c05 = CrushingWheelPlan.at(new BlockPos3i(10, 64, 10));

        assertThat(geometry.components()).hasSize(c05.placements().size());
        assertThat(geometry.components().stream()
                .map(value -> value.blockId()).collect(Collectors.toSet()))
                .isEqualTo(c05.placements().stream()
                        .map(value -> value.blockId()).collect(Collectors.toSet()));
        assertThat(geometry.footprint().cells()).isEqualTo(
                c05.placements().stream()
                        .map(value -> value.position().translate(-10, -64, -10))
                        .collect(Collectors.toSet()));
        assertThat(geometry.topologyContract())
                .contains("mirrored_enclosed_water_wheels", "opposed_independent_drives", "two_wheels",
                        "runtime_controller", "hopper", "chest");
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
        assertThat(geometry.rotationalPowerRoute()).containsExactly(
                new BlockPos3i(0, 3, 2), new BlockPos3i(1, 3, 2),
                new BlockPos3i(1, 3, 1), new BlockPos3i(1, 3, 0));
        assertThat(geometry.topologyContract())
                .contains("three_segment_belt", "press_water_wheel", "funnel", "chest");
    }

    @Test
    void survivalPowerLedgerRolesStayBoundToThePublishedGeometry() {
        var catalog = CreateV606MachineGeometryCatalog.create(FINGERPRINT);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C03);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C04);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C05);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.FAN_WASHING_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C06);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C07);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.BASIN_MIXER_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C08);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C09);
        assertPowerRoles(catalog.find(CreateRuntimeMachineImplementationCatalog.DEPLOYER_IMPLEMENTATION_ID)
                .orElseThrow(), CreateCapabilityContractV1.C10);
    }

    private static void assertPowerRoles(
            MachineGeometryDescriptor geometry, CreateCapabilityContractV1 capability) {
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(capability);
        Set<String> roles = geometry.components().stream()
                .map(value -> value.roleId().path().replaceFirst("^geometry/", ""))
                .filter(mapping.roles()::contains)
                .collect(Collectors.toSet());
        assertThat(roles).containsExactlyInAnyOrderElementsOf(mapping.roles());
        List<String> powerBlocks = geometry.components().stream()
                .filter(value -> mapping.roles().contains(
                        value.roleId().path().replaceFirst("^geometry/", "")))
                .map(value -> value.blockId().toString()).toList();
        if (mapping.status() == CreateSurvivalPowerMappingV1.Status.REVIEW_REQUIRED) {
            assertThat(powerBlocks).containsOnly(mapping.powerResource().toString());
        } else {
            assertThat(powerBlocks).containsExactlyInAnyOrder(
                    mapping.roleBlockIds().values().stream().map(Object::toString).toArray(String[]::new));
        }
    }
}
