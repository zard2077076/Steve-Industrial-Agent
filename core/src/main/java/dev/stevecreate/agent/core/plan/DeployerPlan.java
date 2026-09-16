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

/** Fixed bounded water-wheel/downward-Deployer/owned-Depot topology for C-10. */
public final class DeployerPlan {
    public static final int MAX_PREFLIGHT_POSITIONS = 512;

    private final PlanAnchor anchor;
    private final DeployingProcessSpec process;
    private final List<DeployerPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;

    private DeployerPlan(PlanAnchor anchor, DeployingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = List.of(
                placement(1, DeployerRole.FLOW_CATCH_FLOOR, "minecraft:stone", transform,
                        0, 1, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(2, DeployerRole.FLOW_CATCH_WEST_WALL, "minecraft:stone", transform,
                        -1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(3, DeployerRole.FLOW_CATCH_EAST_WALL, "minecraft:stone", transform,
                        1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(4, DeployerRole.FLOW_CATCH_NORTH_WALL, "minecraft:stone", transform,
                        0, 2, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, DeployerRole.FLOW_CATCH_SOUTH_WALL, "minecraft:stone", transform,
                        0, 2, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(6, DeployerRole.FLOW_CHAMBER_WEST_WALL, "minecraft:stone", transform,
                        -1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(7, DeployerRole.FLOW_CHAMBER_EAST_WALL, "minecraft:stone", transform,
                        1, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(8, DeployerRole.FLOW_CHAMBER_NORTH_WALL, "minecraft:stone", transform,
                        0, 4, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(9, DeployerRole.FLOW_CHAMBER_SOUTH_WALL, "minecraft:stone", transform,
                        0, 4, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(10, DeployerRole.FLOW_CHANNEL_WEST_WALL, "minecraft:stone", transform,
                        -1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(11, DeployerRole.FLOW_CHANNEL_EAST_WALL, "minecraft:stone", transform,
                        1, 3, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(12, DeployerRole.FLOW_CHANNEL_NORTH_WALL, "minecraft:stone", transform,
                        0, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(13, DeployerRole.DEPLOYER_PLATFORM, "minecraft:stone", transform,
                        1, 3, -3, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(14, DeployerRole.WATER_WHEEL, "create:water_wheel", transform,
                        0, 3, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(15, DeployerRole.BOTTOM_GEARBOX, "create:gearbox", transform,
                        1, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(16, DeployerRole.VERTICAL_SHAFT, "create:shaft", transform,
                        1, 4, 0, PlanBlockAxis.Y, PlanBlockFacing.NONE),
                placement(17, DeployerRole.TOP_GEARBOX, "create:gearbox", transform,
                        1, 5, 0, PlanBlockAxis.X, PlanBlockFacing.NONE),
                placement(18, DeployerRole.HORIZONTAL_SHAFT, "create:shaft", transform,
                        1, 5, -1, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(19, DeployerRole.DEPLOYER, "create:deployer", transform,
                        1, 5, -2, PlanBlockAxis.Z, PlanBlockFacing.DOWN),
                placement(20, DeployerRole.INPUT_DEPOT, "create:depot", transform,
                        1, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(21, DeployerRole.OUTPUT_CHEST, "minecraft:chest", transform,
                        2, 3, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(22, DeployerRole.WATER_SOURCE, "minecraft:water", transform,
                        0, 4, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE));
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c10/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = preflight(transform);
        validate();
    }

    public static DeployerPlan forProcess(
            PlanAnchor anchor, DeployingProcessSpec process) {
        return new DeployerPlan(anchor, process);
    }

    public static DeployerPlan cogwheel(BlockPos3i origin) {
        return forProcess(
                PlanAnchor.at(origin),
                new DeployingProcessSpec(
                        id("create:deploying/cogwheel"),
                        id("create:shaft"),
                        1,
                        id("minecraft:oak_planks"),
                        HeldItemDisposition.CONSUMED,
                        id("create:cogwheel"),
                        1,
                        DeployerInteractionPolicy.safeDepotItemOnly(),
                        400,
                        2_000));
    }

    public static DeployerPlan at(
            BlockPos3i origin,
            QuarterTurn rotation,
            DeployingProcessSpec process) {
        return forProcess(new PlanAnchor(origin, rotation), process);
    }

    public PlanAnchor anchor() { return anchor; }
    public BlockPos3i origin() { return anchor.position(); }
    public DeployingProcessSpec process() { return process; }
    public List<DeployerPlacement> placements() { return placements; }
    public List<PlacementTarget> placementTargets() {
        return placementTargets;
    }
    public List<BlockPos3i> preflightPositions() {
        return preflightPositions;
    }

    public DeployerPlacement placement(DeployerRole role) {
        return placements.stream()
                .filter(value -> value.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "C-10 role is absent: " + role));
    }

    public BlockPos3i interactionPosition() {
        PlanTransform transform = new PlanTransform(anchor);
        return transform.resolve(new BlockPos3i(1, 4, -2));
    }

    private void validate() {
        EnumSet<DeployerRole> roles =
                EnumSet.noneOf(DeployerRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            DeployerPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException(
                        "C-10 placement order, roles and positions must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(DeployerRole.class))) {
            throw new IllegalStateException("C-10 topology is incomplete");
        }
        if (placement(DeployerRole.DEPLOYER).facing()
                != PlanBlockFacing.DOWN
                || placement(DeployerRole.DEPLOYER).rotationAxis()
                == PlanBlockAxis.Y
                || placement(DeployerRole.DEPLOYER).rotationAxis()
                == PlanBlockAxis.NONE
                || process.interactionPolicy().interactionFace()
                != PlanBlockFacing.DOWN) {
            throw new IllegalStateException(
                    "C-10 Deployer must face down with a horizontal shaft on the exact owned Depot");
        }
    }

    private static List<BlockPos3i> preflight(
            PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>();
        for (int x = -2; x <= 4; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -4; z <= 2; z++) {
                    values.add(transform.resolve(
                            new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException(
                    "C-10 preflight volume exceeds its bound");
        }
        return List.copyOf(values);
    }

    private static DeployerPlacement placement(
            int order,
            DeployerRole role,
            String block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
        return new DeployerPlacement(
                order,
                role,
                id(block),
                transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis),
                transform.rotate(facing));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
