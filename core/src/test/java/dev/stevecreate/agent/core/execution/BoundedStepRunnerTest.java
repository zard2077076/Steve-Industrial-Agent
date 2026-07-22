package dev.stevecreate.agent.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.stevecreate.agent.core.verification.EvidenceRequirement;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedStepRunnerTest {
    @Test
    void completesNormallyAndCollectsPhysicalEvidenceReferencesWithinOneAction() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.succeeded(
                            List.of(evidence(context, true)),
                            List.of(new SessionWorldChangeReference(
                                    id("test:change/" + context.gameTick()),
                                    context.stepId(),
                                    context.gameTick(),
                                    true)));
                },
                normalEvaluator());

        StepRunResult result = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.SESSION_COMPLETED);
        assertThat(result.session().status()).isEqualTo(GenericExecutionSessionStatus.COMPLETED);
        assertThat(result.session().evidence()).hasSize(1);
        assertThat(result.session().worldChanges()).hasSize(1);
        assertThat(result.actionInvocations()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void waitsForRequiredEvidenceWithoutReinvokingASucceededAction() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.succeeded(List.of(), List.of());
                },
                normalEvaluator());

        StepRunResult first = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);
        StepRunResult second = runner.tick(first.session(), 101);

        assertThat(first.outcome()).isEqualTo(StepRunOutcome.WAITING);
        assertThat(first.session().stepRunState()).isEqualTo(GenericStepRunState.VERIFY);
        assertThat(first.actionInvocations()).isEqualTo(1);
        assertThat(second.outcome()).isEqualTo(StepRunOutcome.WAITING);
        assertThat(second.actionInvocations()).isZero();
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotTreatFailedEvidenceAsACompletionRequirementPass() {
        BoundedStepRunner runner = runner(
                (action, context) -> ActionHandlerResult.succeeded(
                        List.of(evidence(context, false)), List.of()),
                normalEvaluator());

        StepRunResult result = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.WAITING);
        assertThat(result.session().evidence()).singleElement().satisfies(
                item -> assertThat(item.passed()).isFalse());
        assertThat(result.session().status()).isEqualTo(GenericExecutionSessionStatus.RUNNING);
    }

    @Test
    void registeredRuleTurnsRejectedPhysicalEvidenceIntoATypedFailure() {
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(EvidenceRequirement.fromSourceToTarget(
                        id("test:evidence/output"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        id("test:fake_handler"),
                        id("test:output_node"))),
                List.of(),
                10,
                false);
        BoundedStepRunner runner = new BoundedStepRunner(
                Map.of(id("test:handler"),
                        (action, context) -> ActionHandlerResult.succeeded(
                                List.of(evidence(context, false)), List.of())),
                Map.of(id("test:evaluator"), normalEvaluator()),
                Map.of(id("test:step"), rule));

        StepRunResult result = runner.tick(
                session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.failureCode()).contains(StepRunFailureCode.VERIFICATION_FAILED);
        assertThat(result.session().failure().orElseThrow().detail())
                .contains("EVIDENCE_REJECTED");
    }

    @Test
    void registeredRuleMustExactlyMatchTheStepRequirements() {
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(EvidenceRequirement.anySourceAndTarget(
                        id("test:evidence/different"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED)),
                List.of(),
                10,
                false);
        BoundedStepRunner runner = new BoundedStepRunner(
                Map.of(id("test:handler"), successfulHandler()),
                Map.of(id("test:evaluator"), normalEvaluator()),
                Map.of(id("test:step"), rule));

        StepRunResult result = runner.tick(
                session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.failureCode()).contains(StepRunFailureCode.VERIFICATION_FAILED);
        assertThat(result.session().failure().orElseThrow().detail())
                .contains("do not match step test:step");
    }

    @Test
    void failsAnUnsatisfiedPreconditionWithoutInvokingTheAction() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.inProgress(false);
                },
                (condition, context) -> condition.conditionType().equals(id("test:precondition"))
                        ? ConditionEvaluation.unsatisfied()
                        : normalEvaluation(condition));

        StepRunResult result = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.failureCode()).contains(StepRunFailureCode.PRECONDITION_FAILED);
        assertThat(result.actionInvocations()).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    void timesOutAnActionThatNeverFinishesWithoutUnboundedSameTickWork() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.inProgress(false);
                },
                normalEvaluator());
        GenericExecutionSession session = session(step(2, RetryPolicy.NO_RETRY, true));

        StepRunResult first = runner.tick(session, 100);
        StepRunResult second = runner.tick(first.session(), 101);
        StepRunResult timedOut = runner.tick(second.session(), 102);

        assertThat(first.outcome()).isEqualTo(StepRunOutcome.ACTION_IN_PROGRESS);
        assertThat(second.outcome()).isEqualTo(StepRunOutcome.ACTION_IN_PROGRESS);
        assertThat(timedOut.outcome()).isEqualTo(StepRunOutcome.TIMED_OUT);
        assertThat(timedOut.failureCode()).contains(StepRunFailureCode.TIMEOUT);
        assertThat(timedOut.actionInvocations()).isZero();
        assertThat(calls).hasValue(2);
    }

    @Test
    void exactRecoveryRebasePreventsImmediateTimeoutAfterAWorldReloadGap() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.inProgress(false);
                },
                normalEvaluator());
        GenericExecutionSession beforeReload = runner.tick(
                session(step(2, RetryPolicy.NO_RETRY, true)), 100).session();

        StepRunResult recovered = runner.tick(
                beforeReload.rebaseRecoveryTiming(1_000), 1_000);

        assertThat(recovered.outcome()).isEqualTo(StepRunOutcome.ACTION_IN_PROGRESS);
        assertThat(recovered.failureCode()).isEmpty();
        assertThat(recovered.actionInvocations()).isEqualTo(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void handlesCancellationBeforeAnyActionInvocation() {
        AtomicInteger calls = new AtomicInteger();
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.inProgress(false);
                },
                normalEvaluator());

        StepRunResult result = runner.tick(
                session(step(20, RetryPolicy.NO_RETRY, true)),
                100,
                Optional.of(id("test:user_cancelled")));

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.CANCELLED);
        assertThat(result.session().status()).isEqualTo(GenericExecutionSessionStatus.CANCELLED);
        assertThat(result.actionInvocations()).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    void cancellationAfterOneWorldChangeStopsEveryLaterActionInvocation() {
        AtomicInteger calls = new AtomicInteger();
        StepActionHandler handler = (action, context) -> {
            calls.incrementAndGet();
            return new ActionHandlerResult(
                    ActionHandlerStatus.IN_PROGRESS,
                    true,
                    List.of(),
                    List.of(new SessionWorldChangeReference(
                            id("test:change/placed"),
                            context.stepId(),
                            context.gameTick(),
                            true)),
                    Optional.empty(),
                    Optional.empty());
        };
        BoundedStepRunner runner = runner(handler, normalEvaluator());
        GenericExecutionSession started = session(step(20, RetryPolicy.NO_RETRY, true));

        StepRunResult changed = runner.tick(started, 100);
        StepRunResult cancelled = runner.tick(
                changed.session(),
                101,
                Optional.of(id("test:user_cancelled")));

        assertThat(changed.session().worldChanges()).hasSize(1);
        assertThat(cancelled.outcome()).isEqualTo(StepRunOutcome.CANCELLED);
        assertThat(cancelled.session().status()).isEqualTo(GenericExecutionSessionStatus.CANCELLED);
        assertThat(cancelled.actionInvocations()).isZero();
        assertThat(calls).hasValue(1);
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> runner.tick(cancelled.session(), 102))
                .withMessage("Runner requires a running session");
        assertThat(calls).hasValue(1);
    }

    @Test
    void returnsTypedActionFailureForANonRetryableHandlerFailure() {
        BoundedStepRunner runner = runner(
                (action, context) -> ActionHandlerResult.failed(
                        id("test:permanent"), "Fixture action failed"),
                normalEvaluator());

        StepRunResult result = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.failureCode()).contains(StepRunFailureCode.ACTION_FAILED);
        assertThat(result.session().failure().orElseThrow().failureCode())
                .isEqualTo(StepRunFailureCode.ACTION_FAILED.resourceId());
        assertThat(result.actionInvocations()).isEqualTo(1);
    }

    @Test
    void returnsTypedVerificationFailureWhenAFailureConditionBecomesTrue() {
        BoundedStepRunner runner = runner(
                successfulHandler(),
                (condition, context) -> condition.conditionType().equals(id("test:failure"))
                        ? ConditionEvaluation.satisfied()
                        : ConditionEvaluation.satisfied());

        StepRunResult result = runner.tick(session(step(20, RetryPolicy.NO_RETRY, true)), 100);

        assertThat(result.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(result.failureCode()).contains(StepRunFailureCode.VERIFICATION_FAILED);
        assertThat(result.actionInvocations()).isEqualTo(1);
    }

    @Test
    void retriesOnlyAcrossTicksAndReturnsRetryExhaustedAtTheBound() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy retryPolicy = new RetryPolicy(
                2, 0, Set.of(id("test:transient")));
        BoundedStepRunner runner = runner(
                (action, context) -> {
                    calls.incrementAndGet();
                    return ActionHandlerResult.failed(
                            id("test:transient"), "Transient fixture failure");
                },
                normalEvaluator());

        StepRunResult retry = runner.tick(session(step(20, retryPolicy, true)), 100);
        StepRunResult exhausted = runner.tick(retry.session(), 101);

        assertThat(retry.outcome()).isEqualTo(StepRunOutcome.RETRY_SCHEDULED);
        assertThat(retry.session().currentAttempt()).isEqualTo(2);
        assertThat(retry.actionInvocations()).isEqualTo(1);
        assertThat(exhausted.outcome()).isEqualTo(StepRunOutcome.FAILED);
        assertThat(exhausted.failureCode()).contains(StepRunFailureCode.RETRY_EXHAUSTED);
        assertThat(exhausted.actionInvocations()).isEqualTo(1);
        assertThat(calls).hasValue(2);
    }

    private static StepActionHandler successfulHandler() {
        return (action, context) -> ActionHandlerResult.succeeded(
                List.of(evidence(context, true)),
                List.of());
    }

    private static StepConditionEvaluator normalEvaluator() {
        return (condition, context) -> normalEvaluation(condition);
    }

    private static ConditionEvaluation normalEvaluation(StepCondition condition) {
        return condition.conditionType().equals(id("test:failure"))
                ? ConditionEvaluation.unsatisfied()
                : ConditionEvaluation.satisfied();
    }

    private static BoundedStepRunner runner(
            StepActionHandler handler,
            StepConditionEvaluator evaluator) {
        return new BoundedStepRunner(
                Map.of(id("test:handler"), handler),
                Map.of(id("test:evaluator"), evaluator));
    }

    private static GenericExecutionSession session(GenericExecutionStep step) {
        return GenericExecutionSession.start(
                id("test:session"), plan(step), 100);
    }

    private static GenericExecutionPlan plan(GenericExecutionStep step) {
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
                Set.of(id("test:evidence/output")),
                100,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of());
        return new GenericExecutionPlan(id("test:plan"), graph, process, List.of(step));
    }

    private static GenericExecutionStep step(
            int timeoutTicks,
            RetryPolicy retryPolicy,
            boolean cancellable) {
        return new BoundedExecutionStep(
                id("test:step"),
                GenericExecutionPhase.PROCESS,
                List.of(condition("precondition")),
                new StepActionDescriptor(id("test:handler"), id("test:operate"), Map.of()),
                List.of(condition("success")),
                List.of(condition("failure")),
                timeoutTicks,
                retryPolicy,
                cancellable,
                Optional.empty(),
                Set.of(id("test:evidence/output")));
    }

    private static StepCondition condition(String type) {
        return new StepCondition(
                id("test:condition/" + type),
                id("test:evaluator"),
                id("test:" + type),
                Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static VerificationEvidence evidence(
            StepRunnerContext context,
            boolean passed) {
        EvidenceValue observed = new EvidenceValue(id("test:value/integer"), "1");
        return new VerificationEvidence(
                id("test:evidence/" + context.gameTick()),
                VerificationEvidenceKind.OUTPUT_PRODUCED,
                id("test:evidence/output"),
                context.stepId(),
                id("test:fake_handler"),
                id("test:output_node"),
                observed,
                observed,
                context.gameTick(),
                passed,
                Optional.empty());
    }
}
