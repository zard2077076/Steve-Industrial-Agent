package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.layout.ClearanceVolume;
import dev.stevecreate.agent.core.layout.GeometryComponent;
import dev.stevecreate.agent.core.layout.ImmutableMachineGeometryCatalog;
import dev.stevecreate.agent.core.layout.MachineFootprint;
import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.layout.PhysicalPortRule;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loader-neutral Create 6.0.6 geometry derived from the accepted C-03/C-04 topology contracts. */
public final class CreateV606MachineGeometryCatalog {
    private static final Set<QuarterTurn> ORIENTATIONS = Set.of(
            QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90,
            QuarterTurn.CLOCKWISE_180, QuarterTurn.CLOCKWISE_270);

    private CreateV606MachineGeometryCatalog() {}

    public static ImmutableMachineGeometryCatalog create(String runtimeFingerprint) {
        return new ImmutableMachineGeometryCatalog(
                List.of(
                        crushing(), cutting(),
                        fan(
                                CreateRuntimeMachineImplementationCatalog
                                        .FAN_WASHING_IMPLEMENTATION_ID,
                                "fan_washing", "minecraft:stone", "minecraft:water", false),
                        fan(
                                CreateRuntimeMachineImplementationCatalog
                                        .FAN_SMOKING_IMPLEMENTATION_ID,
                                "fan_smoking", "minecraft:netherrack", "minecraft:fire", true),
                        fan(
                                CreateRuntimeMachineImplementationCatalog
                                        .FAN_HAUNTING_IMPLEMENTATION_ID,
                                "fan_haunting", "minecraft:soul_soil", "minecraft:soul_fire", true),
                        fan(
                                CreateRuntimeMachineImplementationCatalog
                                        .FAN_BLASTING_IMPLEMENTATION_ID,
                                "fan_blasting", "minecraft:stone", "minecraft:lava", true),
                        mixing(), compacting(), deploying(), millstone(), press()),
                runtimeFingerprint);
    }

