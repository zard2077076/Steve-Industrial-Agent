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

/** Loader-neutral C-03 graph, step sequence and evidence rules used by the v606 action handler. */
public final class WaterWheelMillstoneGenericExecutionPlan {
    public static final ResourceId PLAN_ID = id("steve_industrial:c03/water_wheel_millstone_plan");
    public static final ResourceId GRAPH_ID = id("steve_industrial:c03/water_wheel_millstone_graph");

    public static final ResourceId BUILD_STEP_ID = id("steve_industrial:c03/step/build");
    public static final ResourceId POWER_STEP_ID = id("steve_industrial:c03/step/power");
    public static final ResourceId FEED_STEP_ID = id("steve_industrial:c03/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID = id("steve_industrial:c03/step/process");

    public static final ResourceId ACTION_HANDLER_ID = id("create:v606/water_wheel_millstone");
    public static final ResourceId BUILD_OPERATION_ID = id("create:v606/build_water_wheel_millstone");
    public static final ResourceId POWER_OPERATION_ID = id("create:v606/observe_millstone_power");
    public static final ResourceId FEED_OPERATION_ID = id("create:v606/feed_millstone");
    public static final ResourceId PROCESS_OPERATION_ID = id("create:v606/observe_milling_output");

    public static final ResourceId CONDITION_EVALUATOR_ID = id("create:v606/c03_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION = id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION = id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE_REQUIREMENT = id("steve_industrial:c03/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE_REQUIREMENT = id("steve_industrial:c03/evidence/power_present");
    public static final ResourceId FEED_EVIDENCE_REQUIREMENT = id("steve_industrial:c03/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED_REQUIREMENT = id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED_REQUIREMENT = id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED_REQUIREMENT = id("steve_industrial:evidence/output_produced");
    public static final ResourceId MILLSTONE_OUTPUT_REQUIREMENT = id("create:evidence/millstone_inventory_output");

    public static final ResourceId WATER_WHEEL_NODE_ID = id("steve_industrial:c03/node/water_wheel");
    public static final ResourceId MILLSTONE_NODE_ID = id("steve_industrial:c03/node/millstone");

    private static final ResourceId KINETIC_SOURCE = id("steve_industrial:kinetic_source");
    private static final ResourceId KINETIC_TRANSMISSION = id("steve_industrial:kinetic_transmission");
    private static final ResourceId ITEM_PROCESSOR = id("steve_industrial:item_processor");

    private WaterWheelMillstoneGenericExecutionPlan() {
    }

    public static GenericExecutionPlan from(WaterWheelMillstonePlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID,
                graph(plan),
                plan.process().genericSpec(),
                steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule> verificationRules(
            WaterWheelMillstonePlan plan) {
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
                MILLSTONE_NODE_ID,
                plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE_REQUIREMENT,
                VerificationEvidenceKind.PROCESS_STARTED,
                MILLSTONE_NODE_ID,
                20));

        List<EvidenceRequirement> processRequirements = List.of(
                exact(INPUT_CONSUMED_REQUIREMENT, VerificationEvidenceKind.INPUT_CONSUMED, MILLSTONE_NODE_ID),
                exact(PROCESS_COMPLETED_REQUIREMENT, VerificationEvidenceKind.PROCESS_COMPLETED, MILLSTONE_NODE_ID),
                exact(OUTPUT_PRODUCED_REQUIREMENT, VerificationEvidenceKind.OUTPUT_PRODUCED, MILLSTONE_NODE_ID),
                exact(MILLSTONE_OUTPUT_REQUIREMENT, VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, MILLSTONE_NODE_ID));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                processRequirements,
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(WaterWheelMillstonePlan plan) {
        GraphBuilder builder = new GraphBuilder(plan.origin());
        MachineNode waterWheel = builder.node(
                WATER_WHEEL_NODE_ID,
                "water_wheel",
                plan.placement(WaterWheelMillstoneRole.WATER_WHEEL),
                Set.of(KINETIC_SOURCE),
                Map.of());
        MachineNode gearbox = builder.node(
                id("steve_industrial:c03/node/gearbox"),
                "gearbox",
                plan.placement(WaterWheelMillstoneRole.GEARBOX),
                Set.of(KINETIC_TRANSMISSION),
                Map.of());
        MachineNode shaft = builder.node(
                id("steve_industrial:c03/node/shaft"),
                "shaft",
                plan.placement(WaterWheelMillstoneRole.VERTICAL_SHAFT),
                Set.of(KINETIC_TRANSMISSION),
                Map.of());
        MachineNode millstone = builder.node(
                MILLSTONE_NODE_ID,
                "millstone",
                plan.placement(WaterWheelMillstoneRole.MILLSTONE),
                Set.of(KINETIC_TRANSMISSION, ITEM_PROCESSOR),
                Map.of("recipe", plan.process().recipeId().toString()));
        MachineNode input = builder.boundary(
                id("steve_industrial:c03/node/cobblestone_input"),
                "cobblestone_input",
                id("steve_industrial:item_source"),
                millstone.relativePosition().translate(0, 1, 0));
        MachineNode output = builder.boundary(
                id("steve_industrial:c03/node/gravel_output"),
                "gravel_output",
                id("steve_industrial:item_sink"),
                millstone.relativePosition().translate(0, -1, 0));

        MachinePort wheelPower = builder.port("water_wheel/power_out", waterWheel, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort gearboxPowerIn = builder.port("gearbox/power_in", gearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort gearboxPowerOut = builder.port("gearbox/power_out", gearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort shaftPowerIn = builder.port("shaft/power_in", shaft, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort shaftPowerOut = builder.port("shaft/power_out", shaft, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort millPower = builder.port("millstone/power_in", millstone, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort itemSource = builder.port("input/items_out", input, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort millInput = builder.port("millstone/items_in", millstone, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort millOutput = builder.port("millstone/items_out", millstone, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort itemSink = builder.port("output/items_in", output, GenericResourceType.ITEM, PortMode.INPUT);

        builder.edge("power/wheel_to_gearbox", wheelPower, gearboxPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/gearbox_to_shaft", gearboxPowerOut, shaftPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/shaft_to_millstone", shaftPowerOut, millPower, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("items/input_to_millstone", itemSource, millInput, GenericResourceType.ITEM);
        builder.edge("items/millstone_to_output", millOutput, itemSink, GenericResourceType.ITEM);
        return builder.build();
    }

    private static List<GenericExecutionStep> steps(WaterWheelMillstonePlan plan) {
        return List.of(
                step(
                        BUILD_STEP_ID,
                        GenericExecutionPhase.BUILD,
                        BUILD_OPERATION_ID,
                        buildTimeout(plan),
                        Set.of(BUILD_EVIDENCE_REQUIREMENT)),
                step(
                        POWER_STEP_ID,
                        GenericExecutionPhase.POWER,
                        POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()),
                        Set.of(POWER_EVIDENCE_REQUIREMENT)),
                step(
                        FEED_STEP_ID,
                        GenericExecutionPhase.FEED_INPUT,
                        FEED_OPERATION_ID,
                        20,
                        Set.of(FEED_EVIDENCE_REQUIREMENT)),
                step(
                        PROCESS_STEP_ID,
                        GenericExecutionPhase.PROCESS,
                        PROCESS_OPERATION_ID,
                        afterBudget(plan.process().processingTimeoutTicks()),
                        plan.process().genericSpec().requiredCompletionEvidence()));
    }

    private static GenericExecutionStep step(
            ResourceId stepId,
            GenericExecutionPhase phase,
            ResourceId operationId,
            int timeoutTicks,
            Set<ResourceId> requiredEvidence) {
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
                requiredEvidence);
    }

    private static StepCondition condition(String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c03/condition/" + step + "/" + role),
                CONDITION_EVALUATOR_ID,
                type,
                Map.of());
    }

    private static GenericVerificationRule rule(
            ResourceId requirementId,
            VerificationEvidenceKind kind,
            ResourceId targetId,
            int timeoutTicks) {
        return new GenericVerificationRule(
                List.of(exact(requirementId, kind, targetId)),
                List.of(),
                timeoutTicks,
                false);
    }

    private static EvidenceRequirement exact(
            ResourceId requirementId,
            VerificationEvidenceKind kind,
            ResourceId targetId) {
        return EvidenceRequirement.fromSourceToTarget(
                requirementId, kind, ACTION_HANDLER_ID, targetId);
    }

    private static int buildTimeout(WaterWheelMillstonePlan plan) {
        return Math.min(
                BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(plan.placements().size(), 20));
    }

    private static int afterBudget(int budget) {
        return budget == BoundedExecutionStep.MAX_TIMEOUT_TICKS
                ? budget
                : Math.addExact(budget, 1);
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
                ResourceId nodeId,
                String role,
                ResolvedPlanPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode node = new MachineNode(
                    nodeId,
                    id("steve_industrial:c03/role/" + role),
                    placement.blockId(),
                    relative(placement.position()),
                    orientation(placement.axis()),
                    capabilities,
                    configuration);
            nodes.add(node);
            return node;
        }

        private MachineNode boundary(
                ResourceId nodeId,
                String role,
                ResourceId implementationId,
                BlockPos3i relativePosition) {
            MachineNode node = new MachineNode(
                    nodeId,
                    id("steve_industrial:c03/role/" + role),
                    implementationId,
                    relativePosition,
                    MachineOrientation.NONE,
                    Set.of(implementationId),
                    Map.of("boundary", "true"));
            nodes.add(node);
            return node;
        }

        private MachinePort port(
                String name,
                MachineNode node,
                GenericResourceType resourceType,
                PortMode mode) {
            MachinePort port = new MachinePort(
                    id("steve_industrial:c03/port/" + name),
                    node.id(),
                    resourceType,
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
                GenericResourceType resourceType) {
            edges.add(new MachineEdge(
                    id("steve_industrial:c03/edge/" + name),
                    source.id(),
                    target.id(),
                    resourceType,
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
            return new UnifiedMachineGraph(GRAPH_ID, nodes, ports, edges);
        }
    }

    private static MachineOrientation orientation(PlanBlockAxis axis) {
        return axis == PlanBlockAxis.NONE
                ? MachineOrientation.NONE
                : MachineOrientation.ofAxis(MachineAxis.valueOf(axis.name()));
    }
}
