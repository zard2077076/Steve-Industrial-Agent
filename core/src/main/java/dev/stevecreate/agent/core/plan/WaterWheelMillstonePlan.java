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

/**
 * Fixed, bounded C-03 plan. The only caller-provided coordinate is the typed origin; layout,
 * materials, ordering and recipe are deterministic and validated rather than free text.
 */
public final class WaterWheelMillstonePlan {
    public static final int MAX_PLACEMENTS = 20;
    public static final int MAX_PREFLIGHT_POSITIONS = 256;

    private static final ResourceId STONE = id("minecraft:stone");
    private static final ResourceId WATER = id("minecraft:water");
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId GEARBOX = id("create:gearbox");
    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId MILLSTONE = id("create:millstone");

    private static final List<PlanPlacement> TEMPLATE = List.of(
            placement(1, WaterWheelMillstoneRole.FLOW_CATCH_FLOOR, STONE, 0, 1, -1, PlanBlockAxis.NONE),
            placement(2, WaterWheelMillstoneRole.FLOW_CATCH_WEST_WALL, STONE, -1, 2, -1, PlanBlockAxis.NONE),
            placement(3, WaterWheelMillstoneRole.FLOW_CATCH_EAST_WALL, STONE, 1, 2, -1, PlanBlockAxis.NONE),
            placement(4, WaterWheelMillstoneRole.FLOW_CATCH_NORTH_WALL, STONE, 0, 2, -2, PlanBlockAxis.NONE),
            placement(5, WaterWheelMillstoneRole.FLOW_CATCH_SOUTH_WALL, STONE, 0, 2, 0, PlanBlockAxis.NONE),
            placement(6, WaterWheelMillstoneRole.FLOW_CHAMBER_WEST_WALL, STONE, -1, 4, -1, PlanBlockAxis.NONE),
            placement(7, WaterWheelMillstoneRole.FLOW_CHAMBER_EAST_WALL, STONE, 1, 4, -1, PlanBlockAxis.NONE),
            placement(8, WaterWheelMillstoneRole.FLOW_CHAMBER_NORTH_WALL, STONE, 0, 4, -2, PlanBlockAxis.NONE),
            placement(9, WaterWheelMillstoneRole.FLOW_CHAMBER_SOUTH_WALL, STONE, 0, 4, 0, PlanBlockAxis.NONE),
            placement(10, WaterWheelMillstoneRole.FLOW_CHANNEL_WEST_WALL, STONE, -1, 3, -1, PlanBlockAxis.NONE),
            placement(11, WaterWheelMillstoneRole.FLOW_CHANNEL_EAST_WALL, STONE, 1, 3, -1, PlanBlockAxis.NONE),
            placement(12, WaterWheelMillstoneRole.FLOW_CHANNEL_NORTH_WALL, STONE, 0, 3, -2, PlanBlockAxis.NONE),
            placement(13, WaterWheelMillstoneRole.WATER_WHEEL, WATER_WHEEL, 0, 3, 0, PlanBlockAxis.X),
            placement(14, WaterWheelMillstoneRole.GEARBOX, GEARBOX, 1, 3, 0, PlanBlockAxis.Z),
            placement(15, WaterWheelMillstoneRole.VERTICAL_SHAFT, SHAFT, 1, 4, 0, PlanBlockAxis.Y),
            placement(16, WaterWheelMillstoneRole.MILLSTONE, MILLSTONE, 1, 5, 0, PlanBlockAxis.NONE),
            placement(17, WaterWheelMillstoneRole.WATER_SOURCE, WATER, 0, 4, -1, PlanBlockAxis.NONE));

    private static final MillingProcessSpec PROCESS = new MillingProcessSpec(
            id("create:milling/cobblestone"),
            id("create:milling"),
            id("minecraft:cobblestone"),
            1,
            id("minecraft:gravel"),
            1,
            400,
            1_200);

    private final PlanAnchor anchor;
    private final MillingProcessSpec process;
    private final List<ResolvedPlanPlacement> placements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;

