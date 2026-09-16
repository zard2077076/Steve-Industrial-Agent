package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.placement.PlacementTarget;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Fixed bounded water-wheel-to-fan-to-medium-to-item C-06 topology. */
public final class FanProcessingPlan {
    public static final int MAX_PREFLIGHT_POSITIONS = 640;
    private final PlanAnchor anchor;
    private final FanProcessingSpec process;
    private final List<FanProcessingPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;
    private final BlockPos3i inputEntityPosition;

    private FanProcessingPlan(PlanAnchor anchor, FanProcessingSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = List.of(
                placement(1, FanProcessingRole.FLOW_CATCH_FLOOR, id("minecraft:stone"),
                        transform, 0, 1, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(2, FanProcessingRole.FLOW_CATCH_WEST_WALL, id("minecraft:stone"),
                        transform, -1, 2, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(3, FanProcessingRole.FLOW_CATCH_EAST_WALL, id("minecraft:stone"),
                        transform, 1, 2, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(4, FanProcessingRole.FLOW_CATCH_NORTH_WALL, id("minecraft:stone"),
                        transform, 0, 2, 2, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(5, FanProcessingRole.FLOW_CATCH_SOUTH_WALL, id("minecraft:stone"),
                        transform, 0, 2, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(6, FanProcessingRole.FLOW_CHAMBER_WEST_WALL, id("minecraft:stone"),
                        transform, -1, 4, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(7, FanProcessingRole.FLOW_CHAMBER_EAST_WALL, id("minecraft:stone"),
                        transform, 1, 4, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(8, FanProcessingRole.FLOW_CHAMBER_NORTH_WALL, id("minecraft:stone"),
                        transform, 0, 4, 2, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(9, FanProcessingRole.FLOW_CHAMBER_SOUTH_WALL, id("minecraft:stone"),
                        transform, 0, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(10, FanProcessingRole.FLOW_CHANNEL_WEST_WALL, id("minecraft:stone"),
                        transform, -1, 3, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(11, FanProcessingRole.FLOW_CHANNEL_EAST_WALL, id("minecraft:stone"),
                        transform, 1, 3, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(12, FanProcessingRole.FLOW_CHANNEL_NORTH_WALL, id("minecraft:stone"),
                        transform, 0, 3, 2, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(13, FanProcessingRole.WATER_WHEEL, id("create:water_wheel"),
                        transform, 0, 3, 0, PlanBlockAxis.X, PlanBlockFacing.NONE, false),
                placement(14, FanProcessingRole.BOTTOM_GEARBOX, id("create:gearbox"),
                        transform, 1, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE, false),
                placement(15, FanProcessingRole.VERTICAL_SHAFT, id("create:shaft"),
                        transform, 1, 4, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE, false),
                placement(16, FanProcessingRole.TOP_GEARBOX, id("create:gearbox"),
                        transform, 1, 5, 0, PlanBlockAxis.X, PlanBlockFacing.NONE, false),
                placement(17, FanProcessingRole.FAN_DRIVE_SHAFT, id("create:shaft"),
                        transform, 1, 5, -1, PlanBlockAxis.Z, PlanBlockFacing.NONE, false),
                placement(18, FanProcessingRole.ENCASED_FAN, id("create:encased_fan"),
                        transform, 1, 5, -2, PlanBlockAxis.Z, PlanBlockFacing.NORTH, false),
                placement(19, FanProcessingRole.MEDIUM_SUPPORT, process.mode().supportBlock(),
                        transform, 1, 4, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(20, FanProcessingRole.MEDIUM_LEFT_BARRIER, id("minecraft:glass"),
                        transform, 0, 5, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(21, FanProcessingRole.MEDIUM_RIGHT_BARRIER, id("minecraft:glass"),
                        transform, 2, 5, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(22, FanProcessingRole.MEDIUM_STOP, id("minecraft:iron_bars"),
                        transform, 1, 5, -4, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(23, FanProcessingRole.INPUT_DEPOT, id("create:depot"),
                        transform, 1, 4, -5, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(24, FanProcessingRole.PROCESSING_MEDIUM, process.mode().mediumBlock(),
                        transform, 1, 5, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE,
                        process.mode().dangerousToBots()),
                placement(25, FanProcessingRole.OUTPUT_CHEST, id("minecraft:chest"),
                        transform, 1, 4, -6, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false),
                placement(26, FanProcessingRole.WATER_SOURCE, id("minecraft:water"),
                        transform, 0, 4, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE, false));
        this.inputEntityPosition = transform.resolve(new BlockPos3i(1, 5, -5));
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c06/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = preflight(transform);
        validate();
    }

    public static FanProcessingPlan forProcess(PlanAnchor anchor, FanProcessingSpec process) {
        return new FanProcessingPlan(anchor, process);
    }

    public static FanProcessingPlan washing(BlockPos3i origin) {
        return forProcess(
                PlanAnchor.at(origin),
                new FanProcessingSpec(
                        id("create:splashing/wheat_flour"), id("create:splashing"),
                        id("create:wheat_flour"), 1, id("create:dough"), 1, 400, 1_200));
    }

    public static FanProcessingPlan at(
            BlockPos3i origin,
            QuarterTurn rotation,
            FanProcessingSpec process) {
        return forProcess(new PlanAnchor(origin, rotation), process);
    }

    public PlanAnchor anchor() { return anchor; }
    public BlockPos3i origin() { return anchor.position(); }
    public FanProcessingSpec process() { return process; }
    public List<FanProcessingPlacement> placements() { return placements; }
    public List<PlacementTarget> placementTargets() { return placementTargets; }
    public List<BlockPos3i> preflightPositions() { return preflightPositions; }
    public BlockPos3i inputEntityPosition() { return inputEntityPosition; }

    /** The mirrored water channel fixes the reviewed network sign along the fan facing. */
    public PlanBlockFacing airflowFacing() {
        PlanBlockFacing facing = placement(FanProcessingRole.ENCASED_FAN).facing();
        if (facing == PlanBlockFacing.UP || facing == PlanBlockFacing.DOWN
                || facing == PlanBlockFacing.NONE) {
            throw new IllegalStateException("C-06 survival fan requires horizontal airflow");
        }
        return facing;
    }

    public FanProcessingPlacement placement(FanProcessingRole role) {
        return placements.stream().filter(value -> value.role() == role).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("C-06 role is absent: " + role));
    }

    public Set<BlockPos3i> botForbiddenPositions() {
        if (!process.mode().dangerousToBots()) {
            return Set.of();
        }
        return Set.of(
                placement(FanProcessingRole.PROCESSING_MEDIUM).position(),
                placement(FanProcessingRole.MEDIUM_STOP).position(),
                inputEntityPosition);
    }

    private void validate() {
        EnumSet<FanProcessingRole> roles = EnumSet.noneOf(FanProcessingRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            FanProcessingPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException("C-06 placement order, roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(FanProcessingRole.class))
                || positions.contains(inputEntityPosition)
                || placement(FanProcessingRole.PROCESSING_MEDIUM).dangerousToBots()
                        != process.mode().dangerousToBots()) {
            throw new IllegalStateException("C-06 topology or medium safety contract is inconsistent");
        }
    }

    private static List<BlockPos3i> preflight(PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>();
        for (int x = -2; x <= 3; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -7; z <= 2; z++) {
                    values.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-06 preflight volume exceeds its bound");
        }
        return List.copyOf(values);
    }

    private static FanProcessingPlacement placement(
            int order,
            FanProcessingRole role,
            ResourceId block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing,
            boolean dangerous) {
        return new FanProcessingPlacement(
                order, role, block, transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis), transform.rotate(facing), dangerous);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
