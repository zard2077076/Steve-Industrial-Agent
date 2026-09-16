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

/** Loader-neutral C-04 graph, step sequence and evidence rules used by the v606 action handler. */
public final class BeltPressGenericExecutionPlan {
    public static final ResourceId PLAN_ID = id("steve_industrial:c04/belt_press_plan");
    public static final ResourceId GRAPH_ID = id("steve_industrial:c04/belt_press_graph");

    public static final ResourceId BUILD_STEP_ID = id("steve_industrial:c04/step/build");
    public static final ResourceId POWER_STEP_ID = id("steve_industrial:c04/step/power");
    public static final ResourceId FEED_STEP_ID = id("steve_industrial:c04/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID = id("steve_industrial:c04/step/process");

    public static final ResourceId ACTION_HANDLER_ID = id("create:v606/belt_press");
    public static final ResourceId BUILD_OPERATION_ID = id("create:v606/build_belt_press");
    public static final ResourceId POWER_OPERATION_ID = id("create:v606/observe_belt_press_power");
    public static final ResourceId FEED_OPERATION_ID = id("create:v606/feed_belt");
    public static final ResourceId PROCESS_OPERATION_ID = id("create:v606/observe_belt_press_output");

    public static final ResourceId CONDITION_EVALUATOR_ID = id("create:v606/c04_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION = id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION = id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE_REQUIREMENT = id("steve_industrial:c04/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE_REQUIREMENT = id("steve_industrial:c04/evidence/power_present");
    public static final ResourceId FEED_EVIDENCE_REQUIREMENT = id("steve_industrial:c04/evidence/input_offered");
    public static final ResourceId INPUT_CONSUMED_REQUIREMENT = id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED_REQUIREMENT = id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED_REQUIREMENT = id("steve_industrial:evidence/output_produced");
    public static final ResourceId BELT_INPUT_REQUIREMENT = id("create:evidence/belt_input_observed");
    public static final ResourceId PRESS_CYCLE_REQUIREMENT = id("create:evidence/press_cycle_observed");
    public static final ResourceId CHEST_OUTPUT_REQUIREMENT = id("create:evidence/chest_output_observed");

    public static final ResourceId BELT_START_NODE_ID = id("steve_industrial:c04/node/belt_start");
    public static final ResourceId MECHANICAL_PRESS_NODE_ID = id("steve_industrial:c04/node/mechanical_press");
    public static final ResourceId OUTPUT_CHEST_NODE_ID = id("steve_industrial:c04/node/output_chest");

    private static final ResourceId KINETIC_SOURCE = id("steve_industrial:kinetic_source");
    private static final ResourceId KINETIC_TRANSMISSION = id("steve_industrial:kinetic_transmission");
    private static final ResourceId ITEM_PROCESSOR = id("steve_industrial:item_processor");
    private static final ResourceId ITEM_TRANSPORT = id("steve_industrial:item_transport");
    private static final ResourceId ITEM_STORAGE = id("steve_industrial:item_storage");

    private BeltPressGenericExecutionPlan() {
    }

    public static GenericExecutionPlan from(BeltPressPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID,
                graph(plan),
                plan.process().genericSpec(),
                steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule> verificationRules(BeltPressPlan plan) {
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
                MECHANICAL_PRESS_NODE_ID,
                plan.process().powerTimeoutTicks()));
        rules.put(FEED_STEP_ID, rule(
                FEED_EVIDENCE_REQUIREMENT,
                VerificationEvidenceKind.PROCESS_STARTED,
                BELT_START_NODE_ID,
                20));

        List<EvidenceRequirement> processRequirements = List.of(
                exact(INPUT_CONSUMED_REQUIREMENT, VerificationEvidenceKind.INPUT_CONSUMED, BELT_START_NODE_ID),
                exact(PROCESS_COMPLETED_REQUIREMENT, VerificationEvidenceKind.PROCESS_COMPLETED, MECHANICAL_PRESS_NODE_ID),
                exact(OUTPUT_PRODUCED_REQUIREMENT, VerificationEvidenceKind.OUTPUT_PRODUCED, MECHANICAL_PRESS_NODE_ID),
                exact(BELT_INPUT_REQUIREMENT, VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, BELT_START_NODE_ID),
                exact(PRESS_CYCLE_REQUIREMENT, VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE, MECHANICAL_PRESS_NODE_ID),
                exact(CHEST_OUTPUT_REQUIREMENT, VerificationEvidenceKind.OUTPUT_STORED, OUTPUT_CHEST_NODE_ID));
        rules.put(PROCESS_STEP_ID, GenericVerificationRule.forProcess(
                plan.process().genericSpec(),
                processRequirements,
                List.of(),
                true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(BeltPressPlan plan) {
        GraphBuilder builder = new GraphBuilder(plan.origin());
        MachineNode beltWheel = builder.node(
                id("steve_industrial:c04/node/belt_water_wheel"),
                "belt_water_wheel",
                plan.placement(BeltPressRole.BELT_WATER_WHEEL),
                Set.of(KINETIC_SOURCE),
                Map.of());
        MachineNode beltGearbox = transmission(builder, plan, BeltPressRole.BELT_GEARBOX);
        MachineNode beltDriveShaft = transmission(builder, plan, BeltPressRole.BELT_DRIVE_SHAFT);
        MachineNode pressWheel = builder.node(
                id("steve_industrial:c04/node/press_water_wheel"),
                "press_water_wheel",
                plan.placement(BeltPressRole.PRESS_WATER_WHEEL),
                Set.of(KINETIC_SOURCE),
                Map.of());
        MachineNode pressGearbox = transmission(builder, plan, BeltPressRole.PRESS_GEARBOX);
        MachineNode pressDriveShaft = transmission(builder, plan, BeltPressRole.PRESS_DRIVE_SHAFT);
        MachineNode beltStart = builder.node(
                BELT_START_NODE_ID,
                "belt_start",
                plan.placement(BeltPressRole.BELT_START),
                Set.of(KINETIC_TRANSMISSION, ITEM_TRANSPORT),
                Map.of());
        MachineNode beltPressing = builder.node(
                id("steve_industrial:c04/node/belt_pressing"),
                "belt_pressing",
                plan.placement(BeltPressRole.BELT_PRESSING),
                Set.of(KINETIC_TRANSMISSION, ITEM_TRANSPORT),
                Map.of());
        MachineNode beltEnd = builder.node(
                id("steve_industrial:c04/node/belt_end"),
                "belt_end",
                plan.placement(BeltPressRole.BELT_END),
                Set.of(KINETIC_TRANSMISSION, ITEM_TRANSPORT),
                Map.of());
        MachineNode press = builder.node(
                MECHANICAL_PRESS_NODE_ID,
                "mechanical_press",
                plan.placement(BeltPressRole.MECHANICAL_PRESS),
                Set.of(KINETIC_TRANSMISSION, ITEM_PROCESSOR),
                Map.of("recipe", plan.process().recipeId().toString(), "cycle_ticks", "240"));
        MachineNode funnel = builder.node(
                id("steve_industrial:c04/node/output_funnel"),
                "output_funnel",
                plan.placement(BeltPressRole.OUTPUT_FUNNEL),
                Set.of(ITEM_TRANSPORT),
                Map.of("mode", "insert"));
        MachineNode chest = builder.node(
                OUTPUT_CHEST_NODE_ID,
                "output_chest",
                plan.placement(BeltPressRole.OUTPUT_CHEST),
                Set.of(ITEM_STORAGE),
                Map.of());
        MachineNode input = builder.boundary(
                id("steve_industrial:c04/node/iron_ingot_input"),
                "iron_ingot_input",
                id("steve_industrial:item_source"),
                upstreamOf(beltStart.relativePosition(), plan));

        for (BeltPressRole role : BeltPressRole.values()) {
            if (role.isKinetic() || role.isBelt()
                    || role == BeltPressRole.OUTPUT_CHEST
                    || role == BeltPressRole.OUTPUT_FUNNEL) continue;
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            builder.node(id("steve_industrial:c04/node/" + name), name,
                    plan.placement(role), Set.of(id(role.name().endsWith("WATER_SOURCE")
                            ? "steve_industrial:kinetic_source_fluid"
                            : "steve_industrial:fluid_containment")), Map.of("owned", "true"));
        }

        MachinePort beltWheelOut = builder.port("belt_water_wheel/power_out", beltWheel, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort beltGearboxIn = builder.port("belt_gearbox/power_in", beltGearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort beltGearboxOut = builder.port("belt_gearbox/power_out", beltGearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort beltShaftIn = builder.port("belt_drive_shaft/power_in", beltDriveShaft, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort beltShaftOut = builder.port("belt_drive_shaft/power_out", beltDriveShaft, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort pressWheelOut = builder.port("press_water_wheel/power_out", pressWheel, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort pressGearboxIn = builder.port("press_gearbox/power_in", pressGearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort pressGearboxOut = builder.port("press_gearbox/power_out", pressGearbox, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort pressShaftIn = builder.port("press_drive_shaft/power_in", pressDriveShaft, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort pressShaftOut = builder.port("press_drive_shaft/power_out", pressDriveShaft, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort startPowerIn = builder.port("belt_start/power_in", beltStart, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort startPowerOut = builder.port("belt_start/power_out", beltStart, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort pressingPowerIn = builder.port("belt_pressing/power_in", beltPressing, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort pressingPowerOut = builder.port("belt_pressing/power_out", beltPressing, GenericResourceType.ROTATIONAL_POWER, PortMode.OUTPUT);
        MachinePort endPowerIn = builder.port("belt_end/power_in", beltEnd, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);
        MachinePort pressPowerIn = builder.port("press/power_in", press, GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT);

        MachinePort sourceItems = builder.port("input/items_out", input, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort startItemsIn = builder.port("belt_start/items_in", beltStart, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort startItemsOut = builder.port("belt_start/items_out", beltStart, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort pressingItemsIn = builder.port("belt_pressing/items_in", beltPressing, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort pressingItemsOut = builder.port("belt_pressing/items_out", beltPressing, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort pressItemsIn = builder.port("press/items_in", press, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort pressItemsOut = builder.port("press/items_out", press, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort endItemsIn = builder.port("belt_end/items_in", beltEnd, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort endItemsOut = builder.port("belt_end/items_out", beltEnd, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort funnelItemsIn = builder.port("funnel/items_in", funnel, GenericResourceType.ITEM, PortMode.INPUT);
        MachinePort funnelItemsOut = builder.port("funnel/items_out", funnel, GenericResourceType.ITEM, PortMode.OUTPUT);
        MachinePort chestItemsIn = builder.port("chest/items_in", chest, GenericResourceType.ITEM, PortMode.INPUT);

        builder.edge("power/belt_wheel_to_gearbox", beltWheelOut, beltGearboxIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/belt_gearbox_to_shaft", beltGearboxOut, beltShaftIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/belt_shaft_to_start", beltShaftOut, startPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/start_to_pressing", startPowerOut, pressingPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/pressing_to_end", pressingPowerOut, endPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/press_wheel_to_gearbox", pressWheelOut, pressGearboxIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/press_gearbox_to_shaft", pressGearboxOut, pressShaftIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/press_shaft_to_press", pressShaftOut, pressPowerIn, GenericResourceType.ROTATIONAL_POWER);
        builder.edge("items/input_to_start", sourceItems, startItemsIn, GenericResourceType.ITEM);
        builder.edge("items/start_to_pressing", startItemsOut, pressingItemsIn, GenericResourceType.ITEM);
        builder.edge("items/pressing_to_press", pressingItemsOut, pressItemsIn, GenericResourceType.ITEM);
        builder.edge("items/press_to_end", pressItemsOut, endItemsIn, GenericResourceType.ITEM);
        builder.edge("items/end_to_funnel", endItemsOut, funnelItemsIn, GenericResourceType.ITEM);
        builder.edge("items/funnel_to_chest", funnelItemsOut, chestItemsIn, GenericResourceType.ITEM);
        return builder.build();
    }

    private static MachineNode transmission(
            GraphBuilder builder, BeltPressPlan plan, BeltPressRole role) {
        String name = role.name().toLowerCase(java.util.Locale.ROOT);
        return builder.node(id("steve_industrial:c04/node/" + name), name,
                plan.placement(role), Set.of(KINETIC_TRANSMISSION), Map.of());
    }

    private static List<GenericExecutionStep> steps(BeltPressPlan plan) {
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
                id("steve_industrial:c04/condition/" + step + "/" + role),
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

    private static int buildTimeout(BeltPressPlan plan) {
        return Math.min(
                BoundedExecutionStep.MAX_TIMEOUT_TICKS,
                Math.addExact(Math.multiplyExact(plan.buildSteps().size(), 32), 20));
    }

    private static int afterBudget(int budget) {
        return budget == BoundedExecutionStep.MAX_TIMEOUT_TICKS
                ? budget
                : Math.addExact(budget, 1);
    }

    private static BlockPos3i upstreamOf(BlockPos3i beltStart, BeltPressPlan plan) {
        BlockPos3i offset = new PlanTransform(plan.anchor())
                .rotateRelative(new BlockPos3i(-1, 0, 0));
        return beltStart.translate(offset.x(), offset.y(), offset.z());
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
                BeltPressPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode node = new MachineNode(
                    nodeId,
                    id("steve_industrial:c04/role/" + role),
                    placement.blockId(),
                    relative(placement.position()),
                    orientation(placement.rotationAxis(), placement.facing()),
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
                    id("steve_industrial:c04/role/" + role),
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
                    id("steve_industrial:c04/port/" + name),
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
                    id("steve_industrial:c04/edge/" + name),
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
}
