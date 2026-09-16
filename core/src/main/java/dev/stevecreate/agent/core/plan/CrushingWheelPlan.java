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

/**
 * Fixed bounded C-05 topology. Create owns the runtime controller in the one-block wheel gap;
 * the plan owns both wheels, their independent enclosed water-wheel drives, and
 * hopper-to-chest output logistics.
 */
public final class CrushingWheelPlan {
    public static final int MAX_PLACEMENTS = 24;
    public static final int MAX_PREFLIGHT_POSITIONS = 256;

    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER = id("minecraft:water");
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId CRUSHING_WHEEL = id("create:crushing_wheel");
    private static final ResourceId HOPPER = id("minecraft:hopper");
    private static final ResourceId CHEST = id("minecraft:chest");

    private static final CrushingProcessSpec PROCESS = new CrushingProcessSpec(
            id("create:crushing/gravel"),
            id("create:crushing"),
            id("minecraft:gravel"),
            1,
            id("minecraft:sand"),
            1,
            List.of(
                    new ProcessResource(id("minecraft:flint"), GenericResourceType.ITEM, 1),
                    new ProcessResource(id("minecraft:clay_ball"), GenericResourceType.ITEM, 1)),
            400,
            2_400);

    private final PlanAnchor anchor;
    private final CrushingProcessSpec process;
    private final List<CrushingWheelPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;
    private final BlockPos3i controllerPosition;
    private final BlockPos3i inputSpawnPosition;

    private CrushingWheelPlan(PlanAnchor anchor, CrushingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.placements = resolvePlacements(transform);
        this.placementTargets = placements.stream()
                .map(value -> new PlacementTarget(
                        id("steve_industrial:c05/role/"
                                + value.role().name().toLowerCase(Locale.ROOT)),
                        value.position()))
                .toList();
        this.preflightPositions = resolvePreflightPositions(transform);
        this.controllerPosition = transform.resolve(new BlockPos3i(1, 3, 0));
        this.inputSpawnPosition = transform.resolve(new BlockPos3i(1, 4, 0));
        validate();
    }

    public static CrushingWheelPlan at(BlockPos3i origin) {
        return new CrushingWheelPlan(PlanAnchor.at(origin), PROCESS);
    }

    public static CrushingWheelPlan at(BlockPos3i origin, QuarterTurn rotation) {
        return new CrushingWheelPlan(new PlanAnchor(origin, rotation), PROCESS);
    }

    public static CrushingWheelPlan forProcess(PlanAnchor anchor, CrushingProcessSpec process) {
        return new CrushingWheelPlan(anchor, process);
    }

    public PlanAnchor anchor() {
        return anchor;
    }

    public BlockPos3i origin() {
        return anchor.position();
    }

    public QuarterTurn rotation() {
        return anchor.rotation();
    }

    public CrushingProcessSpec process() {
        return process;
    }

    public List<CrushingWheelPlacement> placements() {
        return placements;
    }

    public List<PlacementTarget> placementTargets() {
        return placementTargets;
    }

    public List<BlockPos3i> preflightPositions() {
        return preflightPositions;
    }

    public BlockPos3i controllerPosition() {
        return controllerPosition;
    }

    public BlockPos3i inputSpawnPosition() {
        return inputSpawnPosition;
    }

    public CrushingWheelPlacement placement(CrushingWheelRole role) {
        Objects.requireNonNull(role, "role");
        return placements.stream()
                .filter(value -> value.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("C-05 role is absent: " + role));
    }

    private static List<CrushingWheelPlacement> resolvePlacements(PlanTransform transform) {
        return List.of(
                placement(1, CrushingWheelRole.LEFT_FLOW_FLOOR, STONE, transform,
                        1, 2, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(2, CrushingWheelRole.LEFT_SOURCE_WEST_WALL, STONE, transform,
                        0, 5, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(3, CrushingWheelRole.LEFT_SOURCE_EAST_WALL, STONE, transform,
                        2, 5, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(4, CrushingWheelRole.SHARED_SOURCE_CENTER_WALL, STONE, transform,
                        1, 5, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, CrushingWheelRole.LEFT_SOURCE_OUTER_WALL, STONE, transform,
                        1, 5, 2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(6, CrushingWheelRole.RIGHT_FLOW_FLOOR, STONE, transform,
                        1, 2, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(7, CrushingWheelRole.RIGHT_SOURCE_WEST_WALL, STONE, transform,
                        0, 5, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(8, CrushingWheelRole.RIGHT_SOURCE_EAST_WALL, STONE, transform,
                        2, 5, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(9, CrushingWheelRole.RIGHT_SOURCE_OUTER_WALL, STONE, transform,
                        1, 5, -2, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(10, CrushingWheelRole.OUTPUT_CHEST, CHEST, transform,
                        1, 0, 0, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(11, CrushingWheelRole.OUTPUT_HOPPER, HOPPER, transform,
                        1, 1, 0, PlanBlockAxis.NONE, PlanBlockFacing.DOWN),
                placement(12, CrushingWheelRole.LEFT_WATER_SOURCE, WATER, transform,
                        1, 5, 1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(13, CrushingWheelRole.RIGHT_WATER_SOURCE, WATER, transform,
                        1, 5, -1, PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(14, CrushingWheelRole.LEFT_DRIVE, WATER_WHEEL, transform,
                        0, 3, 1, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(15, CrushingWheelRole.RIGHT_DRIVE, WATER_WHEEL, transform,
                        2, 3, -1, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(16, CrushingWheelRole.LEFT_WHEEL, CRUSHING_WHEEL, transform,
                        0, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE),
                placement(17, CrushingWheelRole.RIGHT_WHEEL, CRUSHING_WHEEL, transform,
                        2, 3, 0, PlanBlockAxis.Z, PlanBlockFacing.NONE));
    }

    private void validate() {
        if (placements.isEmpty() || placements.size() > MAX_PLACEMENTS) {
            throw new IllegalStateException("C-05 placement count is outside its bound");
        }
        EnumSet<CrushingWheelRole> roles = EnumSet.noneOf(CrushingWheelRole.class);
        Set<BlockPos3i> positions = new HashSet<>();
        for (int index = 0; index < placements.size(); index++) {
            CrushingWheelPlacement placement = placements.get(index);
            if (placement.order() != index + 1
                    || !roles.add(placement.role())
                    || !positions.add(placement.position())) {
                throw new IllegalStateException(
                        "C-05 placement order, role, and position must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(CrushingWheelRole.class))
                || positions.contains(controllerPosition)
                || positions.contains(inputSpawnPosition)) {
            throw new IllegalStateException(
                    "C-05 must preserve every owned role and both runtime-only cells");
        }
    }

    private static List<BlockPos3i> resolvePreflightPositions(PlanTransform transform) {
        List<BlockPos3i> values = new ArrayList<>(150);
        for (int x = -1; x <= 3; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = -2; z <= 2; z++) {
                    values.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (values.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-05 preflight volume exceeds its fixed bound");
        }
        return List.copyOf(values);
    }

    private static CrushingWheelPlacement placement(
            int order,
            CrushingWheelRole role,
            ResourceId block,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
        return new CrushingWheelPlacement(
                order,
                role,
                block,
                transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(axis),
                transform.rotate(facing));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
