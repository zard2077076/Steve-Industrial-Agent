package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.BoundedExecutionStep;
import dev.stevecreate.agent.core.execution.ConditionEvaluation;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.execution.StepConditionEvaluator;
import dev.stevecreate.agent.core.graph.EdgeMode;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceRequirement;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-source-only adapter proving that the public contracts are not shaped around one real mod. */
final class TestOnlyVirtualIndustrialAdapter implements IndustrialModAdapter {
    static final ResourceId ADAPTER_ID = id("virtual_industry:test_adapter");
    static final ResourceId GRAPH_ID = id("virtual_industry:graph/pulse_line");
    static final ResourceId PLAN_ID = id("virtual_industry:plan/pulse_line");
    static final ResourceId PROCESSOR_NODE_ID = id("virtual_industry:node/pulse_processor");
    static final ResourceId SOURCE_OUT_PORT_ID = id("virtual_industry:port/source_output");
    static final ResourceId PROCESSOR_IN_PORT_ID = id("virtual_industry:port/processor_input");
    static final ResourceId PROCESSOR_OUT_PORT_ID = id("virtual_industry:port/processor_output");
    static final ResourceId SINK_IN_PORT_ID = id("virtual_industry:port/sink_input");
    static final ResourceId PROCESSING_CAPABILITY = id("virtual_industry:capability/pulse_processing");
    static final ResourceId STEP_ID = id("virtual_industry:step/process");
    static final ResourceId ACTION_HANDLER_ID = id("virtual_industry:handler/process");
    static final ResourceId CONDITION_EVALUATOR_ID = id("virtual_industry:evaluator/process");
    static final ResourceId SIMULATED_JAM = id("virtual_industry:failure/simulated_jam");

    private static final ResourceId SOURCE_NODE_ID = id("virtual_industry:node/input_buffer");
    private static final ResourceId SINK_NODE_ID = id("virtual_industry:node/output_buffer");
    private static final ResourceId SUCCESS_CONDITION_TYPE = id("virtual_industry:condition/output_ready");
    private static final ResourceId FAILURE_CONDITION_TYPE = id("virtual_industry:condition/jammed");
    private static final ResourceId INPUT_EVIDENCE_ID = id("virtual_industry:evidence/input_consumed");
    private static final ResourceId PROCESS_EVIDENCE_ID = id("virtual_industry:evidence/process_completed");
    private static final ResourceId OUTPUT_EVIDENCE_ID = id("virtual_industry:evidence/output_produced");
    private static final ResourceId ADAPTER_EVIDENCE_ID = id("virtual_industry:evidence/pulse_signature");
    private static final ResourceId VALUE_SCHEMA_ID = id("virtual_industry:value/text");
    private static final RuntimeFingerprint RUNTIME = new RuntimeFingerprint(
            "1.20.1",
            "test-loader",
            "1.0-test",
            Map.of("virtual_industry", "1.0-test"),
            ADAPTER_ID.toString(),
            1);

    @Override
    public ResourceId adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public String targetModId() {
        return "virtual_industry";
    }

    @Override
    public RuntimeFingerprint runtime() {
        return RUNTIME;
    }

