package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.placement.PlacementTarget;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Fixed bounded water-wheel/gearing/mixer/basin topology for C-08. */
public final class BasinMixerPlan {
    public static final int MAX_PREFLIGHT_POSITIONS = 640;
    public static final ResourceId HEATING_FUEL =
            ResourceId.parse("minecraft:coal");
    private final PlanAnchor anchor;
    private final MixingProcessSpec process;
    private final List<BasinMixerPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;

    private BasinMixerPlan(PlanAnchor anchor, MixingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = List.of(
                placement(1, BasinMixerRole.FLOW_CATCH_FLOOR, "minecraft:stone", transform,
                        0, 1, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(2, BasinMixerRole.FLOW_CATCH_WEST_WALL, "minecraft:stone", transform,
                        -1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(3, BasinMixerRole.FLOW_CATCH_EAST_WALL, "minecraft:stone", transform,
                        1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(4, BasinMixerRole.FLOW_CATCH_NORTH_WALL, "minecraft:stone", transform,
                        0, 2, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, BasinMixerRole.FLOW_CATCH_SOUTH_WALL, "minecraft:stone", transform,
                        0, 2, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(6, BasinMixerRole.FLOW_CHAMBER_WEST_WALL, "minecraft:stone", transform,
                        -1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(7, BasinMixerRole.FLOW_CHAMBER_EAST_WALL, "minecraft:stone", transform,
                        1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(8, BasinMixerRole.FLOW_CHAMBER_NORTH_WALL, "minecraft:stone", transform,
                        0, 4, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(9, BasinMixerRole.FLOW_CHAMBER_SOUTH_WALL, "minecraft:stone", transform,
                        0, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(10, BasinMixerRole.FLOW_CHANNEL_WEST_WALL, "minecraft:stone", transform,
                        -1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(11, BasinMixerRole.FLOW_CHANNEL_EAST_WALL, "minecraft:stone", transform,
                        1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(12, BasinMixerRole.FLOW_CHANNEL_NORTH_WALL, "minecraft:stone", transform,
                        0, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(13, BasinMixerRole.MIXER_PLATFORM, "minecraft:stone", transform,
                        3, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(14, BasinMixerRole.WATER_WHEEL, "create:water_wheel", transform,
                        0, 3, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(15, BasinMixerRole.BOTTOM_GEARBOX, "create:gearbox", transform,
                        1, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(16, BasinMixerRole.VERTICAL_SHAFT, "create:shaft", transform,
                        1, 4, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(17, BasinMixerRole.LARGE_COGWHEEL_INPUT, "create:large_cogwheel", transform,
                        1, 5, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(18, BasinMixerRole.SMALL_COGWHEEL, "create:cogwheel", transform,
                        2, 5, 1, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(19, BasinMixerRole.LARGE_COGWHEEL_OUTPUT, "create:large_cogwheel", transform,
                        2, 6, 1, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(20, BasinMixerRole.MECHANICAL_MIXER,
                        "create:mechanical_mixer", transform, 3, 6, 2,
                        PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(21, BasinMixerRole.HEAT_SOURCE,
                        "create:blaze_burner", transform, 3, 3, 2,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(22, BasinMixerRole.BASIN,
                        "create:basin", transform, 3, 4, 2,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(23, BasinMixerRole.OUTPUT_CHEST,
                        "minecraft:chest", transform, 4, 4, 2,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(24, BasinMixerRole.WATER_SOURCE,
                        "minecraft:water", transform, 0, 4, -1,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE));
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c08/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = preflight(transform);
        validate();
    }

    public static BasinMixerPlan forProcess(
            PlanAnchor anchor, MixingProcessSpec process) {
        return new BasinMixerPlan(anchor, process);
    }

    public static BasinMixerPlan andesiteAlloy(BlockPos3i origin) {
        return forProcess(
                PlanAnchor.at(origin),
                new MixingProcessSpec(
                        id("create:mixing/andesite_alloy"),
                        List.of(
                                item("minecraft:andesite", 1),
                                item("minecraft:iron_nugget", 1)),
                        id("create:andesite_alloy"),
                        1,
                        BasinHeatMode.NONE,
                        400,
                        2_000));
    }

    public static BasinMixerPlan brass(BlockPos3i origin) {
        return forProcess(
                PlanAnchor.at(origin),
                new MixingProcessSpec(
                        id("create:mixing/brass_ingot"),
                        List.of(
                                item("minecraft:copper_ingot", 1),
                                item("create:zinc_ingot", 1)),
                        id("create:brass_ingot"),
                        2,
                        BasinHeatMode.HEATED,
                        400,
                        2_000));
    }

    public static BasinMixerPlan at(
            BlockPos3i origin,
            QuarterTurn rotation,
            MixingProcessSpec process) {
        return forProcess(new PlanAnchor(origin, rotation), process);
    }

    public PlanAnchor anchor() { return anchor; }
    public BlockPos3i origin() { return anchor.position(); }
    public MixingProcessSpec process() { return process; }
    public List<BasinMixerPlacement> placements() { return placements; }
    public List<PlacementTarget> placementTargets() { return placementTargets; }
    public List<BlockPos3i> preflightPositions() { return preflightPositions; }

    public BasinMixerPlacement placement(BasinMixerRole role) {
        return placements.stream()
                .filter(value -> value.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "C-08 role is absent: " + role));
    }

    public ProcessResource heatingFuel() {
        if (!process.requiresFuel()) {
            throw new IllegalStateException(
                    "C-08 NONE heat does not consume burner fuel");
        }
        return new ProcessResource(
                HEATING_FUEL, GenericResourceType.ITEM, 1);
    }

    private void validate() {
        EnumSet<BasinMixerRole> roles =
                EnumSet.noneOf(BasinMixerRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            BasinMixerPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException(
                        "C-08 placement order, roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(BasinMixerRole.class))) {
            throw new IllegalStateException("C-08 topology is incomplete");
        }
    }

    private static List<BlockPos3i> preflight(PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>();
        for (int x = -2; x <= 5; x++) {
            for (int y = 0; y <= 8; y++) {
                for (int z = -3; z <= 3; z++) {
                    values.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException(
                    "C-08 preflight volume exceeds its bound");
        }
        return List.copyOf(values);
    }

    private static BasinMixerPlacement placement(
            int order,
            BasinMixerRole role,
            String block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
        return new BasinMixerPlacement(
                order, role, id(block),
                transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis), transform.rotate(facing));
    }

    private static ProcessResource item(String id, int count) {
        return new ProcessResource(
                ResourceId.parse(id), GenericResourceType.ITEM, count);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
