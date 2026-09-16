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

/** Fixed bounded water-wheel/press/basin topology for C-09 compacting. */
public final class BasinPressPlan {
    public static final int MAX_PREFLIGHT_POSITIONS = 512;
    private final PlanAnchor anchor;
    private final CompactingProcessSpec process;
    private final List<BasinPressPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;

    private BasinPressPlan(PlanAnchor anchor, CompactingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = List.of(
                placement(1, BasinPressRole.FLOW_CATCH_FLOOR, "minecraft:stone",
                        transform, 0, 1, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(2, BasinPressRole.FLOW_CATCH_WEST_WALL, "minecraft:stone",
                        transform, -1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(3, BasinPressRole.FLOW_CATCH_EAST_WALL, "minecraft:stone",
                        transform, 1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(4, BasinPressRole.FLOW_CATCH_NORTH_WALL, "minecraft:stone",
                        transform, 0, 2, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, BasinPressRole.FLOW_CATCH_SOUTH_WALL, "minecraft:stone",
                        transform, 0, 2, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(6, BasinPressRole.FLOW_CHAMBER_WEST_WALL, "minecraft:stone",
                        transform, -1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(7, BasinPressRole.FLOW_CHAMBER_EAST_WALL, "minecraft:stone",
                        transform, 1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(8, BasinPressRole.FLOW_CHAMBER_NORTH_WALL, "minecraft:stone",
                        transform, 0, 4, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(9, BasinPressRole.FLOW_CHAMBER_SOUTH_WALL, "minecraft:stone",
                        transform, 0, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(10, BasinPressRole.FLOW_CHANNEL_WEST_WALL, "minecraft:stone",
                        transform, -1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(11, BasinPressRole.FLOW_CHANNEL_EAST_WALL, "minecraft:stone",
                        transform, 1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(12, BasinPressRole.FLOW_CHANNEL_NORTH_WALL, "minecraft:stone",
                        transform, 0, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(13, BasinPressRole.PRESS_PLATFORM, "minecraft:stone",
                        transform, 1, 3, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(14, BasinPressRole.WATER_WHEEL, "create:water_wheel",
                        transform, 0, 3, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(15, BasinPressRole.BOTTOM_GEARBOX, "create:gearbox",
                        transform, 1, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(16, BasinPressRole.VERTICAL_SHAFT, "create:shaft",
                        transform, 1, 4, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(17, BasinPressRole.TOP_GEARBOX, "create:gearbox",
                        transform, 1, 5, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(18, BasinPressRole.HORIZONTAL_SHAFT, "create:shaft",
                        transform, 1, 5, -1, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(19, BasinPressRole.MECHANICAL_PRESS, "create:mechanical_press",
                        transform, 1, 5, -2, PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                placement(20, BasinPressRole.BASIN, "create:basin",
                        transform, 1, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(21, BasinPressRole.OUTPUT_CHEST, "minecraft:chest",
                        transform, 2, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(22, BasinPressRole.WATER_SOURCE, "minecraft:water",
                        transform, 0, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE));
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c09/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = preflight(transform);
        validate();
    }

    public static BasinPressPlan forProcess(
            PlanAnchor anchor, CompactingProcessSpec process) {
        return new BasinPressPlan(anchor, process);
    }

    public static BasinPressPlan blazeCakeBase(BlockPos3i origin) {
        return forProcess(
                PlanAnchor.at(origin),
                new CompactingProcessSpec(
                        id("create:compacting/blaze_cake"),
                        List.of(
                                item("create:cinder_flour", 1),
                                item("minecraft:sugar", 1),
                                item("minecraft:egg", 1)),
                        id("create:blaze_cake_base"),
                        1,
                        BasinHeatMode.NONE,
                        400,
                        2_000));
    }

    public static BasinPressPlan at(
            BlockPos3i origin,
            QuarterTurn rotation,
            CompactingProcessSpec process) {
        return forProcess(new PlanAnchor(origin, rotation), process);
    }

    public PlanAnchor anchor() { return anchor; }
    public BlockPos3i origin() { return anchor.position(); }
    public CompactingProcessSpec process() { return process; }
    public List<BasinPressPlacement> placements() { return placements; }
    public List<PlacementTarget> placementTargets() { return placementTargets; }
    public List<BlockPos3i> preflightPositions() { return preflightPositions; }

    public BasinPressPlacement placement(BasinPressRole role) {
        return placements.stream().filter(value -> value.role() == role).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "C-09 role is absent: " + role));
    }

    private void validate() {
        EnumSet<BasinPressRole> roles = EnumSet.noneOf(BasinPressRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            BasinPressPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException(
                        "C-09 placement order, roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(BasinPressRole.class))) {
            throw new IllegalStateException("C-09 topology is incomplete");
        }
    }

    private static List<BlockPos3i> preflight(PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>();
        for (int x = -2; x <= 4; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -4; z <= 2; z++) {
                    values.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-09 preflight volume exceeds its bound");
        }
        return List.copyOf(values);
    }

    private static BasinPressPlacement placement(
            int order,
            BasinPressRole role,
            String block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
        return new BasinPressPlacement(
                order, role, id(block), transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis), transform.rotate(facing));
    }

    private static ProcessResource item(String id, int count) {
        return new ProcessResource(
                ResourceId.parse(id), GenericResourceType.ITEM, count);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