    @Override
    public AdapterResult<WorldSnapshot> capture(ScanRequest request) {
        ObservedComponent processor = new ObservedComponent(
                id("virtual_industry:pulse_processor"),
                request.center(),
                Map.of(
                        "capability", PROCESSING_CAPABILITY.toString(),
                        "input", "virtual_industry:raw_pulse",
                        "output", "virtual_industry:refined_pulse"));
        return new AdapterResult.Success<>(new WorldSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                120L,
                RUNTIME,
                request.center(),
                request.radius(),
                List.of(processor)));
    }

    GenericExecutionPlan executionPlan() {
        UnifiedMachineGraph graph = new UnifiedMachineGraph(
                GRAPH_ID,
                List.of(
                        node(SOURCE_NODE_ID, "input_buffer", new BlockPos3i(0, 0, 0), Set.of()),
                        node(PROCESSOR_NODE_ID, "processor", new BlockPos3i(1, 0, 0),
                                Set.of(PROCESSING_CAPABILITY)),
                        node(SINK_NODE_ID, "output_buffer", new BlockPos3i(2, 0, 0), Set.of())),
                List.of(
                        port(SOURCE_OUT_PORT_ID, SOURCE_NODE_ID, PortMode.OUTPUT, Direction6.EAST),
                        port(PROCESSOR_IN_PORT_ID, PROCESSOR_NODE_ID, PortMode.INPUT, Direction6.WEST),
                        port(PROCESSOR_OUT_PORT_ID, PROCESSOR_NODE_ID, PortMode.OUTPUT, Direction6.EAST),
                        port(SINK_IN_PORT_ID, SINK_NODE_ID, PortMode.INPUT, Direction6.WEST)),
                List.of(
                        edge("input_to_processor", SOURCE_OUT_PORT_ID, PROCESSOR_IN_PORT_ID),
                        edge("processor_to_output", PROCESSOR_OUT_PORT_ID, SINK_IN_PORT_ID)));

        GenericProcessSpec process = new GenericProcessSpec(
                id("virtual_industry:recipe/refine_pulse"),
                id("virtual_industry:recipe_type/pulse_processing"),
                List.of(new ProcessResource(
                        id("virtual_industry:raw_pulse"), GenericResourceType.ITEM, 1)),
                List.of(new ProcessResource(
                        id("virtual_industry:refined_pulse"), GenericResourceType.ITEM, 1)),
                List.of(),
                Set.of(PROCESSING_CAPABILITY),
                Set.of(
                        INPUT_EVIDENCE_ID,
                        PROCESS_EVIDENCE_ID,
                        OUTPUT_EVIDENCE_ID,
                        ADAPTER_EVIDENCE_ID),
                20,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(id("virtual_industry:extension/fixture"), "test-source-only"));

        BoundedExecutionStep step = new BoundedExecutionStep(
                STEP_ID,
                GenericExecutionPhase.PROCESS,
                List.of(new StepCondition(
                        id("virtual_industry:condition/input_available"),
                        CONDITION_EVALUATOR_ID,
                        id("virtual_industry:condition/input_available"),
                        Map.of())),
                new StepActionDescriptor(
                        ACTION_HANDLER_ID,
                        id("virtual_industry:operation/refine_pulse"),
                        Map.of()),
                List.of(new StepCondition(
                        id("virtual_industry:condition/output_verified"),
                        CONDITION_EVALUATOR_ID,
                        SUCCESS_CONDITION_TYPE,
                        Map.of())),
                List.of(new StepCondition(
                        id("virtual_industry:condition/no_jam"),
                        CONDITION_EVALUATOR_ID,
                        FAILURE_CONDITION_TYPE,
                        Map.of())),
                process.maximumWaitTicks(),
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                process.requiredCompletionEvidence());
        return new GenericExecutionPlan(PLAN_ID, graph, process, List.of(step));
    }

    GenericVerificationRule verificationRule(GenericExecutionPlan plan) {
        return GenericVerificationRule.forProcess(
                plan.processSpec(), evidenceRequirements(), List.of(), true);
    }

    StepActionHandler successHandler(AtomicInteger invocations) {
        return (action, context) -> {
            invocations.incrementAndGet();
            List<VerificationEvidence> evidence = evidenceRequirements().stream()
                    .map(requirement -> new VerificationEvidence(
                            id("virtual_industry:observation/" + requirement.kind().serializedName()),
                            requirement.kind(),
                            requirement.requirementId(),
                            context.stepId(),
                            requirement.requiredSourceId().orElseThrow(),
                            requirement.requiredTargetId().orElseThrow(),
                            new EvidenceValue(VALUE_SCHEMA_ID, "verified"),
                            new EvidenceValue(VALUE_SCHEMA_ID, "verified"),
                            context.gameTick(),
                            true,
                            Optional.empty()))
                    .toList();
            return ActionHandlerResult.succeeded(evidence, List.of());
        };
    }

    StepActionHandler failureHandler(AtomicInteger invocations) {
        return (action, context) -> {
            invocations.incrementAndGet();
            return ActionHandlerResult.failed(SIMULATED_JAM, "Virtual processor reported a bounded jam");
        };
    }

    StepConditionEvaluator conditionEvaluator() {
        return (condition, context) -> condition.conditionType().equals(FAILURE_CONDITION_TYPE)
                ? ConditionEvaluation.unsatisfied()
                : ConditionEvaluation.satisfied();
    }

    private static List<EvidenceRequirement> evidenceRequirements() {
        return List.of(
                EvidenceRequirement.fromSourceToTarget(
                        INPUT_EVIDENCE_ID,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        SOURCE_OUT_PORT_ID,
                        PROCESSOR_IN_PORT_ID),
                EvidenceRequirement.fromSourceToTarget(
                        PROCESS_EVIDENCE_ID,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        PROCESSOR_NODE_ID,
                        PROCESSOR_NODE_ID),
                EvidenceRequirement.fromSourceToTarget(
                        OUTPUT_EVIDENCE_ID,
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        PROCESSOR_OUT_PORT_ID,
                        SINK_IN_PORT_ID),
                EvidenceRequirement.fromSourceToTarget(
                        ADAPTER_EVIDENCE_ID,
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        ADAPTER_ID,
                        PROCESSOR_NODE_ID));
    }

    private static MachineNode node(
            ResourceId nodeId,
            String role,
            BlockPos3i position,
            Set<ResourceId> capabilities) {
        return new MachineNode(
                nodeId,
                id("virtual_industry:role/" + role),
                id("virtual_industry:machine/" + role),
                position,
                MachineOrientation.ofFacing(Direction6.EAST),
                capabilities,
                Map.of("fixture", "virtual"));
    }

    private static MachinePort port(
            ResourceId portId,
            ResourceId nodeId,
            PortMode mode,
            Direction6 side) {
        return new MachinePort(
                portId,
                nodeId,
                GenericResourceType.ITEM,
                mode,
                Optional.of(side),
                OptionalLong.of(1),
                Map.of("resource", "pulse"));
    }

    private static MachineEdge edge(
            String path,
            ResourceId sourcePortId,
            ResourceId targetPortId) {
        return new MachineEdge(
                id("virtual_industry:edge/" + path),
                sourcePortId,
                targetPortId,
                GenericResourceType.ITEM,
                EdgeMode.DIRECTED,
                OptionalLong.of(1),
                Map.of("transport", "virtual"));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
