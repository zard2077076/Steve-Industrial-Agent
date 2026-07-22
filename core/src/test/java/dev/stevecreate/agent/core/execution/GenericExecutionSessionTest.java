package dev.stevecreate.agent.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GenericExecutionSessionTest {
    @Test
    void executionPlanDefensivelyCopiesStepsAndRejectsDuplicateIds() {
        GenericExecutionStep first = step("prepare", GenericExecutionPhase.PREPARE, true, true);
        GenericExecutionStep second = step("verify", GenericExecutionPhase.VERIFY, false, false);
        List<GenericExecutionStep> steps = new ArrayList<>(List.of(first, second));

        GenericExecutionPlan plan = plan(steps);
        steps.clear();

        assertThat(plan.steps()).containsExactly(first, second);
        assertThat(plan.step(first.stepId())).isSameAs(first);
        assertThatThrownBy(() -> plan.steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> plan(List.of(first, first)))
                .withMessage("Duplicate execution step id: test:step/prepare");
    }

    @Test
    void startsWithTheFirstStepPhaseAndMonotonicTicks() {
        GenericExecutionSession session = GenericExecutionSession.start(
                id("test:session"), plan(), 100);

        assertThat(session.status()).isEqualTo(GenericExecutionSessionStatus.RUNNING);
        assertThat(session.currentStep().orElseThrow().stepId())
                .isEqualTo(id("test:step/prepare"));
        assertThat(session.currentPhase()).contains(GenericExecutionPhase.PREPARE);
        assertThat(session.startedTick()).isEqualTo(100);
        assertThat(session.lastProgressTick()).isEqualTo(100);
        assertThat(session.currentAttempt()).isEqualTo(1);
        assertThat(session.completedStepIds()).isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> session.markProgress(99))
                .withMessage("Progress tick must not move backwards from 100");
    }

    @Test
    void recordsTypedEvidenceAndWorldChangeReferencesForOnlyTheCurrentStep() {
        GenericExecutionSession session = GenericExecutionSession.start(
                id("test:session"), plan(), 100);
        VerificationEvidence evidence = evidence(
                "loaded", id("test:step/prepare"), 101, true);
        SessionWorldChangeReference change = new SessionWorldChangeReference(
                id("test:change/place"), id("test:step/prepare"), 102, true);

        GenericExecutionSession recorded = session.recordEvidence(evidence).recordWorldChange(change);

        assertThat(recorded.evidence()).containsExactly(evidence);
        assertThat(recorded.worldChanges()).containsExactly(change);
        assertThat(recorded.lastProgressTick()).isEqualTo(102);
        assertThatThrownBy(() -> recorded.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> session.recordEvidence(evidence(
                        "wrong", id("test:step/verify"), 101, true)))
                .withMessage("Session evidence must belong to current step test:step/prepare");
    }

    @Test
    void exactRecoveryRebasesOnlyTheCurrentStepTimeoutWindow() {
        VerificationEvidence observed = evidence(
                "loaded", id("test:step/prepare"), 101, true);
        SessionWorldChangeReference change = new SessionWorldChangeReference(
                id("test:change/place"), id("test:step/prepare"), 102, true);
        GenericExecutionSession before = GenericExecutionSession.start(
                        id("test:session"), plan(), 100)
                .beginAction(100)
                .recordEvidence(observed)
                .recordWorldChange(change);

        GenericExecutionSession recovered = before.rebaseRecoveryTiming(1_000);

        assertThat(recovered.startedTick()).isEqualTo(100);
        assertThat(recovered.currentStepStartedTick()).isEqualTo(1_000);
        assertThat(recovered.lastProgressTick()).isEqualTo(1_000);
        assertThat(recovered.currentStepIndex()).isEqualTo(before.currentStepIndex());
        assertThat(recovered.currentAttempt()).isEqualTo(before.currentAttempt());
        assertThat(recovered.stepRunState()).isEqualTo(GenericStepRunState.ACTION);
        assertThat(recovered.completedStepIds()).isEqualTo(before.completedStepIds());
        assertThat(recovered.evidence()).containsExactly(observed);
        assertThat(recovered.worldChanges()).containsExactly(change);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> before.rebaseRecoveryTiming(101))
                .withMessage("Progress tick must not move backwards from 102");
        GenericExecutionSession terminal = before.fail(103, id("test:failure"), "terminal");
        assertThatIllegalStateException()
                .isThrownBy(() -> terminal.rebaseRecoveryTiming(1_000))
                .withMessage("Session is already terminal: FAILED");
    }

    @Test
    void completesStepsInPlanOrderAndMakesCompletionTerminal() {
        GenericExecutionSession firstCompleted = GenericExecutionSession.start(
                        id("test:session"), plan(), 100)
                .completeCurrentStep(105);

        assertThat(firstCompleted.status()).isEqualTo(GenericExecutionSessionStatus.RUNNING);
        assertThat(firstCompleted.completedStepIds()).containsExactly(id("test:step/prepare"));
        assertThat(firstCompleted.currentStep().orElseThrow().stepId())
                .isEqualTo(id("test:step/verify"));
        assertThat(firstCompleted.currentPhase()).contains(GenericExecutionPhase.VERIFY);

        GenericExecutionSession completed = firstCompleted.completeCurrentStep(110);
        assertThat(completed.status()).isEqualTo(GenericExecutionSessionStatus.COMPLETED);
        assertThat(completed.completedStepIds()).containsExactly(
                id("test:step/prepare"), id("test:step/verify"));
        assertThat(completed.currentStep()).isEmpty();
        assertThat(completed.currentPhase()).isEmpty();
        assertThat(completed.terminalTick()).hasValue(110);
        assertThatIllegalStateException()
                .isThrownBy(() -> completed.markProgress(111))
                .withMessage("Session is already terminal: COMPLETED");
    }

    @Test
    void cancellationRequiresTheCurrentStepToAllowIt() {
        GenericExecutionSession cancelled = GenericExecutionSession.start(
                        id("test:session"), plan(), 100)
                .cancel(101, id("test:user_cancelled"));

        assertThat(cancelled.status()).isEqualTo(GenericExecutionSessionStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).contains(id("test:user_cancelled"));
        assertThat(cancelled.failure()).isEmpty();
        assertThat(cancelled.terminalTick()).hasValue(101);

        GenericExecutionSession nonCancellable = GenericExecutionSession.start(
                        id("test:session_two"), plan(), 100)
                .completeCurrentStep(101);
        assertThatIllegalStateException()
                .isThrownBy(() -> nonCancellable.cancel(102, id("test:user_cancelled")))
                .withMessage("Current step does not allow cancellation");
    }

    @Test
    void timeoutAndFailureCarryTypedTerminalInformation() {
        GenericExecutionSession timedOut = GenericExecutionSession.start(
                        id("test:timeout_session"), plan(), 100)
                .timeout(120, id("test:timeout"), "No progress within the step budget");
        assertThat(timedOut.status()).isEqualTo(GenericExecutionSessionStatus.TIMED_OUT);
        assertThat(timedOut.failure().orElseThrow().failureCode()).isEqualTo(id("test:timeout"));
        assertThat(timedOut.failure().orElseThrow().sourceStepId())
                .isEqualTo(id("test:step/prepare"));

        GenericExecutionSession failed = GenericExecutionSession.start(
                        id("test:failed_session"), plan(), 100)
                .fail(105, id("test:action_failed"), "Typed action handler rejected the operation");
        assertThat(failed.status()).isEqualTo(GenericExecutionSessionStatus.FAILED);
        assertThat(failed.failure().orElseThrow().failedTick()).isEqualTo(105);
        assertThat(failed.cancellationReason()).isEmpty();
    }

    @Test
    void retryRequiresAnAllowlistedFailureAndStopsAtTheStepLimit() {
        GenericExecutionSession session = GenericExecutionSession.start(
                id("test:session"), plan(), 100);
        assertThatIllegalStateException()
                .isThrownBy(() -> session.retryCurrentStep(101, id("test:not_retryable")))
                .withMessage("Current step does not allow retry for failure: test:not_retryable");

        GenericExecutionSession retried = session.retryCurrentStep(101, id("test:dirty_state"));
        assertThat(retried.currentAttempt()).isEqualTo(2);
        assertThatIllegalStateException()
                .isThrownBy(() -> retried.retryCurrentStep(102, id("test:dirty_state")))
                .withMessage("Current step retry limit is exhausted");
    }

    private static GenericExecutionPlan plan() {
        return plan(List.of(
                step("prepare", GenericExecutionPhase.PREPARE, true, true),
                step("verify", GenericExecutionPhase.VERIFY, false, false)));
    }

    private static GenericExecutionPlan plan(List<GenericExecutionStep> steps) {
        MachineNode node = new MachineNode(
                id("test:node"),
                id("test:role"),
                id("test:machine"),
                new BlockPos3i(0, 0, 0),
                MachineOrientation.NONE,
                Set.of(id("test:capability")),
                Map.of());
        UnifiedMachineGraph graph = new UnifiedMachineGraph(
                id("test:graph"), List.of(node), List.of(), List.of());
        GenericProcessSpec process = new GenericProcessSpec(
                id("test:recipe"),
                id("test:recipe_type"),
                List.of(new ProcessResource(id("test:input"), GenericResourceType.ITEM, 1)),
                List.of(new ProcessResource(id("test:output"), GenericResourceType.ITEM, 1)),
                List.of(),
                Set.of(id("test:capability")),
                Set.of(id("test:evidence_required")),
                100,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of());
        return new GenericExecutionPlan(id("test:plan"), graph, process, steps);
    }

    private static GenericExecutionStep step(
            String name,
            GenericExecutionPhase phase,
            boolean cancellable,
            boolean retryable) {
        return new BoundedExecutionStep(
                id("test:step/" + name),
                phase,
                List.of(),
                new StepActionDescriptor(id("test:handler"), id("test:" + name), Map.of()),
                List.of(condition(name + "_done")),
                List.of(condition(name + "_failed")),
                100,
                retryable
                        ? new RetryPolicy(2, 1, Set.of(id("test:dirty_state")))
                        : RetryPolicy.NO_RETRY,
                cancellable,
                Optional.empty(),
                Set.of(id("test:evidence_required")));
    }

    private static StepCondition condition(String name) {
        return new StepCondition(
                id("test:" + name),
                id("test:evaluator"),
                id("test:condition"),
                Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static VerificationEvidence evidence(
            String name,
            ResourceId sourceStepId,
            long tick,
            boolean passed) {
        EvidenceValue observed = new EvidenceValue(id("test:value/integer"), "1");
        return new VerificationEvidence(
                id("test:evidence/" + name),
                VerificationEvidenceKind.BLOCK_PRESENT,
                id("test:evidence_required"),
                sourceStepId,
                id("test:fake_evaluator"),
                id("test:node"),
                observed,
                observed,
                tick,
                passed,
                Optional.empty());
    }
}