    private static MachineGeometryDescriptor fan(
            ResourceId implementationId,
            String portPrefix,
            String supportBlock,
            String mediumBlock,
            boolean dangerousMedium) {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, 1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, 1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, 1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, 2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, 1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, 1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, 2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, 1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, 1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, 2, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0,
                        Map.of("axis", "x")),
                component("bottom_gearbox", "create:gearbox", 1, 3, 0,
                        Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0,
                        Map.of("axis", "y")),
                component("top_gearbox", "create:gearbox", 1, 5, 0,
                        Map.of("axis", "x")),
                component("fan_drive_shaft", "create:shaft", 1, 5, -1,
                        Map.of("axis", "z")),
                component("encased_fan", "create:encased_fan", 1, 5, -2,
                        Map.of("axis", "z", "facing", "north")),
                component("medium_support", supportBlock, 1, 4, -3, Map.of()),
                component("medium_left_barrier", "minecraft:glass", 0, 5, -3, Map.of()),
                component("medium_right_barrier", "minecraft:glass", 2, 5, -3, Map.of()),
                component("medium_stop", "minecraft:iron_bars", 1, 5, -4, Map.of()),
                component("input_depot", "create:depot", 1, 4, -5, Map.of()),
                component("processing_medium", mediumBlock, 1, 5, -3, Map.of()),
                component("output_chest", "minecraft:chest", 1, 4, -6, Map.of()),
                component("water_source", "minecraft:water", 0, 4, 1, Map.of()));
        return new MachineGeometryDescriptor(
                implementationId,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 3, 0, 7, -7, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:" + portPrefix + "_item_input", 1, 5, -5,
                                Direction6.UP, GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:" + portPrefix + "_item_output", 1, 4, -6,
                                Direction6.EAST, GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:" + portPrefix + "_rotational_power_input", 1, 5, -2,
                                Direction6.SOUTH, GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0),
                        pos(1, 5, 0), pos(1, 5, -1), pos(1, 5, -2)),
                32,
                8,
                "c06:water_flow->water_wheel[x]->gearboxes+shafts->fan[north]"
                        + "->exact_medium->contained_depot_processing->chest;dangerous="
                        + dangerousMedium);
    }

    private static MachineGeometryDescriptor cutting() {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, -1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, -1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, -1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, -2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, -1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, -1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, -2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, -1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, -1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, -2, Map.of()),
                component("saw_lane_west_floor", "minecraft:stone", 0, 3, -3, Map.of()),
                component("saw_lane_east_floor", "minecraft:stone", 1, 3, -3, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0,
                        Map.of("axis", "x")),
                component("bottom_gearbox", "create:gearbox", 1, 3, 0,
                        Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0,
                        Map.of("axis", "y")),
                component("top_gearbox", "create:gearbox", 1, 5, 0,
                        Map.of("axis", "x")),
                component("horizontal_shaft", "create:shaft", 1, 5, -1,
                        Map.of("axis", "z")),
                component("mechanical_saw", "create:mechanical_saw", 1, 5, -2,
                        Map.of("axis", "z", "facing", "up")),
                component("input_depot", "create:depot", 1, 4, -2, Map.of()),
                component("output_chest", "minecraft:chest", 2, 5, -2, Map.of()),
                component("water_source", "minecraft:water", 0, 4, -1, Map.of()));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 3, 0, 7, -4, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:mechanical_saw_item_input", 1, 6, -2,
                                Direction6.UP, GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:mechanical_saw_item_output", 2, 5, -2,
                                Direction6.EAST, GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:mechanical_saw_rotational_power_input", 1, 5, -2,
                                Direction6.NORTH, GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0),
                        pos(1, 5, 0), pos(1, 5, -1), pos(1, 5, -2)),
                32,
                8,
                "c07:water_flow->water_wheel[x]->gearboxes+shafts->upward_saw"
                        + "->depot+real_item_entity->chest;world_cutting=forbidden");
    }

    private static MachineGeometryDescriptor compacting() {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, -1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, -1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, -1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, -2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, -1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, -1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, -2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, -1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, -1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, -2, Map.of()),
                component("press_platform", "minecraft:stone", 1, 3, -3, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0, Map.of("axis", "x")),
                component("bottom_gearbox", "create:gearbox", 1, 3, 0, Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0, Map.of("axis", "y")),
                component("top_gearbox", "create:gearbox", 1, 5, 0, Map.of("axis", "x")),
                component("horizontal_shaft", "create:shaft", 1, 5, -1, Map.of("axis", "z")),
                component("mechanical_press", "create:mechanical_press", 1, 5, -2,
                        Map.of("axis", "z", "facing", "north")),
                component("basin", "create:basin", 1, 3, -2, Map.of()),
                component("output_chest", "minecraft:chest", 2, 3, -2, Map.of()),
                component("water_source", "minecraft:water", 0, 4, -1, Map.of()));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog
                        .BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 4, 0, 7, -4, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:basin_compacting_item_input", 1, 4, -2,
                                Direction6.UP, GenericResourceType.ITEM,
                                PortMode.INPUT, 9),
                        port("create:basin_compacting_item_output", 2, 3, -2,
                                Direction6.EAST, GenericResourceType.ITEM,
                                PortMode.OUTPUT, 64),
                        port("create:basin_compacting_rotational_power_input",
                                1, 5, -2, Direction6.NORTH,
                                GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0),
                        pos(1, 5, 0), pos(1, 5, -1), pos(1, 5, -2)),
                32,
                9,
                "c09:water_flow->water_wheel[x]->gearboxes+shafts->basin_press->chest;"
                        + "item_only_basin->real_mechanical_press_cycle;"
                        + "fluids=forbidden;heat=none;belt_pressing=forbidden");
    }

    private static MachineGeometryDescriptor mixing() {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, -1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, -1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, -1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, -2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, -1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, -1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, -2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, -1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, -1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, -2, Map.of()),
                component("mixer_platform", "minecraft:stone", 3, 4, 0, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0, Map.of("axis", "x")),
                component("bottom_gearbox", "create:gearbox", 1, 3, 0, Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0, Map.of("axis", "y")),
                component("large_cogwheel_input", "create:large_cogwheel", 1, 5, 0,
                        Map.of("axis", "y")),
                component("small_cogwheel", "create:cogwheel", 2, 5, 1, Map.of("axis", "y")),
                component("large_cogwheel_output", "create:large_cogwheel", 2, 6, 1,
                        Map.of("axis", "y")),
                component("mechanical_mixer", "create:mechanical_mixer", 3, 6, 2,
                        Map.of("axis", "y")),
                component("heat_source", "create:blaze_burner", 3, 3, 2,
                        Map.of()),
                component("basin", "create:basin", 3, 4, 2, Map.of()),
                component("output_chest", "minecraft:chest", 4, 4, 2, Map.of()),
                component("water_source", "minecraft:water", 0, 4, -1, Map.of()));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog
                        .BASIN_MIXER_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 5, 0, 8, -3, 3)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:basin_mixing_item_input", 3, 5, 2,
                                Direction6.UP, GenericResourceType.ITEM,
                                PortMode.INPUT, 9),
                        port("create:basin_mixing_item_output", 4, 4, 2,
                                Direction6.EAST, GenericResourceType.ITEM,
                                PortMode.OUTPUT, 64),
                        port("create:basin_mixing_rotational_power_input",
                                3, 6, 2, Direction6.UP,
                                GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32),
                        port("create:basin_mixing_heat_input",
                                3, 3, 2, Direction6.UP,
                                GenericResourceType.HEAT,
                                PortMode.INPUT, 1)),
                List.of(
                        pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0),
                        pos(1, 5, 0), pos(2, 5, 1), pos(2, 6, 1), pos(3, 6, 2)),
                32,
                9,
                "c08:counted_item_only_basin->water_wheel->two_stage_gearing"
                        + "->real_mechanical_mixer_cycle->chest;"
                        + "fluids=forbidden;heat=none_or_kindled;"
                        + "superheated=forbidden;unknown_nbt=forbidden");
    }

    private static MachineGeometryDescriptor deploying() {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, -1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, -1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, -1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, -2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, -1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, -1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, -2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, -1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, -1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, -2, Map.of()),
                component("deployer_platform", "minecraft:stone", 1, 3, -3, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0, Map.of("axis", "x")),
                component("bottom_gearbox", "create:gearbox", 1, 3, 0, Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0, Map.of("axis", "y")),
                component("top_gearbox", "create:gearbox", 1, 5, 0, Map.of("axis", "x")),
                component("horizontal_shaft", "create:shaft", 1, 5, -1, Map.of("axis", "z")),
                component("deployer", "create:deployer", 1, 5, -2,
                        Map.of("axis", "z", "facing", "down")),
                component("input_depot", "create:depot", 1, 3, -2, Map.of()),
                component("output_chest", "minecraft:chest", 2, 3, -2, Map.of()),
                component("water_source", "minecraft:water", 0, 4, -1, Map.of()));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog
                        .DEPLOYER_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 4, 0, 7, -4, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:deployer_processed_item_input", 1, 3, -2,
                                Direction6.UP, GenericResourceType.ITEM,
                                PortMode.INPUT, 64),
                        port("create:deployer_held_item_input", 1, 5, -2,
                                Direction6.UP, GenericResourceType.ITEM,
                                PortMode.INPUT, 1),
                        port("create:deployer_item_output", 2, 3, -2,
                                Direction6.EAST, GenericResourceType.ITEM,
                                PortMode.OUTPUT, 64),
                        port("create:deployer_rotational_power_input", 1, 5, -2,
                                Direction6.SOUTH,
                                GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 32)),
                List.of(pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0),
                        pos(1, 5, 0), pos(1, 5, -1), pos(1, 5, -2)),
                32,
                4,
                "c10:water_wheel->gearbox->vertical_shaft->gearbox->horizontal_shaft"
                        + "->exact_held_item->downward_deployer->owned_depot->chest;"
                        + "entity_block_container_player_inventory_nbt=forbidden");
    }

    private static MachineGeometryDescriptor crushing() {
        List<GeometryComponent> components = List.of(
                component("left_flow_floor", "minecraft:stone", 1, 2, 1, Map.of()),
                component("left_source_west_wall", "minecraft:stone", 0, 5, 1, Map.of()),
                component("left_source_east_wall", "minecraft:stone", 2, 5, 1, Map.of()),
                component("shared_source_center_wall", "minecraft:stone", 1, 5, 0, Map.of()),
                component("left_source_outer_wall", "minecraft:stone", 1, 5, 2, Map.of()),
                component("right_flow_floor", "minecraft:stone", 1, 2, -1, Map.of()),
                component("right_source_west_wall", "minecraft:stone", 0, 5, -1, Map.of()),
                component("right_source_east_wall", "minecraft:stone", 2, 5, -1, Map.of()),
                component("right_source_outer_wall", "minecraft:stone", 1, 5, -2, Map.of()),
                component("output_chest", "minecraft:chest", 1, 0, 0, Map.of()),
                component("output_hopper", "minecraft:hopper", 1, 1, 0,
                        Map.of("facing", "down")),
                component("left_water_source", "minecraft:water", 1, 5, 1, Map.of()),
                component("right_water_source", "minecraft:water", 1, 5, -1, Map.of()),
                component("left_drive", "create:water_wheel", 0, 3, 1,
                        Map.of("axis", "z")),
                component("right_drive", "create:water_wheel", 2, 3, -1,
                        Map.of("axis", "z")),
                component("left_wheel", "create:crushing_wheel", 0, 3, 0,
                        Map.of("axis", "z")),
                component("right_wheel", "create:crushing_wheel", 2, 3, 0,
                        Map.of("axis", "z")));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-1, 3, 0, 6, -2, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:crushing_wheel_pair_item_input", 1, 4, 0,
                                Direction6.UP, GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:crushing_wheel_pair_item_output", 1, 0, 0,
                                Direction6.DOWN, GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:crushing_wheel_pair_rotational_power_input", 0, 3, 0,
                                Direction6.SOUTH, GenericResourceType.ROTATIONAL_POWER,
                                PortMode.INPUT, 64)),
                List.of(pos(0, 3, 1), pos(0, 3, 0)),
                64,
                16,
                "c05:mirrored_enclosed_water_wheels->opposed_independent_drives->two_wheels"
                        + "->runtime_controller->hopper->chest");
    }

    private static MachineGeometryDescriptor millstone() {
        List<GeometryComponent> components = List.of(
                component("flow_catch_floor", "minecraft:stone", 0, 1, -1, Map.of()),
                component("flow_catch_west_wall", "minecraft:stone", -1, 2, -1, Map.of()),
                component("flow_catch_east_wall", "minecraft:stone", 1, 2, -1, Map.of()),
                component("flow_catch_north_wall", "minecraft:stone", 0, 2, -2, Map.of()),
                component("flow_catch_south_wall", "minecraft:stone", 0, 2, 0, Map.of()),
                component("flow_chamber_west_wall", "minecraft:stone", -1, 4, -1, Map.of()),
                component("flow_chamber_east_wall", "minecraft:stone", 1, 4, -1, Map.of()),
                component("flow_chamber_north_wall", "minecraft:stone", 0, 4, -2, Map.of()),
                component("flow_chamber_south_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("flow_channel_west_wall", "minecraft:stone", -1, 3, -1, Map.of()),
                component("flow_channel_east_wall", "minecraft:stone", 1, 3, -1, Map.of()),
                component("flow_channel_north_wall", "minecraft:stone", 0, 3, -2, Map.of()),
                component("water_wheel", "create:water_wheel", 0, 3, 0, Map.of("axis", "x")),
                component("gearbox", "create:gearbox", 1, 3, 0, Map.of("axis", "z")),
                component("vertical_shaft", "create:shaft", 1, 4, 0, Map.of("axis", "y")),
                component("millstone", "create:millstone", 1, 5, 0, Map.of()),
                component("water_source", "minecraft:water", 0, 4, -1, Map.of()));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-2, 2, 0, 6, -3, 1)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:millstone_item_input", 1, 6, 0, Direction6.UP,
                                GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:millstone_item_output", 2, 5, 0, Direction6.EAST,
                                GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:millstone_rotational_power_input", 1, 4, 0, Direction6.DOWN,
                                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT, 32)),
                List.of(pos(0, 3, 0), pos(1, 3, 0), pos(1, 4, 0)),
                32,
                8,
                "c03:water_flow->water_wheel[x]->gearbox[z]->vertical_shaft[y]->millstone");
    }

    private static MachineGeometryDescriptor press() {
        List<GeometryComponent> components = List.of(
                component("belt_flow_floor", "minecraft:stone", -2, 2, 1, Map.of()),
                component("belt_flow_outer_floor", "minecraft:stone", 0, 2, 1, Map.of()),
                component("belt_flow_outer_west_wall", "minecraft:stone", -1, 2, 0, Map.of()),
                component("belt_flow_outer_east_wall", "minecraft:stone", -1, 2, 2, Map.of()),
                component("belt_flow_north_wall", "minecraft:stone", -1, 0, 1, Map.of()),
                component("belt_base_start", "minecraft:stone", 0, 0, 0, Map.of()),
                component("belt_base_pressing", "minecraft:stone", 1, 0, 0, Map.of()),
                component("belt_base_end", "minecraft:stone", 2, 0, 0, Map.of()),
                component("press_flow_floor", "minecraft:stone", -1, 4, 1, Map.of()),
                component("press_flow_outer_floor", "minecraft:stone", 1, 4, 1, Map.of()),
                component("press_flow_east_wall", "minecraft:stone", 0, 4, 0, Map.of()),
                component("press_flow_north_wall", "minecraft:stone", 0, 4, 2, Map.of()),
                component("belt_water_wheel", "create:water_wheel", -1, 1, 2, Map.of("axis", "x")),
                component("belt_gearbox", "create:gearbox", 0, 1, 2, Map.of("axis", "y")),
                component("belt_drive_shaft", "create:shaft", 0, 1, 1, Map.of("axis", "z")),
                component("press_water_wheel", "create:water_wheel", 0, 3, 2, Map.of("axis", "x")),
                component("press_gearbox", "create:gearbox", 1, 3, 2, Map.of("axis", "y")),
                component("press_drive_shaft", "create:shaft", 1, 3, 1, Map.of("axis", "z")),
                component("mechanical_press", "create:mechanical_press", 1, 3, 0,
                        Map.of("axis", "z", "facing", "north")),
                component("output_chest", "minecraft:chest", -1, 0, 0, Map.of()),
                component("output_funnel", "create:andesite_funnel", -1, 1, 0,
                        Map.of("facing", "up")),
                component("belt_water_source", "minecraft:water", -1, 2, 1, Map.of()),
                component("press_water_source", "minecraft:water", 0, 4, 1, Map.of()),
                component("belt_start", "create:belt", 2, 1, 0, Map.of("facing", "west")),
                component("belt_pressing", "create:belt", 1, 1, 0, Map.of("facing", "west")),
                component("belt_end", "create:belt", 0, 1, 0, Map.of("facing", "west")));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-3, 4, 0, 5, -2, 4)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:mechanical_press_item_input", 2, 2, 0, Direction6.UP,
                                GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:mechanical_press_item_output", -1, 1, 0, Direction6.WEST,
                                GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:mechanical_press_rotational_power_input", 1, 3, 0, Direction6.SOUTH,
                                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT, 32)),
                List.of(pos(0, 3, 2), pos(1, 3, 2), pos(1, 3, 1), pos(1, 3, 0)),
                32,
                16,
                "c04:belt_water_wheel->belt_gearbox->belt_drive_shaft->three_segment_belt;"
                        + "press_water_wheel->press_gearbox->press_drive_shaft->mechanical_press;"
                        + "funnel->chest");
    }

    private static GeometryComponent component(
            String role, String block, int x, int y, int z, Map<String, String> state) {
        return new GeometryComponent(id("create:geometry/" + role), id(block), pos(x, y, z), state);
    }

    private static PhysicalPortRule port(
            String id, int x, int y, int z, Direction6 side,
            GenericResourceType type, PortMode mode, long capacity) {
        return new PhysicalPortRule(
                CreateV606MachineGeometryCatalog.id(id), pos(x, y, z), Optional.of(side),
                type, mode, capacity);
    }

    private static Set<BlockPos3i> positions(List<GeometryComponent> components) {
        LinkedHashSet<BlockPos3i> positions = new LinkedHashSet<>();
        components.forEach(value -> positions.add(value.relativePosition()));
        return positions;
    }

    private static Set<BlockPos3i> box(
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        LinkedHashSet<BlockPos3i> positions = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) positions.add(pos(x, y, z));
            }
        }
        return positions;
    }

    private static BlockPos3i pos(int x, int y, int z) { return new BlockPos3i(x, y, z); }
    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
