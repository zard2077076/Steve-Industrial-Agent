package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.execution.BoundedExecutionStep;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionStep;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.graph.EdgeMode;
import dev.stevecreate.agent.core.graph.MachineAxis;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceRequirement;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Shared bounded runner contract for C-09 Basin + Mechanical Press. */
public final class BasinPressGenericExecutionPlan {
    public static final ResourceId PLAN_ID = id("steve_industrial:c09/basin_press_plan");
    public static final ResourceId GRAPH_ID = id("steve_industrial:c09/basin_press_graph");
    public static final ResourceId BUILD_STEP_ID = id("steve_industrial:c09/step/build");
    public static final ResourceId POWER_STEP_ID = id("steve_industrial:c09/step/power");
    public static final ResourceId FEED_STEP_ID = id("steve_industrial:c09/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID = id("steve_industrial:c09/step/process");

    public static final ResourceId ACTION_HANDLER_ID = id("create:v606/basin_compacting");
    public static final ResourceId BUILD_OPERATION_ID = id("create:v606/build_basin_compacting");
    public static final ResourceId POWER_OPERATION_ID =
            id("create:v606/observe_basin_press_power");
    public static final ResourceId FEED_OPERATION_ID = id("create:v606/feed_basin_compacting");
    public static final ResourceId PROCESS_OPERATION_ID =
            id("create:v606/observe_basin_compacting_output");
    public static final ResourceId CONDITION_EVALUATOR_ID =
            id("create:v606/c09_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION =
            id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION =
            id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE =
            id("steve_industrial:c09/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE =
            id("steve_industrial:c09/evidence/power_present");
    public static final ResourceId FEED_EVIDENCE =
            id("steve_industrial:c09/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    public static final ResourceId BASIN_CONTENTS_OBSERVED =
            id("create:evidence/basin_contents_observed");
    public static final ResourceId PRESS_CYCLE_OBSERVED =
            id("create:evidence/basin_press_cycle_observed");
    public static final ResourceId CHEST_OUTPUT_OBSERVED =
            id("create:evidence/basin_output_stored");

    public static final ResourceId PRESS_NODE_ID =
            id("steve_industrial:c09/node/mechanical_press");
    public static final ResourceId BASIN_NODE_ID =
            id("steve_industrial:c09/node/basin");
    public static final ResourceId CHEST_NODE_ID =
            id("steve_industrial:c09/node/output_chest");

    private BasinPressGenericExecutionPlan() {}

    public static GenericExecutionPlan from(BasinPressPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID, graph(plan), plan.process().genericSpec(), steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule> verificationRules(
            BasinPressPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ResourceId, GenericVerificationRule> rules = new LinkedHashMap<>();
        rules.put(BUILD_STEP_ID, rule(
                BUILD_EVIDENCE, VerificationEvidenceKind.BLOCK_STATE_MATCH,
                GRAPH_ID, buildTimeout(plan)));
        rules.put(POWER_STEP_ID, rule(
                POWER_EVIDENCE, VerificationEvidenceKind.POWER_PRESENT,
                PRESS_NODE_ID, plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE, VerificationEvidenceKind.PROCESS_STARTED,
                BASIN_NODE_ID, 20));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                List.of(
                        exact(INPUT_CONSUMED,
                                VerificationEvidenceKind.INPUT_CONSUMED, BASIN_NODE_ID),
                        exact(PROCESS_COMPLETED,
                                VerificationEvidenceKind.PROCESS_COMPLETED, PRESS_NODE_ID),
                        exact(OUTPUT_PRODUCED,
                                VerificationEvidenceKind.OUTPUT_PRODUCED, BASIN_NODE_ID),
                        exact(BASIN_CONTENTS_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                BASIN_NODE_ID),
                        exact(PRESS_CYCLE_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                PRESS_NODE_ID),
                        exact(CHEST_OUTPUT_OBSERVED,
                                VerificationEvidenceKind.OUTPUT_STORED, CHEST_NODE_ID)),
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(BasinPressPlan plan) {
        Builder builder = new Builder(plan.origin());
        MachineNode wheel = builder.node(
                id("steve_industrial:c09/node/water_wheel"), "water_wheel",
                plan.placement(BasinPressRole.WATER_WHEEL),
                Set.of(id("steve_industrial:kinetic_source")), Map.of());
        MachineNode bottomGearbox = transmission(builder, plan, BasinPressRole.BOTTOM_GEARBOX);
        MachineNode verticalShaft = transmission(builder, plan, BasinPressRole.VERTICAL_SHAFT);
        MachineNode topGearbox = transmission(builder, plan, BasinPressRole.TOP_GEARBOX);
        MachineNode horizontalShaft = transmission(builder, plan, BasinPressRole.HORIZONTAL_SHAFT);
        MachineNode press = builder.node(
                PRESS_NODE_ID, "mechanical_press",
                plan.placement(BasinPressRole.MECHANICAL_PRESS),
                Set.of(id("create:basin_compacting")),
                Map.of("recipe", plan.process().recipeId().toString(),
                        "belt_pressing", "forbidden",
                        "fluid_recipe", plan.process().fluidInputs().isEmpty()
                                ? "none" : "exact_declared"));
        MachineNode basin = builder.node(
                BASIN_NODE_ID, "basin",
                plan.placement(BasinPressRole.BASIN),
                plan.process().fluidInputs().isEmpty()
                        ? Set.of(id("create:basin_item_inventory"))
                        : Set.of(id("create:basin_item_inventory"),
                                id("create:basin_fluid_inventory")),
                Map.of("fluids", plan.process().fluidInputs().isEmpty()
                                ? "none" : "exact_declared",
                        "heat", "none"));
        MachineNode chest = builder.node(
                CHEST_NODE_ID, "output_chest",
                plan.placement(BasinPressRole.OUTPUT_CHEST),
                Set.of(id("steve_industrial:item_storage")), Map.of());
        MachineNode input = builder.boundary(
                id("steve_industrial:c09/node/item_input"),
                id("steve_industrial:item_source"),
                new BlockPos3i(0, 3, -3));
        MachineNode fluidInput = plan.process().fluidInputs().isEmpty()
                ? null
                : builder.boundary(
                        id("steve_industrial:c09/node/fluid_input"),
                        id("steve_industrial:fluid_source"),
                        new BlockPos3i(1, 4, -3));

        for (BasinPressRole role : List.of(
                BasinPressRole.FLOW_CATCH_FLOOR,
                BasinPressRole.FLOW_CATCH_WEST_WALL,
                BasinPressRole.FLOW_CATCH_EAST_WALL,
                BasinPressRole.FLOW_CATCH_NORTH_WALL,
                BasinPressRole.FLOW_CATCH_SOUTH_WALL,
                BasinPressRole.FLOW_CHAMBER_WEST_WALL,
                BasinPressRole.FLOW_CHAMBER_EAST_WALL,
                BasinPressRole.FLOW_CHAMBER_NORTH_WALL,
                BasinPressRole.FLOW_CHAMBER_SOUTH_WALL,
                BasinPressRole.FLOW_CHANNEL_WEST_WALL,
                BasinPressRole.FLOW_CHANNEL_EAST_WALL,
                BasinPressRole.FLOW_CHANNEL_NORTH_WALL,
                BasinPressRole.PRESS_PLATFORM)) {
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            builder.node(id("steve_industrial:c09/node/" + name), name,
                    plan.placement(role), Set.of(id("steve_industrial:fluid_containment")),
                    Map.of("owned", "true"));
        }
        builder.node(id("steve_industrial:c09/node/water_source"), "water_source",
                plan.placement(BasinPressRole.WATER_SOURCE),
                Set.of(id("steve_industrial:kinetic_source_fluid")), Map.of());

        MachinePort wheelOut = builder.port(
                "water_wheel/power_out", wheel, GenericResourceType.ROTATIONAL_POWER,
                PortMode.OUTPUT);
        MachinePort bottomIn = builder.port("bottom_gearbox/power_in", bottomGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort bottomOut = builder.port("bottom_gearbox/power_out", bottomGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort verticalIn = builder.port("vertical_shaft/power_in", verticalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort verticalOut = builder.port("vertical_shaft/power_out", verticalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort topIn = builder.port("top_gearbox/power_in", topGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort topOut = builder.port("top_gearbox/power_out", topGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort horizontalIn = builder.port("horizontal_shaft/power_in", horizontalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort horizontalOut = builder.port("horizontal_shaft/power_out", horizontalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort pressIn = builder.port(
                "press/power_in", press, GenericResourceType.ROTATIONAL_POWER,
                PortMode.INPUT);
        MachinePort sourceOut = builder.port(
                "source/items_out", input, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort basinIn = builder.port(
                "basin/items_in", basin, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort fluidSourceOut = fluidInput == null ? null : builder.port(
                "source/fluids_out", fluidInput,
                GenericResourceType.FLUID, PortMode.OUTPUT);
        MachinePort basinFluidIn = fluidInput == null ? null : builder.port(
                "basin/fluids_in", basin,
                GenericResourceType.FLUID, PortMode.INPUT);
        MachinePort basinOut = builder.port(
                "basin/items_out", basin, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort chestIn = builder.port(
                "chest/items_in", chest, GenericResourceType.ITEM, PortMode.INPUT);
        builder.edge("power/wheel_to_bottom_gearbox", wheelOut, bottomIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/bottom_gearbox_to_vertical_shaft", bottomOut, verticalIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/vertical_shaft_to_top_gearbox", verticalOut, topIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/top_gearbox_to_horizontal_shaft", topOut, horizontalIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/horizontal_shaft_to_press", horizontalOut, pressIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("items/source_to_basin", sourceOut, basinIn,
                GenericResourceType.ITEM);
        if (fluidInput != null) {
            builder.edge("fluids/source_to_basin", fluidSourceOut, basinFluidIn,
                    GenericResourceType.FLUID);
        }
        builder.edge("items/basin_to_chest", basinOut, chestIn,
                GenericResourceType.ITEM);
        return builder.build();
    }

    private static MachineNode transmission(
            Builder builder, BasinPressPlan plan, BasinPressRole role) {
        String name = role.name().toLowerCase(java.util.Locale.ROOT);
        return builder.node(id("steve_industrial:c09/node/" + name), name,
                plan.placement(role), Set.of(id("steve_industrial:kinetic_transmission")),
                Map.of());
    }

    private static List<GenericExecutionStep> steps(BasinPressPlan plan) {
        return List.of(
                step(BUILD_STEP_ID, GenericExecutionPhase.BUILD, BUILD_OPERATION_ID,
                        buildTimeout(plan), Set.of(BUILD_EVIDENCE)),
                step(POWER_STEP_ID, GenericExecutionPhase.POWER, POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()),
                        Set.of(POWER_EVIDENCE)),
                step(FEED_STEP_ID, GenericExecutionPhase.FEED_INPUT, FEED_OPERATION_ID,
                        20, Set.of(FEED_EVIDENCE)),
                step(PROCESS_STEP_ID, GenericExecutionPhase.PROCESS,
                        PROCESS_OPERATION_ID,
                        afterBudget(plan.process().processingTimeoutTicks()),
                        plan.process().genericSpec().requiredCompletionEvidence()));
    }

    private static GenericExecutionStep step(
            ResourceId id,
            GenericExecutionPhase phase,
            ResourceId operation,
            int timeout,
            Set<ResourceId> evidence) {
        String segment = id.path().substring(id.path().lastIndexOf('/') + 1);
        return new BoundedExecutionStep(
                id, phase,
                List.of(condition(segment, "precondition", PREFLIGHT_LOADED_CONDITION)),
                new StepActionDescriptor(ACTION_HANDLER_ID, operation, Map.of()),
                List.of(condition(segment, "success", PREFLIGHT_LOADED_CONDITION)),
                List.of(condition(segment, "failure", PREFLIGHT_UNLOADED_CONDITION)),
                timeout, RetryPolicy.NO_RETRY, true, Optional.empty(), evidence);
    }

    private static StepCondition condition(
            String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c09/condition/" + step + "/" + role),
                CONDITION_EVALUATOR_ID, type, Map.of());
    }

    private static GenericVerificationRule rule(
            ResourceId requirement,
            VerificationEvidenceKind kind,
            ResourceId target,
            int timeout) {
        return new GenericVerificationRule(
                List.of(exact(requirement, kind, target)), List.of(), timeout, false);
    }

    private static EvidenceRequirement exact(
            ResourceId requirement,
            VerificationEvidenceKind kind,
            ResourceId target) {
        return EvidenceRequirement.fromSourceToTarget(
                requirement, kind, ACTION_HANDLER_ID, target);
    }

    private static int buildTimeout(BasinPressPlan plan) {
        return Math.min(BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(Math.multiplyExact(plan.placements().size(), 32), 20));
    }

    private static int afterBudget(int value) {
        return value == BoundedExecutionStep.MAX_TIMEOUT_TICKS ? value : value + 1;
    }

    private static MachineOrientation orientation(
            PlanBlockAxis axis, PlanBlockFacing facing) {
        if (axis == PlanBlockAxis.NONE && facing == PlanBlockFacing.NONE) {
            return MachineOrientation.NONE;
        }
        if (axis == PlanBlockAxis.NONE) {
            return MachineOrientation.ofFacing(Direction6.valueOf(facing.name()));
        }
        if (facing == PlanBlockFacing.NONE) {
            return MachineOrientation.ofAxis(MachineAxis.valueOf(axis.name()));
        }
        return MachineOrientation.of(
                Direction6.valueOf(facing.name()), MachineAxis.valueOf(axis.name()));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static final class Builder {
        private final BlockPos3i origin;
        private final List<MachineNode> nodes = new ArrayList<>();
        private final List<MachinePort> ports = new ArrayList<>();
        private final List<MachineEdge> edges = new ArrayList<>();

        private Builder(BlockPos3i origin) { this.origin = origin; }

        private MachineNode node(
                ResourceId id,
                String role,
                BasinPressPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode value = new MachineNode(
                    id,
                    BasinPressGenericExecutionPlan.id(
                            "steve_industrial:c09/role/" + role),
                    placement.blockId(),
                    relative(placement.position()),
                    orientation(placement.rotationAxis(), placement.facing()),
                    capabilities,
                    configuration);
            nodes.add(value);
            return value;
        }

        private MachineNode boundary(
                ResourceId id, ResourceId implementation, BlockPos3i relative) {
            MachineNode value = new MachineNode(
                    id, BasinPressGenericExecutionPlan.id(
                            "steve_industrial:c09/role/item_input"),
                    implementation, relative, MachineOrientation.NONE,
                    Set.of(implementation), Map.of("boundary", "true"));
            nodes.add(value);
            return value;
        }

        private MachinePort port(
                String name,
                MachineNode owner,
                GenericResourceType type,
                PortMode mode) {
            MachinePort value = new MachinePort(
                    id("steve_industrial:c09/port/" + name),
                    owner.id(), type, mode, Optional.empty(),
                    OptionalLong.empty(), Map.of());
            ports.add(value);
            return value;
        }

        private void edge(
                String name,
                MachinePort source,
                MachinePort target,
                GenericResourceType type) {
            edges.add(new MachineEdge(
                    id("steve_industrial:c09/edge/" + name),
                    source.id(), target.id(), type, EdgeMode.DIRECTED,
                    OptionalLong.empty(), Map.of()));
        }

        private BlockPos3i relative(BlockPos3i position) {
            return new BlockPos3i(
                    Math.subtractExact(position.x(), origin.x()),
                    Math.subtractExact(position.y(), origin.y()),
                    Math.subtractExact(position.z(), origin.z()));
        }

        private UnifiedMachineGraph build() {
            return new UnifiedMachineGraph(GRAPH_ID, nodes, ports, edges);
        }
    }
}
