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

/** Loader-neutral C-05 graph, bounded steps, and physical evidence rules. */
public final class CrushingWheelGenericExecutionPlan {
    public static final ResourceId PLAN_ID =
            id("steve_industrial:c05/crushing_wheel_plan");
    public static final ResourceId GRAPH_ID =
            id("steve_industrial:c05/crushing_wheel_graph");

    public static final ResourceId BUILD_STEP_ID =
            id("steve_industrial:c05/step/build");
    public static final ResourceId POWER_STEP_ID =
            id("steve_industrial:c05/step/power");
    public static final ResourceId FEED_STEP_ID =
            id("steve_industrial:c05/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID =
            id("steve_industrial:c05/step/process");

    public static final ResourceId ACTION_HANDLER_ID =
            id("create:v606/crushing_wheel_pair");
    public static final ResourceId BUILD_OPERATION_ID =
            id("create:v606/build_crushing_wheel_pair");
    public static final ResourceId POWER_OPERATION_ID =
            id("create:v606/observe_crushing_power");
    public static final ResourceId FEED_OPERATION_ID =
            id("create:v606/feed_crushing_controller");
    public static final ResourceId PROCESS_OPERATION_ID =
            id("create:v606/observe_crushing_output");

    public static final ResourceId CONDITION_EVALUATOR_ID =
            id("create:v606/c05_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION =
            id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION =
            id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE_REQUIREMENT =
            id("steve_industrial:c05/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE_REQUIREMENT =
            id("steve_industrial:c05/evidence/opposed_power_present");
    public static final ResourceId FEED_EVIDENCE_REQUIREMENT =
            id("steve_industrial:c05/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED_REQUIREMENT =
            id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED_REQUIREMENT =
            id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED_REQUIREMENT =
            id("steve_industrial:evidence/output_produced");
    public static final ResourceId CONTROLLER_VALID_REQUIREMENT =
            id("create:evidence/crushing_controller_valid");
    public static final ResourceId WHEEL_DIRECTIONS_REQUIREMENT =
            id("create:evidence/crushing_wheel_directions");
    public static final ResourceId PROBABILISTIC_OUTPUTS_REQUIREMENT =
            id("create:evidence/probabilistic_outputs_observed");
    public static final ResourceId CHEST_OUTPUT_REQUIREMENT =
            id("create:evidence/crushing_chest_output");

    public static final ResourceId INPUT_BOUNDARY_NODE_ID =
            id("steve_industrial:c05/node/input_boundary");
    public static final ResourceId CONTROLLER_NODE_ID =
            id("steve_industrial:c05/node/crushing_controller");
    public static final ResourceId OUTPUT_CHEST_NODE_ID =
            id("steve_industrial:c05/node/output_chest");

    private static final ResourceId KINETIC_SOURCE =
            id("steve_industrial:kinetic_source");
    private static final ResourceId KINETIC_PROCESSOR =
            id("create:crushing_wheel");
    private static final ResourceId ITEM_PROCESSOR =
            id("create:crushing_wheel_controller");
    private static final ResourceId ITEM_STORAGE =
            id("steve_industrial:item_storage");

    private CrushingWheelGenericExecutionPlan() {
    }

    public static GenericExecutionPlan from(CrushingWheelPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID,
                graph(plan),
                plan.process().genericSpec(),
                steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule> verificationRules(
            CrushingWheelPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ResourceId, GenericVerificationRule> rules = new LinkedHashMap<>();
        rules.put(BUILD_STEP_ID, rule(
                BUILD_EVIDENCE_REQUIREMENT,
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                GRAPH_ID,
                buildTimeout(plan)));
        rules.put(POWER_STEP_ID, rule(
                POWER_EVIDENCE_REQUIREMENT,
                VerificationEvidenceKind.POWER_PRESENT,
                CONTROLLER_NODE_ID,
                plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE_REQUIREMENT,
                VerificationEvidenceKind.PROCESS_STARTED,
                INPUT_BOUNDARY_NODE_ID,
                40));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                List.of(
                        exact(INPUT_CONSUMED_REQUIREMENT,
                                VerificationEvidenceKind.INPUT_CONSUMED,
                                INPUT_BOUNDARY_NODE_ID),
                        exact(PROCESS_COMPLETED_REQUIREMENT,
                                VerificationEvidenceKind.PROCESS_COMPLETED,
                                CONTROLLER_NODE_ID),
                        exact(OUTPUT_PRODUCED_REQUIREMENT,
                                VerificationEvidenceKind.OUTPUT_PRODUCED,
                                CONTROLLER_NODE_ID),
                        exact(CONTROLLER_VALID_REQUIREMENT,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                CONTROLLER_NODE_ID),
                        exact(WHEEL_DIRECTIONS_REQUIREMENT,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                CONTROLLER_NODE_ID),
                        exact(PROBABILISTIC_OUTPUTS_REQUIREMENT,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                CONTROLLER_NODE_ID),
                        exact(CHEST_OUTPUT_REQUIREMENT,
                                VerificationEvidenceKind.OUTPUT_STORED,
                                OUTPUT_CHEST_NODE_ID)),
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(CrushingWheelPlan plan) {
        GraphBuilder builder = new GraphBuilder(plan.origin());
        MachineNode leftDrive = builder.node(
                "left_drive", plan.placement(CrushingWheelRole.LEFT_DRIVE),
                Set.of(KINETIC_SOURCE));
        MachineNode rightDrive = builder.node(
                "right_drive", plan.placement(CrushingWheelRole.RIGHT_DRIVE),
                Set.of(KINETIC_SOURCE));
        MachineNode leftWheel = builder.node(
                "left_wheel", plan.placement(CrushingWheelRole.LEFT_WHEEL),
                Set.of(KINETIC_PROCESSOR));
        MachineNode rightWheel = builder.node(
                "right_wheel", plan.placement(CrushingWheelRole.RIGHT_WHEEL),
                Set.of(KINETIC_PROCESSOR));
        MachineNode controller = builder.boundary(
                CONTROLLER_NODE_ID, "controller", ITEM_PROCESSOR,
                relative(plan.origin(), plan.controllerPosition()));
        MachineNode input = builder.boundary(
                INPUT_BOUNDARY_NODE_ID, "input", id("steve_industrial:item_source"),
                relative(plan.origin(), plan.inputSpawnPosition()));
        MachineNode hopper = builder.node(
                "output_hopper", plan.placement(CrushingWheelRole.OUTPUT_HOPPER),
                Set.of(ITEM_STORAGE));
        MachineNode chest = builder.node(
                "output_chest", plan.placement(CrushingWheelRole.OUTPUT_CHEST),
                Set.of(ITEM_STORAGE));
        for (CrushingWheelRole role : CrushingWheelRole.values()) {
            if (role.isKinetic()
                    || role == CrushingWheelRole.OUTPUT_CHEST
                    || role == CrushingWheelRole.OUTPUT_HOPPER) {
                continue;
            }
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            builder.node(name, plan.placement(role), Set.of(
                    role == CrushingWheelRole.LEFT_WATER_SOURCE
                                    || role == CrushingWheelRole.RIGHT_WATER_SOURCE
                            ? id("steve_industrial:kinetic_source_fluid")
                            : id("steve_industrial:fluid_containment")));
        }

        builder.edge("power/left",
                builder.port("left_drive_power_out", leftDrive,
                        GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT),
                builder.port("left_wheel_power_in", leftWheel,
                        GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT),
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/right",
                builder.port("right_drive_power_out", rightDrive,
                        GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT),
                builder.port("right_wheel_power_in", rightWheel,
                        GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT),
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("items/input_controller",
                builder.port("input_items_out", input,
                        GenericResourceType.ITEM, PortMode.OUTPUT),
                builder.port("controller_items_in", controller,
                        GenericResourceType.ITEM, PortMode.INPUT),
                GenericResourceType.ITEM);
        builder.edge("items/controller_hopper",
                builder.port("controller_items_out", controller,
                        GenericResourceType.ITEM, PortMode.OUTPUT),
                builder.port("hopper_items_in", hopper,
                        GenericResourceType.ITEM, PortMode.INPUT),
                GenericResourceType.ITEM);
        builder.edge("items/hopper_chest",
                builder.port("hopper_items_out", hopper,
                        GenericResourceType.ITEM, PortMode.OUTPUT),
                builder.port("chest_items_in", chest,
                        GenericResourceType.ITEM, PortMode.INPUT),
                GenericResourceType.ITEM);
        return builder.build();
    }

    private static List<GenericExecutionStep> steps(CrushingWheelPlan plan) {
        return List.of(
                step(BUILD_STEP_ID, GenericExecutionPhase.BUILD, BUILD_OPERATION_ID,
                        buildTimeout(plan), Set.of(BUILD_EVIDENCE_REQUIREMENT)),
                step(POWER_STEP_ID, GenericExecutionPhase.POWER, POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()),
                        Set.of(POWER_EVIDENCE_REQUIREMENT)),
                step(FEED_STEP_ID, GenericExecutionPhase.FEED_INPUT, FEED_OPERATION_ID,
                        40, Set.of(FEED_EVIDENCE_REQUIREMENT)),
                step(PROCESS_STEP_ID, GenericExecutionPhase.PROCESS, PROCESS_OPERATION_ID,
                        afterBudget(plan.process().processingTimeoutTicks()),
                        plan.process().genericSpec().requiredCompletionEvidence()));
    }

    private static GenericExecutionStep step(
            ResourceId stepId,
            GenericExecutionPhase phase,
            ResourceId operationId,
            int timeoutTicks,
            Set<ResourceId> evidence) {
        String segment = stepId.path().substring(stepId.path().lastIndexOf('/') + 1);
        return new BoundedExecutionStep(
                stepId,
                phase,
                List.of(condition(segment, "precondition", PREFLIGHT_LOADED_CONDITION)),
                new StepActionDescriptor(ACTION_HANDLER_ID, operationId, Map.of()),
                List.of(condition(segment, "success", PREFLIGHT_LOADED_CONDITION)),
                List.of(condition(segment, "failure", PREFLIGHT_UNLOADED_CONDITION)),
                timeoutTicks,
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                evidence);
    }

    private static StepCondition condition(String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c05/condition/" + step + "/" + role),
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
                List.of(exact(requirement, kind, target)), List.of(), timeout, false);
    }

    private static EvidenceRequirement exact(
            ResourceId requirement,
            VerificationEvidenceKind kind,
            ResourceId target) {
        return EvidenceRequirement.fromSourceToTarget(
                requirement, kind, ACTION_HANDLER_ID, target);
    }

    private static int buildTimeout(CrushingWheelPlan plan) {
        return Math.min(
                BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(plan.placements().size(), 40));
    }

    private static int afterBudget(int budget) {
        return budget == BoundedExecutionStep.MAX_TIMEOUT_TICKS
                ? budget
                : Math.addExact(budget, 1);
    }

    private static BlockPos3i relative(BlockPos3i origin, BlockPos3i position) {
        return new BlockPos3i(
                Math.subtractExact(position.x(), origin.x()),
                Math.subtractExact(position.y(), origin.y()),
                Math.subtractExact(position.z(), origin.z()));
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
                Direction6.valueOf(facing.name()),
                MachineAxis.valueOf(axis.name()));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static final class GraphBuilder {
        private final BlockPos3i origin;
        private final List<MachineNode> nodes = new ArrayList<>();
        private final List<MachinePort> ports = new ArrayList<>();
        private final List<MachineEdge> edges = new ArrayList<>();

        private GraphBuilder(BlockPos3i origin) {
            this.origin = origin;
        }

        private MachineNode node(
                String role,
                CrushingWheelPlacement placement,
                Set<ResourceId> capabilities) {
            MachineNode node = new MachineNode(
                    id("steve_industrial:c05/node/" + role),
                    id("steve_industrial:c05/role/" + role),
                    placement.blockId(),
                    relative(origin, placement.position()),
                    orientation(placement.rotationAxis(), placement.facing()),
                    capabilities,
                    Map.of());
            nodes.add(node);
            return node;
        }

        private MachineNode boundary(
                ResourceId nodeId,
                String role,
                ResourceId implementation,
                BlockPos3i relativePosition) {
            MachineNode node = new MachineNode(
                    nodeId,
                    id("steve_industrial:c05/role/" + role),
                    implementation,
                    relativePosition,
                    MachineOrientation.NONE,
                    Set.of(implementation),
                    Map.of("runtime_managed", "true"));
            nodes.add(node);
            return node;
        }

        private MachinePort port(
                String name,
                MachineNode node,
                GenericResourceType type,
                PortMode mode) {
            MachinePort port = new MachinePort(
                    id("steve_industrial:c05/port/" + name),
                    node.id(),
                    type,
                    mode,
                    Optional.empty(),
                    OptionalLong.empty(),
                    Map.of());
            ports.add(port);
            return port;
        }

        private void edge(
                String name,
                MachinePort source,
                MachinePort target,
                GenericResourceType type) {
            edges.add(new MachineEdge(
                    id("steve_industrial:c05/edge/" + name),
                    source.id(),
                    target.id(),
                    type,
                    EdgeMode.DIRECTED,
                    OptionalLong.empty(),
                    Map.of()));
        }

        private UnifiedMachineGraph build() {
            return new UnifiedMachineGraph(GRAPH_ID, nodes, ports, edges);
        }
    }
}
