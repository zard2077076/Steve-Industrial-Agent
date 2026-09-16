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

/** Shared bounded-runner contract for C-10 item-only Deployer execution. */
public final class DeployerGenericExecutionPlan {
    public static final ResourceId PLAN_ID =
            id("steve_industrial:c10/deployer_plan");
    public static final ResourceId GRAPH_ID =
            id("steve_industrial:c10/deployer_graph");
    public static final ResourceId BUILD_STEP_ID =
            id("steve_industrial:c10/step/build");
    public static final ResourceId POWER_STEP_ID =
            id("steve_industrial:c10/step/power");
    public static final ResourceId FEED_STEP_ID =
            id("steve_industrial:c10/step/feed_input");
    public static final ResourceId PROCESS_STEP_ID =
            id("steve_industrial:c10/step/process");

    public static final ResourceId ACTION_HANDLER_ID =
            id("create:v606/deployer");
    public static final ResourceId BUILD_OPERATION_ID =
            id("create:v606/build_deployer");
    public static final ResourceId POWER_OPERATION_ID =
            id("create:v606/observe_deployer_power");
    public static final ResourceId FEED_OPERATION_ID =
            id("create:v606/feed_deployer");
    public static final ResourceId PROCESS_OPERATION_ID =
            id("create:v606/observe_deployer_output");
    public static final ResourceId CONDITION_EVALUATOR_ID =
            id("create:v606/c10_preflight");
    public static final ResourceId PREFLIGHT_LOADED_CONDITION =
            id("steve_industrial:condition/preflight_loaded");
    public static final ResourceId PREFLIGHT_UNLOADED_CONDITION =
            id("steve_industrial:condition/preflight_unloaded");

    public static final ResourceId BUILD_EVIDENCE =
            id("steve_industrial:c10/evidence/placements_verified");
    public static final ResourceId POWER_EVIDENCE =
            id("steve_industrial:c10/evidence/power_present");
    public static final ResourceId FEED_EVIDENCE =
            id("steve_industrial:c10/evidence/input_and_held_item_offered");
    public static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    public static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    public static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    public static final ResourceId HELD_ITEM_BEFORE_AFTER =
            id("create:evidence/deployer_held_item_before_after");
    public static final ResourceId DEPOT_ITEM_OBSERVED =
            id("create:evidence/depot_item_observed");
    public static final ResourceId DEPLOYER_CYCLE_OBSERVED =
            id("create:evidence/deployer_cycle_observed");
    public static final ResourceId CHEST_OUTPUT_OBSERVED =
            id("create:evidence/deployer_output_stored");

    public static final ResourceId DEPLOYER_NODE_ID =
            id("steve_industrial:c10/node/deployer");
    public static final ResourceId DEPOT_NODE_ID =
            id("steve_industrial:c10/node/input_depot");
    public static final ResourceId CHEST_NODE_ID =
            id("steve_industrial:c10/node/output_chest");

    private static final ResourceId KINETIC_SOURCE =
            id("steve_industrial:kinetic_source");
    private static final ResourceId ITEM_PROCESSOR =
            id("steve_industrial:item_processor");
    private static final ResourceId ITEM_TRANSPORT =
            id("steve_industrial:item_transport");
    private static final ResourceId ITEM_STORAGE =
            id("steve_industrial:item_storage");

    private DeployerGenericExecutionPlan() {}

    public static GenericExecutionPlan from(DeployerPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new GenericExecutionPlan(
                PLAN_ID,
                graph(plan),
                plan.process().genericSpec(),
                steps(plan));
    }

    public static Map<ResourceId, GenericVerificationRule>
            verificationRules(DeployerPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<ResourceId, GenericVerificationRule> rules =
                new LinkedHashMap<>();
        rules.put(
                BUILD_STEP_ID,
                rule(
                        BUILD_EVIDENCE,
                        VerificationEvidenceKind.BLOCK_STATE_MATCH,
                        GRAPH_ID,
                        buildTimeout(plan)));
        rules.put(
                POWER_STEP_ID,
                rule(
                        POWER_EVIDENCE,
                        VerificationEvidenceKind.POWER_PRESENT,
                        DEPLOYER_NODE_ID,
                        plan.process().powerTimeoutTicks()));
        rules.put(
                FEED_STEP_ID,
                rule(
                        FEED_EVIDENCE,
                        VerificationEvidenceKind.PROCESS_STARTED,
                        DEPOT_NODE_ID,
                        20));
        rules.put(
                PROCESS_STEP_ID,
                GenericVerificationRule.forProcess(
                        plan.process().genericSpec(),
                        List.of(
                                exact(
                                        INPUT_CONSUMED,
                                        VerificationEvidenceKind.INPUT_CONSUMED,
                                        DEPOT_NODE_ID),
                                exact(
                                        PROCESS_COMPLETED,
                                        VerificationEvidenceKind.PROCESS_COMPLETED,
                                        DEPLOYER_NODE_ID),
                                exact(
                                        OUTPUT_PRODUCED,
                                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                                        DEPLOYER_NODE_ID),
                                exact(
                                        HELD_ITEM_BEFORE_AFTER,
                                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                        DEPLOYER_NODE_ID),
                                exact(
                                        DEPOT_ITEM_OBSERVED,
                                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                        DEPOT_NODE_ID),
                                exact(
                                        DEPLOYER_CYCLE_OBSERVED,
                                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                                        DEPLOYER_NODE_ID),
                                exact(
                                        CHEST_OUTPUT_OBSERVED,
                                        VerificationEvidenceKind.OUTPUT_STORED,
                                        CHEST_NODE_ID)),
                        List.of(),
                        true));
        return Collections.unmodifiableMap(rules);
    }