    private WaterWheelMillstonePlan(PlanAnchor anchor, MillingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        validateTemplate();
        this.placements = TEMPLATE.stream().map(placement -> placement.resolve(transform)).toList();
        this.placementTargets = placements.stream()
                .map(placement -> new PlacementTarget(
                        id("steve_industrial:c03/role/"
                                + placement.role().name().toLowerCase(Locale.ROOT)),
                        placement.position()))
                .toList();
        this.preflightPositions = resolvePreflightPositions(transform);
    }

    public static WaterWheelMillstonePlan at(BlockPos3i origin) {
        return new WaterWheelMillstonePlan(PlanAnchor.at(origin), PROCESS);
    }

    public static WaterWheelMillstonePlan at(BlockPos3i origin, QuarterTurn rotation) {
        return new WaterWheelMillstonePlan(new PlanAnchor(origin, rotation), PROCESS);
    }

    public static WaterWheelMillstonePlan at(PlanAnchor anchor) {
        return new WaterWheelMillstonePlan(anchor, PROCESS);
    }

    /** Goal-driven variant; geometry stays typed while the verified runtime recipe supplies processing. */
    public static WaterWheelMillstonePlan forProcess(
            PlanAnchor anchor,
            MillingProcessSpec process) {
        return new WaterWheelMillstonePlan(anchor, process);
    }

    public BlockPos3i origin() {
        return anchor.position();
    }

    public PlanAnchor anchor() {
        return anchor;
    }

    public QuarterTurn rotation() {
        return anchor.rotation();
    }

    public List<ResolvedPlanPlacement> placements() {
        return placements;
    }

    /** Final role ownership used by the generic fail-before-mutation placement gate. */
    public List<PlacementTarget> placementTargets() {
        return placementTargets;
    }

    public MillingProcessSpec process() {
        return process;
    }

    /** Fixed 5 x 7 x 5 volume checked without loading chunks before execution. */
    public List<BlockPos3i> preflightPositions() {
        return preflightPositions;
    }

    public ResolvedPlanPlacement placement(WaterWheelMillstoneRole role) {
        Objects.requireNonNull(role, "role");
        return placements.stream()
                .filter(placement -> placement.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Role is absent from plan: " + role));
    }

    private static void validateTemplate() {
        if (TEMPLATE.isEmpty() || TEMPLATE.size() > MAX_PLACEMENTS) {
            throw new IllegalStateException("C-03 template placement count is outside its fixed bound");
        }
        Set<Integer> orders = new HashSet<>();
        Set<BlockPos3i> positions = new HashSet<>();
        EnumSet<WaterWheelMillstoneRole> roles = EnumSet.noneOf(WaterWheelMillstoneRole.class);
        for (int index = 0; index < TEMPLATE.size(); index++) {
            PlanPlacement placement = TEMPLATE.get(index);
            if (placement.order() != index + 1
                    || !orders.add(placement.order())
                    || !positions.add(placement.relativePosition())
                    || !roles.add(placement.role())) {
                throw new IllegalStateException("C-03 template order, positions and roles must be unique");
            }
        }
        if (!roles.equals(EnumSet.allOf(WaterWheelMillstoneRole.class))) {
            throw new IllegalStateException("C-03 template must contain every required role exactly once");
        }
        if (TEMPLATE.get(TEMPLATE.size() - 1).role() != WaterWheelMillstoneRole.WATER_SOURCE) {
            throw new IllegalStateException("Water must be the final placement after the kinetic line is built");
        }
    }

    private static List<BlockPos3i> resolvePreflightPositions(PlanTransform transform) {
        List<BlockPos3i> positions = new ArrayList<>(175);
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y <= 6; y++) {
                for (int z = -3; z <= 1; z++) {
                    positions.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (positions.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-03 preflight volume exceeds its fixed bound");
        }
        return List.copyOf(positions);
    }

    private static PlanPlacement placement(
            int order,
            WaterWheelMillstoneRole role,
            ResourceId blockId,
            int x,
            int y,
            int z,
            PlanBlockAxis axis) {
        return new PlanPlacement(order, role, blockId, new BlockPos3i(x, y, z), axis);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
