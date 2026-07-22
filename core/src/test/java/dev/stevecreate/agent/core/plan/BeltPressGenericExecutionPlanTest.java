package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.ConditionEvaluation;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.StepRunOutcome;
import dev.stevecreate.agent.core.execution.StepRunResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.EvidenceRequirement;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BeltPressGenericExecutionPlanTest {
    @Test
    void buildsTheProductionC04GraphFromRelativePlanCoordinates() {
        BeltPressPlan physical = BeltPressPlan.at(new BlockPos3i(40, 10, -20));
        GenericExecutionPlan generic = BeltPressGenericExecutionPlan.from(physical);

        assertThat(generic.machineGraph().id())
                .isEqualTo(BeltPressGenericExecutionPlan.GRAPH_ID);
        assertThat(generic.machineGraph().nodes()).hasSize(9);
        assertThat(generic.machineGraph().ports()).hasSize(20);
        assertThat(generic.machineGraph().edges()).hasSize(10);
        assertThat(generic.machineGraph().node(
                        BeltPressGenericExecutionPlan.BELT_START_NODE_ID)
                .relativePosition()).isEqualTo(new BlockPos3i(0, 1, 0));
        assertThat(generic.machineGraph().node(
                        BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID)
                .relativePosition()).isEqualTo(new BlockPos3i(1, 3, 0));
        assertThat(generic.machineGraph().node(
                        BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID)
                .configuration())
                .containsEntry("recipe", "create:pressing/iron_ingot")
                .containsEntry("cycle_ticks", "240");
    }

    @Test
    void definesFourBoundedStepsAndPreservesEveryPhysicalProcessRequirement() {
        BeltPressPlan physical = BeltPressPlan.at(new BlockPos3i(0, 0, 0));
        GenericExecutionPlan generic = BeltPressGenericExecutionPlan.from(physical);
        Map<ResourceId, GenericVerificationRule> rules =
                BeltPressGenericExecutionPlan.verificationRules(physical);

        assertThat(generic.steps()).extracting(step -> step.phase()).containsExactly(
                GenericExecutionPhase.BUILD,
                GenericExecutionPhase.POWER,
                GenericExecutionPhase.FEED_INPUT,
                GenericExecutionPhase.PROCESS);
        assertThat(generic.steps()).extracting(step -> step.stepId()).containsExactly(
                BeltPressGenericExecutionPlan.BUILD_STEP_ID,
                BeltPressGenericExecutionPlan.POWER_STEP_ID,
                BeltPressGenericExecutionPlan.FEED_STEP_ID,
                BeltPressGenericExecutionPlan.PROCESS_STEP_ID);
        assertThat(generic.step(BeltPressGenericExecutionPlan.PROCESS_STEP_ID)
                .requiredEvidence()).containsExactlyInAnyOrderElementsOf(
                        physical.process().genericSpec().requiredCompletionEvidence());
        assertThat(rules.get(BeltPressGenericExecutionPlan.PROCESS_STEP_ID)
                .requiredEvidence()).extracting(EvidenceRequirement::requirementId)
                .containsExactlyInAnyOrderElementsOf(
                        physical.process().genericSpec().requiredCompletionEvidence());
        assertThat(rules.get(BeltPressGenericExecutionPlan.PROCESS_STEP_ID)
                .adapterSpecificEvidenceAllowed()).isTrue();
        assertThatThrownBy(rules::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sharedRunnerExecutesTheC04PlanAndEveryRegisteredRule() {
        BeltPressPlan physical = BeltPressPlan.at(new BlockPos3i(0, 0, 0));
        GenericExecutionPlan plan = BeltPressGenericExecutionPlan.from(physical);
        Map<ResourceId, GenericVerificationRule> rules =
                BeltPressGenericExecutionPlan.verificationRules(physical);
        AtomicInteger observationIds = new AtomicInteger();
        BoundedStepRunner runner = new BoundedStepRunner(
                Map.of(
                        BeltPressGenericExecutionPlan.ACTION_HANDLER_ID,
                        (action, context) -> ActionHandlerResult.succeeded(
                                evidenceFor(
                                        rules.get(context.stepId()),
                                        context.stepId(),
                                        context.gameTick(),
                                        observationIds),
                                List.of())),
                Map.of(
                        BeltPressGenericExecutionPlan.CONDITION_EVALUATOR_ID,
                        (condition, context) -> condition.conditionType().equals(
                                        BeltPressGenericExecutionPlan.PREFLIGHT_UNLOADED_CONDITION)
                                ? ConditionEvaluation.unsatisfied()
                                : ConditionEvaluation.satisfied()),
                rules);
        GenericExecutionSession session = GenericExecutionSession.start(
                id("test:c04/session"), plan, 10);
        List<StepRunResult> results = new ArrayList<>();

        for (long tick = 10; tick < 14; tick++) {
            StepRunResult result = runner.tick(session, tick);
            results.add(result);
            session = result.session();
        }

        assertThat(results).extracting(StepRunResult::outcome).containsExactly(
                StepRunOutcome.STEP_COMPLETED,
                StepRunOutcome.STEP_COMPLETED,
                StepRunOutcome.STEP_COMPLETED,
                StepRunOutcome.SESSION_COMPLETED);
        assertThat(results).extracting(StepRunResult::actionInvocations)
                .containsOnly(BoundedStepRunner.MAX_ACTION_INVOCATIONS_PER_TICK);
        assertThat(session.completedStepIds()).containsExactlyElementsOf(
                plan.steps().stream().map(step -> step.stepId()).toList());
        assertThat(session.evidence()).hasSize(9).allSatisfy(value -> {
            assertThat(value.passed()).isTrue();
            assertThat(value.observedValue().value()).isEqualTo("observed");
            assertThat(value.expectedValue().value()).isEqualTo("expected");
        });
    }

    private static List<VerificationEvidence> evidenceFor(
            GenericVerificationRule rule,
            ResourceId stepId,
            long tick,
            AtomicInteger observationIds) {
        return rule.requiredEvidence().stream()
                .map(requirement -> new VerificationEvidence(
                        id("test:c04/observation_" + observationIds.incrementAndGet()),
                        requirement.kind(),
                        requirement.requirementId(),
                        stepId,
                        requirement.requiredSourceId().orElseThrow(),
                        requirement.requiredTargetId().orElseThrow(),
                        new EvidenceValue(id("test:value/text"), "observed"),
                        new EvidenceValue(id("test:value/text"), "expected"),
                        tick,
                        true,
                        Optional.empty()))
                .toList();
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
