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

/** Fixed bounded C-07 water-wheel-to-upward-saw production topology. */
public final class MechanicalSawPlan {
    public static final int MAX_PLACEMENTS = 32;
    public static final int MAX_PREFLIGHT_POSITIONS = 384;

    private static final CuttingProcessSpec PROCESS = new CuttingProcessSpec(
            id("create:cutting/andesite_alloy"),
            id("create:cutting"),
            id("create:andesite_alloy"),
            1,
            id("create:shaft"),
            6,
            400,
            1_200);

    private final PlanAnchor anchor;
    private final CuttingProcessSpec process;
    private final List<MechanicalSawPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;
    private final BlockPos3i inputEntityPosition;

    private MechanicalSawPlan(PlanAnchor anchor, CuttingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = List.of(
                placement(1, MechanicalSawRole.FLOW_CATCH_FLOOR, "minecraft:stone", transform,
                        0, 1, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(2, MechanicalSawRole.FLOW_CATCH_WEST_WALL, "minecraft:stone", transform,
                        -1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(3, MechanicalSawRole.FLOW_CATCH_EAST_WALL, "minecraft:stone", transform,
                        1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(4, MechanicalSawRole.FLOW_CATCH_NORTH_WALL, "minecraft:stone", transform,
                        0, 2, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, MechanicalSawRole.FLOW_CATCH_SOUTH_WALL, "minecraft:stone", transform,
                        0, 2, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(6, MechanicalSawRole.FLOW_CHAMBER_WEST_WALL, "minecraft:stone", transform,
                        -1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(7, MechanicalSawRole.FLOW_CHAMBER_EAST_WALL, "minecraft:stone", transform,
                        1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(8, MechanicalSawRole.FLOW_CHAMBER_NORTH_WALL, "minecraft:stone", transform,
                        0, 4, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(9, MechanicalSawRole.FLOW_CHAMBER_SOUTH_WALL, "minecraft:stone", transform,
                        0, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(10, MechanicalSawRole.FLOW_CHANNEL_WEST_WALL, "minecraft:stone", transform,
                        -1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(11, MechanicalSawRole.FLOW_CHANNEL_EAST_WALL, "minecraft:stone", transform,
                        1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(12, MechanicalSawRole.FLOW_CHANNEL_NORTH_WALL, "minecraft:stone", transform,
                        0, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(13, MechanicalSawRole.SAW_LANE_WEST_FLOOR, "minecraft:stone", transform,
                        0, 3, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(14, MechanicalSawRole.SAW_LANE_EAST_FLOOR, "minecraft:stone", transform,
                        1, 3, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(15, MechanicalSawRole.WATER_WHEEL, "create:water_wheel", transform,
                        0, 3, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(16, MechanicalSawRole.BOTTOM_GEARBOX, "create:gearbox", transform,
                        1, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(17, MechanicalSawRole.VERTICAL_SHAFT, "create:shaft", transform,
                        1, 4, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(18, MechanicalSawRole.TOP_GEARBOX, "create:gearbox", transform,
                        1, 5, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(19, MechanicalSawRole.HORIZONTAL_SHAFT, "create:shaft", transform,
                        1, 5, -1, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(20, MechanicalSawRole.MECHANICAL_SAW, "create:mechanical_saw", transform,
                        1, 5, -2, PlanBlockAxis.Z, PlanBlockFacing.UP),
                placement(21, MechanicalSawRole.INPUT_DEPOT, "create:depot", transform,
                        1, 4, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(22, MechanicalSawRole.OUTPUT_CHEST, "minecraft:chest", transform,
                        2, 5, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(23, MechanicalSawRole.WATER_SOURCE, "minecraft:water", transform,
                        0, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE));
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c07/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = preflight(transform);
        this.inputEntityPosition = transform.resolve(new BlockPos3i(1, 6, -2));
        validate();
    }

    public static MechanicalSawPlan at(BlockPos3i origin) {
        return new MechanicalSawPlan(PlanAnchor.at(origin), PROCESS);
    }

    public static MechanicalSawPlan at(BlockPos3i origin, QuarterTurn rotation) {
        return new MechanicalSawPlan(new PlanAnchor(origin, rotation), PROCESS);
    }

    public static MechanicalSawPlan forProcess(PlanAnchor anchor, CuttingProcessSpec process) {
        return new MechanicalSawPlan(anchor, process);
    }

    public PlanAnchor anchor() { return anchor; }
    public BlockPos3i origin() { return anchor.position(); }
    public QuarterTurn rotation() { return anchor.rotation(); }
    public CuttingProcessSpec process() { return process; }
    public List<MechanicalSawPlacement> placements() { return placements; }
    public List<PlacementTarget> placementTargets() { return placementTargets; }
    public List<BlockPos3i> preflightPositions() { return preflightPositions; }
    public BlockPos3i inputEntityPosition() { return inputEntityPosition; }

    public MechanicalSawPlacement placement(MechanicalSawRole role) {
        return placements.stream().filter(value -> value.role() == role).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("C-07 role is absent: " + role));
    }

    private void validate() {
        EnumSet<MechanicalSawRole> roles = EnumSet.noneOf(MechanicalSawRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            MechanicalSawPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException("C-07 placement order, roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(MechanicalSawRole.class))
                || positions.contains(inputEntityPosition)) {
            throw new IllegalStateException("C-07 topology is incomplete or overlaps its item lane");
        }
    }

    private static List<BlockPos3i> preflight(PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>(336);
        for (int x = -2; x <= 3; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -4; z <= 2; z++) {
                    values.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-07 preflight volume exceeds its bound");
        }
        return List.copyOf(values);
    }

    private static MechanicalSawPlacement placement(
            int order,
            MechanicalSawRole role,
            String block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
        return new MechanicalSawPlacement(
                order, role, id(block), transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis), transform.rotate(facing));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
