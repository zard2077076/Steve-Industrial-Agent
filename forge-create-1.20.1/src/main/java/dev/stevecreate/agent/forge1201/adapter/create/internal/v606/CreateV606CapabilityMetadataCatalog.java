package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.create.CapabilityComponentRequirement;
import dev.stevecreate.agent.adapter.api.create.CapabilityComponentRole;
import dev.stevecreate.agent.adapter.api.create.CapabilityImplementationBindingMetadata;
import dev.stevecreate.agent.adapter.api.create.CapabilityZoneRequirement;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** Exact Create 6.0.6 component metadata; deliberately contains no executor implementation. */
public final class CreateV606CapabilityMetadataCatalog {
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String CREATE_VERSION = "6.0.6";
    private static final EnumSet<Direction6> HORIZONTAL = EnumSet.of(
            Direction6.NORTH, Direction6.EAST, Direction6.SOUTH, Direction6.WEST);
    private static final List<String> LIMITATIONS = List.of(
            "absolute positions require a separately verified layout binding",
            "metadata grants no placement or execution authority",
            "player inventory and arbitrary world interaction are forbidden");

    private final List<CapabilityImplementationBindingMetadata> entries = List.of(
            crushing(),
            fan(CreateCapabilityId.FAN_WASHING, id("minecraft:water")),
            fan(CreateCapabilityId.FAN_SMOKING, id("minecraft:fire")),
            fan(CreateCapabilityId.FAN_HAUNTING, id("minecraft:soul_fire")),
            fan(CreateCapabilityId.FAN_BLASTING, id("minecraft:lava")),
            cutting(),
            basin(CreateCapabilityId.MIXING_PHASE_I, id("create:mechanical_mixer")),
            basin(CreateCapabilityId.COMPACTING_PHASE_I, id("create:mechanical_press")),
            deploying());

    public List<CapabilityImplementationBindingMetadata> entries() {
        return entries;
    }

    public Optional<CapabilityImplementationBindingMetadata> find(CreateCapabilityId capability) {
        return entries.stream().filter(value -> value.capability() == capability).findFirst();
    }

    private static CapabilityImplementationBindingMetadata crushing() {
        return metadata(
                CreateCapabilityId.CRUSHING,
                List.of(
                        component(CapabilityComponentRole.PRIMARY_MACHINE, pos(0, 0, 0),
                                List.of(id("create:crushing_wheel")), true, true),
                        component(CapabilityComponentRole.SECONDARY_MACHINE, pos(2, 0, 0),
                                List.of(id("create:crushing_wheel")), true, true),
                        component(CapabilityComponentRole.ITEM_PROCESSING_LANE, pos(1, 0, 0),
                                List.of(), true, false),
                        component(CapabilityComponentRole.ROTATIONAL_POWER_INPUT, pos(0, 0, -1),
                                List.of(), true, false)),
                List.of(zone("wheel_gap", pos(1, 0, 0), pos(1, 1, 0), true)));
    }

    private static CapabilityImplementationBindingMetadata fan(
            CreateCapabilityId capability,
            ResourceId medium) {
        return metadata(
                capability,
                List.of(
                        component(CapabilityComponentRole.PRIMARY_MACHINE, pos(0, 0, 0),
                                List.of(id("create:encased_fan")), true, true),
                        component(CapabilityComponentRole.MEDIUM, pos(1, 0, 0),
                                List.of(medium), true, false),
                        component(CapabilityComponentRole.ITEM_PROCESSING_LANE, pos(2, 0, 0),
                                List.of(), true, false),
                        component(CapabilityComponentRole.ROTATIONAL_POWER_INPUT, pos(0, 0, -1),
                                List.of(), true, false)),
                List.of(zone("airflow_lane", pos(1, 0, 0), pos(6, 1, 0), true)));
    }

    private static CapabilityImplementationBindingMetadata cutting() {
        return metadata(
                CreateCapabilityId.CUTTING,
                List.of(
                        component(CapabilityComponentRole.PRIMARY_MACHINE, pos(0, 0, 0),
                                List.of(id("create:mechanical_saw")), true, true),
                        component(CapabilityComponentRole.ITEM_PROCESSING_LANE, pos(0, 1, 0),
                                List.of(), true, false),
                        component(CapabilityComponentRole.ROTATIONAL_POWER_INPUT, pos(0, 0, -1),
                                List.of(), true, false)),
                List.of(zone("item_cutting_lane", pos(-1, 1, 0), pos(1, 1, 0), true)));
    }

    private static CapabilityImplementationBindingMetadata basin(
            CreateCapabilityId capability,
            ResourceId machine) {
        return metadata(
                capability,
                List.of(
                        component(CapabilityComponentRole.PRIMARY_MACHINE, pos(0, 1, 0),
                                List.of(machine), true, true),
                        component(CapabilityComponentRole.BASIN, pos(0, 0, 0),
                                List.of(id("create:basin")), true, true),
                        component(CapabilityComponentRole.HEAT_SOURCE, pos(0, -1, 0),
                                List.of(id("create:blaze_burner")), false, true),
                        component(CapabilityComponentRole.ROTATIONAL_POWER_INPUT, pos(0, 1, -1),
                                List.of(), true, false)),
                List.of(zone("basin_output_clearance", pos(-1, 0, -1), pos(1, 2, 1), false)));
    }

    private static CapabilityImplementationBindingMetadata deploying() {
        return metadata(
                CreateCapabilityId.DEPLOYING_PHASE_I,
                List.of(
                        component(CapabilityComponentRole.PRIMARY_MACHINE, pos(0, 1, 0),
                                List.of(id("create:deployer")), true, true),
                        component(CapabilityComponentRole.ITEM_PROCESSING_LANE, pos(0, 0, 0),
                                List.of(), true, false),
                        component(CapabilityComponentRole.HELD_ITEM, pos(0, 1, 0),
                                List.of(), true, false),
                        component(CapabilityComponentRole.ROTATIONAL_POWER_INPUT, pos(0, 1, -1),
                                List.of(), true, false)),
                List.of(zone("deployer_item_lane", pos(-1, 0, 0), pos(1, 0, 0), true)));
    }

    private static CapabilityImplementationBindingMetadata metadata(
            CreateCapabilityId capability,
            List<CapabilityComponentRequirement> components,
            List<CapabilityZoneRequirement> zones) {
        return new CapabilityImplementationBindingMetadata(
                id("steve_industrial:" + capability.name().toLowerCase() + "_metadata_v606"),
                capability,
                MINECRAFT_VERSION,
                CREATE_VERSION,
                HORIZONTAL,
                components,
                zones,
                LIMITATIONS,
                true,
                false,
                false);
    }

    private static CapabilityComponentRequirement component(
            CapabilityComponentRole role,
            BlockPos3i position,
            List<ResourceId> blocks,
            boolean required,
            boolean blockEntity) {
        return new CapabilityComponentRequirement(
                role, position, blocks, required, blockEntity, true);
    }

    private static CapabilityZoneRequirement zone(
            String id,
            BlockPos3i minimum,
            BlockPos3i maximum,
            boolean obstructionFree) {
        return new CapabilityZoneRequirement(id, minimum, maximum, obstructionFree, true, true);
    }

    private static BlockPos3i pos(int x, int y, int z) {
        return new BlockPos3i(x, y, z);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
