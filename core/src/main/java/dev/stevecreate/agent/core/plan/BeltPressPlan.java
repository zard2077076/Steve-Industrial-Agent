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
 * Fixed, bounded C-04 plan. Coordinates, build actions, materials, final blocks and pressing recipe
 * are deterministic; callers supply only a typed origin.
 */
public final class BeltPressPlan {
    public static final int MAX_BUILD_STEPS = 12;
    public static final int MAX_FINAL_PLACEMENTS = 16;
    public static final int MAX_PREFLIGHT_POSITIONS = 256;

    private static final ResourceId SHAFT = id("create:shaft");
    private static final ResourceId CREATIVE_MOTOR = id("create:creative_motor");
    private static final ResourceId MECHANICAL_PRESS = id("create:mechanical_press");
    private static final ResourceId BELT = id("create:belt");
    private static final ResourceId BELT_CONNECTOR = id("create:belt_connector");
    private static final ResourceId ANDESITE_FUNNEL = id("create:andesite_funnel");
    private static final ResourceId CHEST = id("minecraft:chest");

    private static final PressingProcessSpec PROCESS = new PressingProcessSpec(
            id("create:pressing/iron_ingot"),
            id("create:pressing"),
            id("minecraft:iron_ingot"),
            1,
            id("create:iron_sheet"),
            1,
            400,
            1_200);

    private final PlanAnchor anchor;
    private final PressingProcessSpec process;
    private final List<BeltPressBuildStep> buildSteps;
    private final List<BeltPressPlacement> finalPlacements;
    private final List<PlacementTarget> placementTargets;
    private final List<BlockPos3i> preflightPositions;

