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

/** Shared bounded runner plan for all four C-06 airflow media. */
public final class FanProcessingGenericExecutionPlan {
    public static final ResourceId PLAN_ID = id("steve_industrial:c06/fan_processing_plan");
    public static final ResourceId GRAPH_ID = id("steve_industrial:c06/fan_processing_graph");
    public static final ResourceId BUILD_STEP_ID = id("steve_industrial:c06/step/build");
    public static final ResourceId POWER_STEP_ID = id("steve_industrial:c06/step/power");
    public static final ResourceId FEED_STEP_ID = id("steve_industrial:c06/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID = id("steve_industrial:c06/step/process");

    public static final ResourceId ACTION_HANDLER_ID = id("create:v606/fan_processing");
    public static final ResourceId BUILD_OPERATION_ID = id("create:v606/build_fan_processing");
    public static final ResourceId POWER_OPERATION_ID = id("create:v606/observe_fan_airflow");
    public static final ResourceId FEED_OPERATION_ID = id("create:v606/feed_fan_processing");
    public static final ResourceId PROCESS_OPERATION_ID = id("create:v606/observe_fan_output");
    public static final ResourceId CONDITION_EVALUATOR_ID = id("create:v606/c06_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION =
            id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION =
            id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE =
            id("steve_industrial:c06/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE =
            id("steve_industrial:c06/evidence/airflow_present");
    public static final ResourceId FEED_EVIDENCE =
            id("steve_industrial:c06/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    public static final ResourceId AIRFLOW_OBSERVED =
            id("create:evidence/airflow_observed");
    public static final ResourceId MEDIUM_OBSERVED =
            id("create:evidence/fan_medium_observed");
    public static final ResourceId DWELL_OBSERVED =
            id("create:evidence/fan_dwell_observed");
    public static final ResourceId CHEST_OUTPUT_OBSERVED =
            id("create:evidence/chest_output_observed");

    public static final ResourceId FAN_NODE_ID =
            id("steve_industrial:c06/node/encased_fan");
    public static final ResourceId MEDIUM_NODE_ID =
            id("steve_industrial:c06/node/processing_medium");
    public static final ResourceId MEDIUM_SUPPORT_NODE_ID =
            id("steve_industrial:c06/node/medium_support");
    public static final ResourceId INPUT_NODE_ID =
            id("steve_industrial:c06/node/item_input");
    public static final ResourceId CHEST_NODE_ID =
            id("steve_industrial:c06/node/output_chest");

    private FanProcessingGenericExecutionPlan() {}

    public static GenericExecutionPlan from(FanProcessingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID, graph(plan), plan.process().genericSpec(), steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule> verificationRules(
            FanProcessingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ResourceId, GenericVerificationRule> rules = new LinkedHashMap<>();
        rules.put(BUILD_STEP_ID, rule(
                BUILD_EVIDENCE, VerificationEvidenceKind.BLOCK_STATE_MATCH,
                GRAPH_ID, buildTimeout(plan)));
        rules.put(POWER_STEP_ID, rule(
                POWER_EVIDENCE, VerificationEvidenceKind.POWER_PRESENT,
                FAN_NODE_ID, plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE, VerificationEvidenceKind.PROCESS_STARTED,
                INPUT_NODE_ID, 20));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                List.of(
                        exact(INPUT_CONSUMED, VerificationEvidenceKind.INPUT_CONSUMED, INPUT_NODE_ID),
                        exact(PROCESS_COMPLETED, VerificationEvidenceKind.PROCESS_COMPLETED, FAN_NODE_ID),
                        exact(OUTPUT_PRODUCED, VerificationEvidenceKind.OUTPUT_PRODUCED, FAN_NODE_ID),
                        exact(AIRFLOW_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, FAN_NODE_ID),
                        exact(MEDIUM_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, MEDIUM_NODE_ID),
                        exact(DWELL_OBSERVED,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, FAN_NODE_ID),
                        exact(CHEST_OUTPUT_OBSERVED,
                                VerificationEvidenceKind.OUTPUT_STORED, CHEST_NODE_ID)),
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(FanProcessingPlan plan) {
        Builder builder = new Builder(plan.origin());
        MachineNode motor = builder.node(
                id("steve_industrial:c06/node/water_wheel"), "water_wheel",
                plan.placement(FanProcessingRole.WATER_WHEEL),
                Set.of(id("steve_industrial:kinetic_source")), Map.of());
        MachineNode bottomGearbox = transmission(builder, plan, FanProcessingRole.BOTTOM_GEARBOX);
        MachineNode verticalShaft = transmission(builder, plan, FanProcessingRole.VERTICAL_SHAFT);
        MachineNode topGearbox = transmission(builder, plan, FanProcessingRole.TOP_GEARBOX);
        MachineNode fanDriveShaft = transmission(builder, plan, FanProcessingRole.FAN_DRIVE_SHAFT);
        MachineNode fan = builder.node(
                FAN_NODE_ID, "encased_fan",
                plan.placement(FanProcessingRole.ENCASED_FAN),
                Set.of(id("create:fan_airflow")),
                Map.of("direction", "along_facing_mirrored_water_drive",
                        "airflow_facing", plan.airflowFacing().name().toLowerCase(),
                        "obstruction", "forbidden"));
        builder.node(
                MEDIUM_SUPPORT_NODE_ID, "medium_support",
                plan.placement(FanProcessingRole.MEDIUM_SUPPORT),
                Set.of(id("steve_industrial:medium_support")),
                Map.of("owned", "true"));
        for (FanProcessingRole role : List.of(
                FanProcessingRole.FLOW_CATCH_FLOOR,
                FanProcessingRole.FLOW_CATCH_WEST_WALL,
                FanProcessingRole.FLOW_CATCH_EAST_WALL,
                FanProcessingRole.FLOW_CATCH_NORTH_WALL,
                FanProcessingRole.FLOW_CATCH_SOUTH_WALL,
                FanProcessingRole.FLOW_CHAMBER_WEST_WALL,
                FanProcessingRole.FLOW_CHAMBER_EAST_WALL,
                FanProcessingRole.FLOW_CHAMBER_NORTH_WALL,
                FanProcessingRole.FLOW_CHAMBER_SOUTH_WALL,
                FanProcessingRole.FLOW_CHANNEL_WEST_WALL,
                FanProcessingRole.FLOW_CHANNEL_EAST_WALL,
                FanProcessingRole.FLOW_CHANNEL_NORTH_WALL,
                FanProcessingRole.MEDIUM_LEFT_BARRIER,
                FanProcessingRole.MEDIUM_RIGHT_BARRIER,
                FanProcessingRole.MEDIUM_STOP)) {
            builder.node(
                    id("steve_industrial:c06/node/"
                            + role.name().toLowerCase(java.util.Locale.ROOT)),
                    role.name().toLowerCase(java.util.Locale.ROOT),
                    plan.placement(role),
                    Set.of(id("steve_industrial:fluid_containment")),
                    Map.of("owned", "true"));
        }
        MachineNode medium = builder.node(
                MEDIUM_NODE_ID, "processing_medium",
                plan.placement(FanProcessingRole.PROCESSING_MEDIUM),
                Set.of(id("create:fan_processing_medium")),
                Map.of(
                        "medium", plan.process().mode().name().toLowerCase(),
                        "dangerous_to_bots",
                        Boolean.toString(plan.process().mode().dangerousToBots())));
        MachineNode input = builder.node(
                INPUT_NODE_ID, "input_depot",
                plan.placement(FanProcessingRole.INPUT_DEPOT),
                Set.of(id("steve_industrial:item_staging")),
                Map.of("processing_position", plan.inputEntityPosition().toString()));
        MachineNode chest = builder.node(
                CHEST_NODE_ID, "output_chest",
                plan.placement(FanProcessingRole.OUTPUT_CHEST),
                Set.of(id("steve_industrial:item_storage")), Map.of());
        builder.node(
                id("steve_industrial:c06/node/water_source"), "water_source",
                plan.placement(FanProcessingRole.WATER_SOURCE),
                Set.of(id("steve_industrial:kinetic_source_fluid")), Map.of());

        MachinePort motorOut = builder.port("water_wheel/power_out", motor,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
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
        MachinePort shaftIn = builder.port("fan_drive_shaft/power_in", fanDriveShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort shaftOut = builder.port("fan_drive_shaft/power_out", fanDriveShaft,
                GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort fanIn = builder.port("fan/power_in", fan,
                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort fanOut = builder.port("fan/airflow_out", fan,
                GenericResourceType.AIRFLOW, PortMode.OUTPUT);
        MachinePort mediumIn = builder.port("medium/airflow_in", medium,
                GenericResourceType.AIRFLOW, PortMode.INPUT);
        MachinePort sourceOut = builder.port("source/items_out", input,
                GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort chestIn = builder.port("chest/items_in", chest,
                GenericResourceType.ITEM, PortMode.INPUT);
        builder.edge("power/wheel_to_bottom_gearbox", motorOut, bottomIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/bottom_gearbox_to_vertical_shaft", bottomOut, verticalIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/vertical_shaft_to_top_gearbox", verticalOut, topIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/top_gearbox_to_fan_drive_shaft", topOut, shaftIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/fan_drive_shaft_to_fan", shaftOut, fanIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("airflow/fan_to_medium", fanOut, mediumIn,
                GenericResourceType.AIRFLOW);
        builder.edge("items/source_to_chest", sourceOut, chestIn,
                GenericResourceType.ITEM);
        return builder.build();
    }

    private static MachineNode transmission(
            Builder builder, FanProcessingPlan plan, FanProcessingRole role) {
        String name = role.name().toLowerCase(java.util.Locale.ROOT);
        return builder.node(
                id("steve_industrial:c06/node/" + name), name,
                plan.placement(role),
                Set.of(id("steve_industrial:kinetic_transmission")), Map.of());
    }

    private static List<GenericExecutionStep> steps(FanProcessingPlan plan) {
        return List.of(
                step(BUILD_STEP_ID, GenericExecutionPhase.BUILD, BUILD_OPERATION_ID,
                        buildTimeout(plan), Set.of(BUILD_EVIDENCE)),
                step(POWER_STEP_ID, GenericExecutionPhase.POWER, POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()), Set.of(POWER_EVIDENCE)),
                step(FEED_STEP_ID, GenericExecutionPhase.FEED_INPUT, FEED_OPERATION_ID,
                        20, Set.of(FEED_EVIDENCE)),
                step(PROCESS_STEP_ID, GenericExecutionPhase.PROCESS, PROCESS_OPERATION_ID,
                        afterBudget(plan.process().processingTimeoutTicks()),
                        plan.process().genericSpec().requiredCompletionEvidence()));
    }

    private static GenericExecutionStep step(
            ResourceId stepId,
            GenericExecutionPhase phase,
            ResourceId operation,
            int timeout,
            Set<ResourceId> evidence) {
        String segment = stepId.path().substring(stepId.path().lastIndexOf('/') + 1);
        return new BoundedExecutionStep(
                stepId, phase,
                List.of(condition(segment, "precondition", PREFLIGHT_LOADED_CONDITION)),
                new StepActionDescriptor(ACTION_HANDLER_ID, operation, Map.of()),
                List.of(condition(segment, "success", PREFLIGHT_LOADED_CONDITION)),
                List.of(condition(segment, "failure", PREFLIGHT_UNLOADED_CONDITION)),
                timeout, RetryPolicy.NO_RETRY, true, Optional.empty(), evidence);
    }

    private static StepCondition condition(String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c06/condition/" + step + "/" + role),
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

    private static int afterBudget(int value) {
        return value == BoundedExecutionStep.MAX_TIMEOUT_TICKS ? value : value + 1;
    }

    /**
     * The build action is also used by the physical Bot backend.  One invocation places one
     * block, but the Bot may need several bounded walking ticks to reach the next safe work cell
     * before the following invocation.  A placement-count-only timeout therefore expires during
     * a valid multi-block build (especially C-06's nine-position fan line).
     */
    private static int buildTimeout(FanProcessingPlan plan) {
        return Math.min(
                BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(Math.multiplyExact(plan.placements().size(), 32), 20));
    }

    private static MachineOrientation orientation(
            PlanBlockAxis axis,
            PlanBlockFacing facing) {
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
                FanProcessingPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode value = new MachineNode(
                    id,
                    FanProcessingGenericExecutionPlan.id(
                            "steve_industrial:c06/role/" + role),
                    placement.blockId(),
                    relative(placement.position()),
                    orientation(placement.rotationAxis(), placement.facing()),
                    capabilities,
                    configuration);
            nodes.add(value);
            return value;
        }

        private MachinePort port(
                String name,
                MachineNode owner,
                GenericResourceType type,
                PortMode mode) {
            MachinePort value = new MachinePort(
                    id("steve_industrial:c06/port/" + name),
                    owner.id(), type, mode, Optional.empty(), OptionalLong.empty(), Map.of());
            ports.add(value);
            return value;
        }

        private void edge(
                String name,
                MachinePort source,
                MachinePort target,
                GenericResourceType type) {
            edges.add(new MachineEdge(
                    id("steve_industrial:c06/edge/" + name),
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