    private static UnifiedMachineGraph graph(DeployerPlan plan) {
        Builder builder = new Builder(plan.origin());
        MachineNode wheel = builder.node(
                id("steve_industrial:c10/node/water_wheel"),
                "water_wheel",
                plan.placement(DeployerRole.WATER_WHEEL),
                Set.of(KINETIC_SOURCE),
                Map.of());
        MachineNode bottomGearbox = transmission(builder, plan, DeployerRole.BOTTOM_GEARBOX);
        MachineNode verticalShaft = transmission(builder, plan, DeployerRole.VERTICAL_SHAFT);
        MachineNode topGearbox = transmission(builder, plan, DeployerRole.TOP_GEARBOX);
        MachineNode horizontalShaft = transmission(builder, plan, DeployerRole.HORIZONTAL_SHAFT);
        MachineNode deployer = builder.node(
                DEPLOYER_NODE_ID,
                "deployer",
                plan.placement(DeployerRole.DEPLOYER),
                Set.of(ITEM_PROCESSOR),
                Map.of(
                        "recipe", plan.process().recipeId().toString(),
                        "held_item", plan.process().heldItem().toString(),
                        "held_item_disposition",
                                plan.process().heldItemDisposition()
                                        .name().toLowerCase(),
                        "interaction_target", "owned_depot_item",
                        "interaction_face", "down",
                        "arbitrary_block_use", "forbidden",
                        "entity_interaction", "forbidden",
                        "player_inventory", "forbidden",
                        "private_storage", "forbidden",
                        "unknown_nbt_mutation", "forbidden"));
        MachineNode depot = builder.node(
                DEPOT_NODE_ID,
                "input_depot",
                plan.placement(DeployerRole.INPUT_DEPOT),
                Set.of(ITEM_TRANSPORT),
                Map.of("owned", "true"));
        MachineNode chest = builder.node(
                CHEST_NODE_ID,
                "output_chest",
                plan.placement(DeployerRole.OUTPUT_CHEST),
                Set.of(ITEM_STORAGE),
                Map.of());
        MachineNode processedInput = builder.boundary(
                id("steve_industrial:c10/node/processed_item_input"),
                id("steve_industrial:item_source"),
                new BlockPos3i(0, 3, -2),
                "processed_item_input");
        MachineNode heldInput = builder.boundary(
                id("steve_industrial:c10/node/held_item_input"),
                id("steve_industrial:item_source"),
                new BlockPos3i(0, 4, -2),
                "held_item_input");

        for (DeployerRole role : List.of(
                DeployerRole.FLOW_CATCH_FLOOR,
                DeployerRole.FLOW_CATCH_WEST_WALL,
                DeployerRole.FLOW_CATCH_EAST_WALL,
                DeployerRole.FLOW_CATCH_NORTH_WALL,
                DeployerRole.FLOW_CATCH_SOUTH_WALL,
                DeployerRole.FLOW_CHAMBER_WEST_WALL,
                DeployerRole.FLOW_CHAMBER_EAST_WALL,
                DeployerRole.FLOW_CHAMBER_NORTH_WALL,
                DeployerRole.FLOW_CHAMBER_SOUTH_WALL,
                DeployerRole.FLOW_CHANNEL_WEST_WALL,
                DeployerRole.FLOW_CHANNEL_EAST_WALL,
                DeployerRole.FLOW_CHANNEL_NORTH_WALL,
                DeployerRole.DEPLOYER_PLATFORM)) {
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            builder.node(id("steve_industrial:c10/node/" + name), name,
                    plan.placement(role), Set.of(id("steve_industrial:fluid_containment")),
                    Map.of("owned", "true"));
        }
        builder.node(id("steve_industrial:c10/node/water_source"), "water_source",
                plan.placement(DeployerRole.WATER_SOURCE),
                Set.of(id("steve_industrial:kinetic_source_fluid")), Map.of());

        MachinePort powerOut = builder.port(
                "water_wheel/power_out",
                wheel,
                GenericResourceType.ROTATIONAL_POWER,
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
        MachinePort powerIn = builder.port(
                "deployer/power_in",
                deployer,
                GenericResourceType.ROTATIONAL_POWER,
                PortMode.INPUT);
        MachinePort processedOut = builder.port(
                "processed_source/items_out",
                processedInput,
                GenericResourceType.ITEM,
                PortMode.OUTPUT);
        MachinePort depotIn = builder.port(
                "depot/items_in",
                depot,
                GenericResourceType.ITEM,
                PortMode.INPUT);
        MachinePort depotOut = builder.port(
                "depot/items_out",
                depot,
                GenericResourceType.ITEM,
                PortMode.OUTPUT);
        MachinePort deployerProcessedIn = builder.port(
                "deployer/processed_item_in",
                deployer,
                GenericResourceType.ITEM,
                PortMode.INPUT);
        MachinePort heldOut = builder.port(
                "held_source/items_out",
                heldInput,
                GenericResourceType.ITEM,
                PortMode.OUTPUT);
        MachinePort heldIn = builder.port(
                "deployer/held_item_in",
                deployer,
                GenericResourceType.ITEM,
                PortMode.INPUT);
        MachinePort output = builder.port(
                "deployer/items_out",
                deployer,
                GenericResourceType.ITEM,
                PortMode.OUTPUT);
        MachinePort chestIn = builder.port(
                "chest/items_in",
                chest,
                GenericResourceType.ITEM,
                PortMode.INPUT);

        builder.edge("power/wheel_to_bottom_gearbox", powerOut, bottomIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/bottom_gearbox_to_vertical_shaft", bottomOut, verticalIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/vertical_shaft_to_top_gearbox", verticalOut, topIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/top_gearbox_to_horizontal_shaft", topOut, horizontalIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge("power/horizontal_shaft_to_deployer", horizontalOut, powerIn,
                GenericResourceType.ROTATIONAL_POWER);
        builder.edge(
                "items/source_to_depot",
                processedOut,
                depotIn,
                GenericResourceType.ITEM);
        builder.edge(
                "items/depot_to_deployer",
                depotOut,
                deployerProcessedIn,
                GenericResourceType.ITEM);
        builder.edge(
                "items/held_source_to_deployer",
                heldOut,
                heldIn,
                GenericResourceType.ITEM);
        builder.edge(
                "items/deployer_to_chest",
                output,
                chestIn,
                GenericResourceType.ITEM);
        return builder.build();
    }

    private static MachineNode transmission(
            Builder builder, DeployerPlan plan, DeployerRole role) {
        String name = role.name().toLowerCase(java.util.Locale.ROOT);
        return builder.node(id("steve_industrial:c10/node/" + name), name,
                plan.placement(role), Set.of(id("steve_industrial:kinetic_transmission")),
                Map.of());
    }

    private static List<GenericExecutionStep> steps(
            DeployerPlan plan) {
        return List.of(
                step(
                        BUILD_STEP_ID,
                        GenericExecutionPhase.BUILD,
                        BUILD_OPERATION_ID,
                        buildTimeout(plan),
                        Set.of(BUILD_EVIDENCE)),
                step(
                        POWER_STEP_ID,
                        GenericExecutionPhase.POWER,
                        POWER_OPERATION_ID,
                        afterBudget(plan.process().powerTimeoutTicks()),
                        Set.of(POWER_EVIDENCE)),
                step(
                        FEED_STEP_ID,
                        GenericExecutionPhase.FEED_INPUT,
                        FEED_OPERATION_ID,
                        20,
                        Set.of(FEED_EVIDENCE)),
                step(
                        PROCESS_STEP_ID,
                        GenericExecutionPhase.PROCESS,
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
        String segment = stepId.path().substring(
                stepId.path().lastIndexOf('/') + 1);
        return new BoundedExecutionStep(
                stepId,
                phase,
                List.of(condition(
                        segment,
                        "precondition",
                        PREFLIGHT_LOADED_CONDITION)),
                new StepActionDescriptor(
                        ACTION_HANDLER_ID, operation, Map.of()),
                List.of(condition(
                        segment,
                        "success",
                        PREFLIGHT_LOADED_CONDITION)),
                List.of(condition(
                        segment,
                        "failure",
                        PREFLIGHT_UNLOADED_CONDITION)),
                timeout,
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                evidence);
    }

    private static StepCondition condition(
            String step, String role, ResourceId type) {
        return new StepCondition(
                id("steve_industrial:c10/condition/"
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

    private static int buildTimeout(DeployerPlan plan) {
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
                ResourceId id,
                String role,
                DeployerPlacement placement,
                Set<ResourceId> capabilities,
                Map<String, String> configuration) {
            MachineNode value = new MachineNode(
                    id,
                    DeployerGenericExecutionPlan.id(
                            "steve_industrial:c10/role/" + role),
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
                ResourceId id,
                ResourceId implementation,
                BlockPos3i relative,
                String role) {
            MachineNode value = new MachineNode(
                    id,
                    DeployerGenericExecutionPlan.id(
                            "steve_industrial:c10/role/" + role),
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
                    id("steve_industrial:c10/port/" + name),
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
                    id("steve_industrial:c10/edge/" + name),
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