    private BeltPressPlan(PlanAnchor anchor, PressingProcessSpec process) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.process = Objects.requireNonNull(process, "process");
        PlanTransform transform = new PlanTransform(anchor);
        this.buildSteps = resolveBuildSteps(transform);
        this.finalPlacements = resolveFinalPlacements(transform);
        this.placementTargets = finalPlacements.stream()
                .map(placement -> new PlacementTarget(
                        id("steve_industrial:c04/role/"
                                + placement.role().name().toLowerCase(Locale.ROOT)),
                        placement.position()))
                .toList();
        this.preflightPositions = resolvePreflightPositions(transform);
        validateResolvedPlan();
    }

    public static BeltPressPlan at(BlockPos3i origin) {
        return new BeltPressPlan(PlanAnchor.at(origin), PROCESS);
    }

    public static BeltPressPlan at(BlockPos3i origin, QuarterTurn rotation) {
        return new BeltPressPlan(new PlanAnchor(origin, rotation), PROCESS);
    }

    public static BeltPressPlan at(PlanAnchor anchor) {
        return new BeltPressPlan(anchor, PROCESS);
    }

    /** Goal-driven variant; build topology stays typed while the runtime recipe supplies processing. */
    public static BeltPressPlan forProcess(
            PlanAnchor anchor,
            PressingProcessSpec process) {
        return new BeltPressPlan(anchor, process);
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

    public List<BeltPressBuildStep> buildSteps() {
        return buildSteps;
    }

    public List<BeltPressPlacement> finalPlacements() {
        return finalPlacements;
    }

    /** Final role ownership; temporary pulley shafts are deliberate construction transitions. */
    public List<PlacementTarget> placementTargets() {
        return placementTargets;
    }

    public List<BlockPos3i> preflightPositions() {
        return preflightPositions;
    }

    public PressingProcessSpec process() {
        return process;
    }

    public BeltPressPlacement placement(BeltPressRole role) {
        Objects.requireNonNull(role, "role");
        return finalPlacements.stream()
                .filter(placement -> placement.role() == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Role is absent from plan: " + role));
    }

    public BeltPressBuildStep.ConnectBelt beltConnection() {
        return buildSteps.stream()
                .filter(BeltPressBuildStep.ConnectBelt.class::isInstance)
                .map(BeltPressBuildStep.ConnectBelt.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Typed belt connection is absent"));
    }

    private static List<BeltPressBuildStep> resolveBuildSteps(PlanTransform transform) {
        return List.of(
                place(1, BeltPressBuildRole.BELT_START_PULLEY_SHAFT, SHAFT, transform, 0, 1, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.NONE),
                place(2, BeltPressBuildRole.BELT_END_PULLEY_SHAFT, SHAFT, transform, 2, 1, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.NONE),
                place(3, BeltPressBuildRole.BELT_DRIVE, CREATIVE_MOTOR, transform, 0, 1, 1,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                place(4, BeltPressBuildRole.PRESS_DRIVE, CREATIVE_MOTOR, transform, 1, 3, 1,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                place(5, BeltPressBuildRole.MECHANICAL_PRESS, MECHANICAL_PRESS, transform, 1, 3, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                place(6, BeltPressBuildRole.OUTPUT_CHEST, CHEST, transform, 3, 0, 0,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                place(7, BeltPressBuildRole.OUTPUT_FUNNEL, ANDESITE_FUNNEL, transform, 3, 1, 0,
                        PlanBlockAxis.NONE, PlanBlockFacing.UP),
                new BeltPressBuildStep.ConnectBelt(
                        8,
                        BELT_CONNECTOR,
                        transform.resolve(new BlockPos3i(0, 1, 0)),
                        transform.resolve(new BlockPos3i(2, 1, 0)),
                        3));
    }

    private static List<BeltPressPlacement> resolveFinalPlacements(PlanTransform transform) {
        return List.of(
                placement(1, BeltPressRole.BELT_DRIVE, CREATIVE_MOTOR, transform, 0, 1, 1,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                placement(2, BeltPressRole.PRESS_DRIVE, CREATIVE_MOTOR, transform, 1, 3, 1,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                placement(3, BeltPressRole.MECHANICAL_PRESS, MECHANICAL_PRESS, transform, 1, 3, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.NORTH),
                placement(4, BeltPressRole.OUTPUT_CHEST, CHEST, transform, 3, 0, 0,
                        PlanBlockAxis.NONE, PlanBlockFacing.NONE),
                placement(5, BeltPressRole.OUTPUT_FUNNEL, ANDESITE_FUNNEL, transform, 3, 1, 0,
                        PlanBlockAxis.NONE, PlanBlockFacing.UP),
                placement(6, BeltPressRole.BELT_START, BELT, transform, 0, 1, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.EAST),
                placement(7, BeltPressRole.BELT_PRESSING, BELT, transform, 1, 1, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.EAST),
                placement(8, BeltPressRole.BELT_END, BELT, transform, 2, 1, 0,
                        PlanBlockAxis.Z, PlanBlockFacing.EAST));
    }

    private void validateResolvedPlan() {
        if (buildSteps.isEmpty() || buildSteps.size() > MAX_BUILD_STEPS) {
            throw new IllegalStateException("C-04 build-step count is outside its fixed bound");
        }
        if (finalPlacements.isEmpty() || finalPlacements.size() > MAX_FINAL_PLACEMENTS) {
            throw new IllegalStateException("C-04 final-placement count is outside its fixed bound");
        }

        EnumSet<BeltPressBuildRole> buildRoles = EnumSet.noneOf(BeltPressBuildRole.class);
        for (int index = 0; index < buildSteps.size(); index++) {
            BeltPressBuildStep step = buildSteps.get(index);
            if (step.order() != index + 1 || !buildRoles.add(step.role())) {
                throw new IllegalStateException("C-04 build order and roles must be unique and contiguous");
            }
        }
        if (!buildRoles.equals(EnumSet.allOf(BeltPressBuildRole.class))
                || !(buildSteps.get(buildSteps.size() - 1) instanceof BeltPressBuildStep.ConnectBelt)) {
            throw new IllegalStateException("C-04 must contain every build role and connect the belt last");
        }

        EnumSet<BeltPressRole> finalRoles = EnumSet.noneOf(BeltPressRole.class);
        Set<BlockPos3i> finalPositions = new HashSet<>();
        for (int index = 0; index < finalPlacements.size(); index++) {
            BeltPressPlacement placement = finalPlacements.get(index);
            if (placement.order() != index + 1
                    || !finalRoles.add(placement.role())
                    || !finalPositions.add(placement.position())) {
                throw new IllegalStateException("C-04 final order, roles and positions must be unique");
            }
        }
        if (!finalRoles.equals(EnumSet.allOf(BeltPressRole.class))) {
            throw new IllegalStateException("C-04 must contain every final role exactly once");
        }

        BeltPressBuildStep.ConnectBelt connection = beltConnection();
        if (!connection.startPosition().equals(placement(BeltPressRole.BELT_START).position())
                || !connection.endPosition().equals(placement(BeltPressRole.BELT_END).position())) {
            throw new IllegalStateException("Typed belt connection endpoints do not match final placements");
        }
        long beltPlacements = finalPlacements.stream().filter(placement -> placement.role().isBelt()).count();
        if (beltPlacements != connection.expectedSegments()) {
            throw new IllegalStateException("Typed belt segment count does not match the connection step");
        }
    }

    /** Fixed 6 x 5 x 5 volume checked without loading chunks before execution. */
    private static List<BlockPos3i> resolvePreflightPositions(PlanTransform transform) {
        List<BlockPos3i> positions = new ArrayList<>(150);
        for (int x = -1; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = -2; z <= 2; z++) {
                    positions.add(transform.resolve(new BlockPos3i(x, y, z)));
                }
            }
        }
        if (positions.size() > MAX_PREFLIGHT_POSITIONS) {
            throw new IllegalStateException("C-04 preflight volume exceeds its fixed bound");
        }
        return List.copyOf(positions);
    }

    private static BeltPressBuildStep.PlaceBlock place(
            int order,
            BeltPressBuildRole role,
            ResourceId materialId,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis rotationAxis,
            PlanBlockFacing facing) {
        return new BeltPressBuildStep.PlaceBlock(
                order,
                role,
                materialId,
                transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(rotationAxis),
                transform.rotate(facing));
    }

    private static BeltPressPlacement placement(
            int order,
            BeltPressRole role,
            ResourceId blockId,
            PlanTransform transform,
            int x,
            int y,
            int z,
            PlanBlockAxis rotationAxis,
            PlanBlockFacing facing) {
        return new BeltPressPlacement(
                order,
                role,
                blockId,
                transform.resolve(new BlockPos3i(x, y, z)),
                transform.rotate(rotationAxis),
                transform.rotate(facing));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
