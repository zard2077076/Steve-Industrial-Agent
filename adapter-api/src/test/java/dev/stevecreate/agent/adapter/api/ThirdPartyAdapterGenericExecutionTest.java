package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.BoundedExecutionStep;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.StepRunFailureCode;
import dev.stevecreate.agent.core.execution.StepRunOutcome;
import dev.stevecreate.agent.core.execution.StepRunResult;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThirdPartyAdapterGenericExecutionTest {
    @Test
    void testOnlyAdapterCapturesAVirtualMachineAndBuildsAGenericItemGraph() {
        TestOnlyVirtualIndustrialAdapter adapter = new TestOnlyVirtualIndustrialAdapter();
        ScanRequest request = new ScanRequest(new BlockPos3i(3, 70, -4), 2);

        AdapterResult<WorldSnapshot> capture = adapter.capture(request);

        assertThat(TestOnlyVirtualIndustrialAdapter.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(TestOnlyVirtualIndustrialAdapter.class.getInterfaces())
                .containsExactly(IndustrialModAdapter.class);
        assertThat(adapter.targetModId()).isEqualTo("virtual_industry");
        assertThat(adapter.runtime().industrialModVersions())
                .containsExactly(Map.entry("virtual_industry", "1.0-test"));
        assertThat(capture).isInstanceOf(AdapterResult.Success.class);
        WorldSnapshot snapshot = (WorldSnapshot) ((AdapterResult.Success<?>) capture).value();
        assertThat(snapshot.center()).isEqualTo(request.center());
        assertThat(snapshot.radius()).isEqualTo(request.radius());
        assertThat(snapshot.components()).singleElement().satisfies(component -> {
            assertThat(component.blockId()).isEqualTo(ResourceId.parse("virtual_industry:pulse_processor"));
            assertThat(component.state())
                    .containsEntry("capability", TestOnlyVirtualIndustrialAdapter.PROCESSING_CAPABILITY.toString())
                    .containsEntry("input", "virtual_industry:raw_pulse")
                    .containsEntry("output", "virtual_industry:refined_pulse");
        });

        GenericExecutionPlan plan = adapter.executionPlan();
        assertThat(plan.machineGraph().nodes()).hasSize(3).allSatisfy((id, node) ->
                assertThat(node.modNamespace()).isEqualTo("virtual_industry"));
        assertThat(plan.machineGraph().node(TestOnlyVirtualIndustrialAdapter.PROCESSOR_NODE_ID)
                .requiredCapabilities()).containsExactly(TestOnlyVirtualIndustrialAdapter.PROCESSING_CAPABILITY);
        assertThat(plan.machineGraph().ports()).hasSize(4);
        assertThat(plan.machineGraph().port(TestOnlyVirtualIndustrialAdapter.PROCESSOR_IN_PORT_ID))
                .satisfies(port -> {
                    assertThat(port.mode()).isEqualTo(PortMode.INPUT);
                    assertThat(port.resourceType()).isEqualTo(GenericResourceType.ITEM);
                });
        assertThat(plan.machineGraph().port(TestOnlyVirtualIndustrialAdapter.PROCESSOR_OUT_PORT_ID))
                .satisfies(port -> {
                    assertThat(port.mode()).isEqualTo(PortMode.OUTPUT);
                    assertThat(port.resourceType()).isEqualTo(GenericResourceType.ITEM);
                });
        assertThat(plan.machineGraph().edges()).hasSize(2);
        assertThat(plan.processSpec().inputs()).singleElement().satisfies(input -> {
            assertThat(input.resourceId()).isEqualTo(ResourceId.parse("virtual_industry:raw_pulse"));
            assertThat(input.resourceType()).isEqualTo(GenericResourceType.ITEM);
            assertThat(input.amount()).isEqualTo(1);
        });
        assertThat(plan.processSpec().outputs()).singleElement().satisfies(output -> {
            assertThat(output.resourceId()).isEqualTo(ResourceId.parse("virtual_industry:refined_pulse"));
            assertThat(output.resourceType()).isEqualTo(GenericResourceType.ITEM);
            assertThat(output.amount()).isEqualTo(1);
        });
        assertThat(plan.processSpec().requiredMachineCapabilities())
                .containsExactly(TestOnlyVirtualIndustrialAdapter.PROCESSING_CAPABILITY);
        assertThat(plan.steps()).singleElement().isInstanceOf(BoundedExecutionStep.class);
    }

    @Test
    void sharedRunnerCompletesTheVirtualProcessWithTypedEvidence() {
        TestOnlyVirtualIndustrialAdapter adapter = new TestOnlyVirtualIndustrialAdapter();
        GenericExecutionPlan plan = adapter.executionPlan();
        GenericVerificationRule rule = adapter.verificationRule(plan);
        AtomicInteger invocations = new AtomicInteger();
        BoundedStepRunner runner = new BoundedStepRunner(
                Map.of(TestOnlyVirtualIndustrialAdapter.ACTION_HANDLER_ID,
                        adapter.successHandler(invocations)),
                Map.of(TestOnlyVirtualIndustrialAdapter.CONDITION_EVALUATOR_ID,
                        adapter.conditionEvaluator()),
                Map.of(TestOnlyVirtualIndustrialAdapter.STEP_ID, rule));
        GenericExecutionSession session = GenericExecutionSession.start(
                ResourceId.parse("virtual_industry:session/success"), plan, 40);

        StepRunResult result = runner.tick(session, 40);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.SESSION_COMPLETED);
        assertThat(result.actionInvocations()).isEqualTo(BoundedStepRunner.MAX_ACTION_INVOCATIONS_PER_TICK);
        assertThat(result.failureCode()).isEmpty();
        assertThat(result.session().status()).isEqualTo(GenericExecutionSessionStatus.COMPLETED);
        assertThat(result.session().plan()).isSameAs(plan);
        assertThat(result.session().completedStepIds())
                .containsExactly(TestOnlyVirtualIndustrialAdapter.STEP_ID);
        assertThat(result.session().evidence())
                .hasSize(4)
                .extracting(evidence -> evidence.kind())
                .containsExactlyInAnyOrder(
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE);
        assertThat(result.session().evidence()).allSatisfy(evidence -> {
            assertThat(evidence.passed()).isTrue();
            assertThat(evidence.observedValue()).isEqualTo(evidence.expectedValue());
        });
        assertThat(invocations).hasValue(1);
    }

    @Test
    void sharedRunnerFailsClosedOnTheVirtualAdaptersTypedActionFailure() {
        TestOnlyVirtualIndustrialAdapter adapter = new TestOnlyVirtualIndustrialAdapter();
        GenericExecutionPlan plan = adapter.executionPlan();
        GenericVerificationRule rule = adapter.verificationRule(plan);
        AtomicInteger invocations = new AtomicInteger();
        BoundedStepRunner runner = new BoundedStepRunner(
                Map.of(TestOnlyVirtualIndustrialAdapter.ACTION_HANDLER_ID,
                        adapter.failureHandler(invocations)),
                Map.of(TestOnlyVirtualIndustrialAdapter.CONDITION_EVALUATOR_ID,
                        adapter.conditionEvaluator()),
                Map.of(TestOnlyVirtualIndustrialAdapter.STEP_ID, rule));
        GenericExecutionSession session = GenericExecutionSession.start(
                ResourceId.parse("virtual_industry:session/failure"), plan, 80);

        StepRunResult result = runner.tick(session, 80);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.actionInvocations()).isEqualTo(BoundedStepRunner.MAX_ACTION_INVOCATIONS_PER_TICK);
        assertThat(result.failureCode()).contains(StepRunFailureCode.ACTION_FAILED);
        assertThat(result.session().status()).isEqualTo(GenericExecutionSessionStatus.FAILED);
        assertThat(result.session().plan()).isSameAs(plan);
        assertThat(result.session().evidence()).isEmpty();
        assertThat(result.session().completedStepIds()).isEmpty();
        assertThat(result.session().failure()).hasValueSatisfying(failure -> {
            assertThat(failure.failureCode()).isEqualTo(StepRunFailureCode.ACTION_FAILED.resourceId());
            assertThat(failure.sourceStepId()).isEqualTo(TestOnlyVirtualIndustrialAdapter.STEP_ID);
            assertThat(failure.detail())
                    .contains(TestOnlyVirtualIndustrialAdapter.SIMULATED_JAM.toString())
                    .contains("bounded jam");
        });
        assertThat(invocations).hasValue(1);
    }
}
