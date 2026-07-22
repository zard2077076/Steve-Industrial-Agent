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
            QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270);

    private CreateV606MachineGeometryCatalog() {}

    public static ImmutableMachineGeometryCatalog create(String runtimeFingerprint) {
        return new ImmutableMachineGeometryCatalog(
                List.of(millstone(), press()), runtimeFingerprint);
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
                component("belt_drive", "create:creative_motor", 0, 1, 1,
                        Map.of("axis", "z", "facing", "north")),
                component("press_drive", "create:creative_motor", 1, 3, 1,
                        Map.of("axis", "z", "facing", "north")),
                component("mechanical_press", "create:mechanical_press", 1, 3, 0,
                        Map.of("axis", "z", "facing", "north")),
                component("output_chest", "minecraft:chest", 3, 0, 0, Map.of()),
                component("output_funnel", "create:andesite_funnel", 3, 1, 0,
                        Map.of("facing", "up")),
                component("belt_start", "create:belt", 0, 1, 0, Map.of("facing", "east")),
                component("belt_pressing", "create:belt", 1, 1, 0, Map.of("facing", "east")),
                component("belt_end", "create:belt", 2, 1, 0, Map.of("facing", "east")));
        return new MachineGeometryDescriptor(
                CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID,
                new MachineFootprint(positions(components)),
                new ClearanceVolume(box(-1, 4, 0, 4, -2, 2)),
                ORIENTATIONS,
                components,
                List.of(
                        port("create:mechanical_press_item_input", 0, 2, 0, Direction6.UP,
                                GenericResourceType.ITEM, PortMode.INPUT, 64),
                        port("create:mechanical_press_item_output", 3, 1, 0, Direction6.EAST,
                                GenericResourceType.ITEM, PortMode.OUTPUT, 64),
                        port("create:mechanical_press_rotational_power_input", 1, 3, 0, Direction6.SOUTH,
                                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT, 32)),
                List.of(pos(1, 3, 1), pos(1, 3, 0)),
                32,
                16,
                "c04:belt_motor->three_segment_belt;press_motor->mechanical_press;funnel->chest");
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
