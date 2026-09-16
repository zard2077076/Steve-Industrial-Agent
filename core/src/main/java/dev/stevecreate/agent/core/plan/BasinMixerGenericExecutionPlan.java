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

/** Shared bounded runner contract for C-08 Basin + Mechanical Mixer. */
public final class BasinMixerGenericExecutionPlan {
    public static final ResourceId PLAN_ID =
            id("steve_industrial:c08/basin_mixer_plan");
    public static final ResourceId GRAPH_ID =
            id("steve_industrial:c08/basin_mixer_graph");
    public static final ResourceId BUILD_STEP_ID =
            id("steve_industrial:c08/step/build");
    public static final ResourceId POWER_STEP_ID =
            id("steve_industrial:c08/step/power");
    public static final ResourceId FEED_STEP_ID =
            id("steve_industrial:c08/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID =
            id("steve_industrial:c08/step/process");

    public static final ResourceId ACTION_HANDLER_ID =
            id("create:v606/basin_mixing");
    public static final ResourceId BUILD_OPERATION_ID =
            id("create:v606/build_basin_mixing");
    public static final ResourceId POWER_OPERATION_ID =
            id("create:v606/observe_basin_mixer_power");
    public static final ResourceId FEED_OPERATION_ID =
            id("create:v606/feed_basin_mixing");
    public static final ResourceId PROCESS_OPERATION_ID =
            id("create:v606/observe_basin_mixing_output");
    public static final ResourceId CONDITION_EVALUATOR_ID =
            id("create:v606/c08_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION =
            id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION =
            id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE =
            id("steve_industrial:c08/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE =
            id("steve_industrial:c08/evidence/power_present");
    public static final ResourceId FEED_EVIDENCE =
            id("steve_industrial:c08/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    public static final ResourceId BASIN_CONTENTS_OBSERVED =
            id("create:evidence/mixing_basin_contents_observed");
    public static final ResourceId MIXER_CYCLE_OBSERVED =
            id("create:evidence/mixer_cycle_observed");
    public static final ResourceId HEAT_OBSERVED =
            id("create:evidence/mixing_heat_observed");
    public static final ResourceId CHEST_OUTPUT_OBSERVED =
            id("create:evidence/mixing_output_stored");

    public static final ResourceId MIXER_NODE_ID =
            id("steve_industrial:c08/node/mechanical_mixer");
    public static final ResourceId BASIN_NODE_ID =
            id("steve_industrial:c08/node/basin");
    public static final ResourceId HEAT_NODE_ID =
            id("steve_industrial:c08/node/heat_source");
    public static final ResourceId CHEST_NODE_ID =
            id("steve_industrial:c08/node/output_chest");

    private BasinMixerGenericExecutionPlan() {}

    public static GenericExecutionPlan from(BasinMixerPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID, graph(plan), plan.process().genericSpec(),
                steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule>
            verificationRules(BasinMixerPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ResourceId, GenericVerificationRule> rules =
                new LinkedHashMap<>();
        rules.put(BUILD_STEP_ID, rule(
                BUILD_EVIDENCE,
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                GRAPH_ID,
                buildTimeout(plan)));
        rules.put(POWER_STEP_ID, rule(
                POWER_EVIDENCE,
                VerificationEvidenceKind.POWER_PRESENT,
                MIXER_NODE_ID,
                plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE,
                VerificationEvidenceKind.PROCESS_STARTED,
                BASIN_NODE_ID,
                20));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                List.of(
                        exact(INPUT_CONSUMED,
                                VerificationEvidenceKind.INPUT_CONSUMED,
                                BASIN_NODE_ID),
                        exact(PROCESS_COMPLETED,
                                VerificationEvidenceKind.PROCESS_COMPLETED,
                                MIXER_NODE_ID),
                        exact(OUTPUT_PRODUCED,
                                VerificationEvidenceKind.OUTPUT_PRODUCED,
                                BASIN_NODE_ID),
                        exact(BASIN_CONTENTS_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                BASIN_NODE_ID),
                        exact(MIXER_CYCLE_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                MIXER_NODE_ID),
                        exact(HEAT_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                HEAT_NODE_ID),
                        exact(CHEST_OUTPUT_OBSERVED,
                                VerificationEvidenceKind.OUTPUT_STORED,
                                CHEST_NODE_ID)),
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(BasinMixerPlan plan) {
        Builder builder = new Builder(plan.origin());
        MachineNode wheel = builder.node(
                id("steve_industrial:c08/node/water_wheel"),
                "water_wheel",
                plan.placement(BasinMixerRole.WATER_WHEEL),
                Set.of(id("steve_industrial:kinetic_source")),
                Map.of());
        MachineNode bottomGearbox = transmission(builder, plan, BasinMixerRole.BOTTOM_GEARBOX);
        MachineNode verticalShaft = transmission(builder, plan, BasinMixerRole.VERTICAL_SHAFT);
        MachineNode largeInput = transmission(builder, plan, BasinMixerRole.LARGE_COGWHEEL_INPUT);
        MachineNode smallCogwheel = transmission(builder, plan, BasinMixerRole.SMALL_COGWHEEL);
        MachineNode largeOutput = transmission(builder, plan, BasinMixerRole.LARGE_COGWHEEL_OUTPUT);
        MachineNode mixer = builder.node(
                MIXER_NODE_ID,
                "mechanical_mixer",
                plan.placement(BasinMixerRole.MECHANICAL_MIXER),
                Set.of(id("create:basin_mixing")),
                Map.of(
                        "recipe", plan.process().recipeId().toString(),
                        "counted_ingredients", "true",
                        "fluid_recipe", plan.process().fluidInputs().isEmpty()
                                ? "none" : "exact_declared"));
        MachineNode basin = builder.node(
                BASIN_NODE_ID,
                "basin",
                plan.placement(BasinMixerRole.BASIN),
                plan.process().fluidInputs().isEmpty()
                        ? Set.of(id("create:basin_item_inventory"))
                        : Set.of(id("create:basin_item_inventory"),
                                id("create:basin_fluid_inventory")),
                Map.of("fluids", plan.process().fluidInputs().isEmpty()
                        ? "none" : "exact_declared"));
        MachineNode heat = builder.node(
                HEAT_NODE_ID,
                "heat_source",
                plan.placement(BasinMixerRole.HEAT_SOURCE),
                Set.of(id("create:basin_heat_source")),
                Map.of("heat", plan.process().heatMode().name().toLowerCase()));
        MachineNode chest = builder.node(
                CHEST_NODE_ID,
                "output_chest",
                plan.placement(BasinMixerRole.OUTPUT_CHEST),
                Set.of(id("steve_industrial:item_storage")),
                Map.of());
        MachineNode input = builder.boundary(
                id("steve_industrial:c08/node/item_input"),
                id("steve_industrial:item_source"),
                new BlockPos3i(2, 4, 2));
        MachineNode fluidInput = plan.process().fluidInputs().isEmpty()
                ? null
                : builder.boundary(
                        id("steve_industrial:c08/node/fluid_input"),
                        id("steve_industrial:fluid_source"),
                        new BlockPos3i(3, 5, 1));

        for (BasinMixerRole role : List.of(
                BasinMixerRole.FLOW_CATCH_FLOOR,
                BasinMixerRole.FLOW_CATCH_WEST_WALL,
                BasinMixerRole.FLOW_CATCH_EAST_WALL,
                BasinMixerRole.FLOW_CATCH_NORTH_WALL,
                BasinMixerRole.FLOW_CATCH_SOUTH_WALL,
                BasinMixerRole.FLOW_CHAMBER_WEST_WALL,
                BasinMixerRole.FLOW_CHAMBER_EAST_WALL,
                BasinMixerRole.FLOW_CHAMBER_NORTH_WALL,
                BasinMixerRole.FLOW_CHAMBER_SOUTH_WALL,
                BasinMixerRole.FLOW_CHANNEL_WEST_WALL,
                BasinMixerRole.FLOW_CHANNEL_EAST_WALL,
                BasinMixerRole.FLOW_CHANNEL_NORTH_WALL,
                BasinMixerRole.MIXER_PLATFORM)) {
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            builder.node(id("steve_industrial:c08/node/" + name), name,
                    plan.placement(role), Set.of(id("steve_industrial:fluid_containment")),
                    Map.of("owned", "true"));
        }
        builder.node(id("steve_industrial:c08/node/water_source"), "water_source",
                plan.placement(BasinMixerRole.WATER_SOURCE),
                Set.of(id("steve_industrial:kinetic_source_fluid")), Map.of());

        MachinePort wheelOut = builder.port(
                "water_wheel/power_out", wheel,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort bottomIn = builder.port(
                "bottom_gearbox/power_in", bottomGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort bottomOut = builder.port(
                "bottom_gearbox/power_out", bottomGearbox,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort shaftIn = builder.port("vertical_shaft/power_in", verticalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort shaftOut = builder.port("vertical_shaft/power_out", verticalShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort largeInputIn = builder.port("large_input/power_in", largeInput,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort largeInputOut = builder.port("large_input/power_out", largeInput,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort smallIn = builder.port("small_cogwheel/power_in", smallCogwheel,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort smallOut = builder.port("small_cogwheel/power_out", smallCogwheel,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort largeOutputIn = builder.port("large_output/power_in", largeOutput,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort largeOutputOut = builder.port("large_output/power_out", largeOutput,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort mixerIn = builder.port(
                "mixer/power_in", mixer,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort sourceOut = builder.port(
                "source/items_out", input,
                GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort basinIn = builder.port(
                "basin/items_in", basin,
                GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort fluidSourceOut = fluidInput == null ? null : builder.port(
                "source/fluids_out", fluidInput,
                GenericResourceType.FLUID, PortMode.OUTPUT);
        MachinePort basinFluidIn = fluidInput == null ? null : builder.port(
                "basin/fluids_in", basin,
                GenericResourceType.FLUID, PortMode.INPUT);
        MachinePort basinOut = builder.port(
                "basin/items_out", basin,
                GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort chestIn = builder.port(
                "chest/items_in", chest,
                GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort heatOut = builder.port(
                "heat/heat_out", heat,
                GenericResourceType.HEAT, PortMode.OUTPUT);
        MachinePort basinHeat = builder.port(
                "basin/heat_in", basin,
                GenericResourceType.HEAT, PortMode.INPUT);
        builder.edge("power/wheel_to_bottom_gearbox", wheelOut, bottomIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/bottom_gearbox_to_vertical_shaft", bottomOut, shaftIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/vertical_shaft_to_large_input", shaftOut, largeInputIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/large_input_to_small_cogwheel", largeInputOut, smallIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/small_cogwheel_to_large_output", smallOut, largeOutputIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/large_output_to_mixer", largeOutputOut, mixerIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("items/source_to_basin", sourceOut, basinIn,
                GenericResourceType.ITEM);
        if (fluidInput != null) {
            builder.edge("fluids/source_to_basin", fluidSourceOut, basinFluidIn,
                    GenericResourceType.FLUID);
        }
        builder.edge("items/basin_to_chest", basinOut, chestIn,
                GenericResourceType.ITEM);
        builder.edge("heat/source_to_basin", heatOut, basinHeat,
                GenericResourceType.HEAT);
        return builder.build();
    }

    private static MachineNode transmission(
            Builder builder, BasinMixerPlan plan, BasinMixerRole role) {
        String name = role.name().toLowerCase(java.util.Locale.ROOT);
        return builder.node(id("steve_industrial:c08/node/" + name), name,
                plan.placement(role), Set.of(id("steve_industrial:kinetic_transmission")),
                Map.of());
    }

    private static List<GenericExecutionStep> steps(BasinMixerPlan plan) {
        return List.of(
                step(BUILD_STEP_ID, GenericExecutionPhase.BUILD,
                        BUILD_OPERATION_ID, buildTimeout(plan),
                        Set.of(BUILD_EVIDENCE)),
                step(POWER_STEP_ID, GenericExecutionPhase.POWER,
                        POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()),
                        Set.of(POWER_EVIDENCE)),
                step(FEED_STEP_ID, GenericExecutionPhase.FEED_INPUT,
                        FEED_OPERATION_ID, 20, Set.of(FEED_EVIDENCE)),
                step(PROCESS_STEP_ID, GenericExecutionPhase.PROCESS,
                        PROCESS_OPERATION_ID,
                        afterBudget(plan.process().processingTimeoutTicks()),
                        plan.process().genericSpec()
                                .requiredCompletionEvidence()));
    }

    private static GenericExecutionStep step(
            ResourceId stepId,
            GenericExecutionPhase phase,
            ResourceId operation,
            int timeout,
            Set<ResourceId> evidence) {
        String segment = stepId.path()
                .substring(stepId.path().lastIndexOf('/') + 1);
        return new BoundedExecutionStep(
                stepId,
                phase,
                List.of(condition(
                        segment, "precondition",
                        PREFLIGHT_LOADED_CONDITION)),
                new StepActionDescriptor(
                        ACTION_HANDLER_ID, operation, Map.of()),
                List.of(condition(
                        segment, "success", PREFLIGHT_LOADED_CONDITION)),
                List.of(condition(
                        segment, "failure", PREFLIGHT_UNLOADED_CONDITION)),
                timeout,
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                evidence);
    }

    private static StepCondition condition(
            String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c08/condition/"
                        + step + "/" + role),
                CONDITION_EVALUATOR_ID,
                type,
                Map.of());
    }

    private static GenericVerificationRule rule(
            ResourceId requirement,
            VerificationEvidenceKind kind,
            ResourceId target,
            int timeout) {
        return new GenericVerificationRule(
                List.of(exact(requirement, kind, target)),
                List.of(),
                timeout,
                false);
    }

    private static EvidenceRequirement exact(
            ResourceId requirement,
            VerificationEvidenceKind kind,
            ResourceId target) {
        return EvidenceRequirement.fromSourceToTarget(
                requirement, kind, ACTION_HANDLER_ID, target);
    }

    private static int buildTimeout(BasinMixerPlan plan) {
        return Math.min(BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(Math.multiplyExact(plan.placements().size(), 32), 20));
    }

    private static int afterBudget(int value) {
        return value == BoundedExecutionStep.MAX_TIMEOUT_TICKS
                ? value : value + 1;
    }

    private static MachineOrientation orientation(
            PlanBlockAxis axis, PlanBlockFacing facing) {
        if (axis == PlanBlockAxis.NONE
                && facing == PlanBlockFacing.NONE) {
            return MachineOrientation.NONE;
        }
        if (axis == PlanBlockAxis.NONE) {
            return MachineOrientation.ofFacing(
                    Direction6.valueOf(facing.name()));
        }
        if (facing == PlanBlockFacing.NONE) {
            return MachineOrientation.ofAxis(
                    MachineAxis.valueOf(axis.name()));
        }
        return MachineOrientation.of(
                Direction6.valueOf(facing.name()),
                MachineAxis.valueOf(axis.name()));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static final class Builder {
        private final BlockPos3i origin;
        private final List<MachineNode> nodes = new ArrayList<>();
        private final List<MachinePort> ports = new ArrayList<>();
        private final List<MachineEdge> edges = new ArrayList<>();

        private Builder(BlockPos3i origin) {
            this.origin = origin;
        }

        private MachineNode node(
                ResourceId nodeId,
                String role,
                BasinMixerPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode value = new MachineNode(
                    nodeId,
                    BasinMixerGenericExecutionPlan.id(
                            "steve_industrial:c08/role/" + role),
                    placement.blockId(),
                    relative(placement.position()),
                    orientation(
                            placement.rotationAxis(),
                            placement.facing()),
                    capabilities,
                    configuration);
            nodes.add(value);
            return value;
        }

        private MachineNode boundary(
                ResourceId nodeId,
                ResourceId implementation,
                BlockPos3i relative) {
            MachineNode value = new MachineNode(
                    nodeId,
                    BasinMixerGenericExecutionPlan.id(
                            "steve_industrial:c08/role/item_input"),
                    implementation,
                    relative,
                    MachineOrientation.NONE,
                    Set.of(implementation),
                    Map.of("boundary", "true"));
            nodes.add(value);
            return value;
        }

        private MachinePort port(
                String name,
                MachineNode owner,
                GenericResourceType type,
                PortMode mode) {
            MachinePort value = new MachinePort(
                    id("steve_industrial:c08/port/" + name),
                    owner.id(),
                    type,
                    mode,
                    Optional.empty(),
                    OptionalLong.empty(),
                    Map.of());
            ports.add(value);
            return value;
        }

        private void edge(
                String name,
                MachinePort source,
                MachinePort target,
                GenericResourceType type) {
            edges.add(new MachineEdge(
                    id("steve_industrial:c08/edge/" + name),
                    source.id(),
                    target.id(),
                    type,
                    EdgeMode.DIRECTED,
                    OptionalLong.empty(),
                    Map.of()));
        }

        private BlockPos3i relative(BlockPos3i position) {
            return new BlockPos3i(
                    Math.subtractExact(position.x(), origin.x()),
                    Math.subtractExact(position.y(), origin.y()),
                    Math.subtractExact(position.z(), origin.z()));
        }

        private UnifiedMachineGraph build() {
            return new UnifiedMachineGraph(
                    GRAPH_ID, nodes, ports, edges);
        }
    }
}
