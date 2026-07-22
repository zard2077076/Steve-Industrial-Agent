package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.GenericVerificationResult;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import dev.stevecreate.agent.core.verification.GenericVerificationStatus;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Create-free, non-blocking runner that invokes at most one registered action handler per tick. */
public final class BoundedStepRunner {
    public static final int MAX_ACTION_INVOCATIONS_PER_TICK = 1;
    public static final int MAX_REGISTERED_HANDLERS = 128;

    private final Map<ResourceId, StepActionHandler> actionHandlers;
    private final Map<ResourceId, StepConditionEvaluator> conditionEvaluators;
    private final Map<ResourceId, GenericVerificationRule> verificationRules;

    public BoundedStepRunner(
            Map<ResourceId, StepActionHandler> actionHandlers,
            Map<ResourceId, StepConditionEvaluator> conditionEvaluators) {
        this(actionHandlers, conditionEvaluators, Map.of());
    }

    public BoundedStepRunner(
            Map<ResourceId, StepActionHandler> actionHandlers,
            Map<ResourceId, StepConditionEvaluator> conditionEvaluators,
            Map<ResourceId, GenericVerificationRule> verificationRules) {
        this.actionHandlers = copyRegistry(actionHandlers, "actionHandlers");
        this.conditionEvaluators = copyRegistry(conditionEvaluators, "conditionEvaluators");
        this.verificationRules = copyRegistry(verificationRules, "verificationRules");
    }

    public StepRunResult tick(GenericExecutionSession session, long gameTick) {
        return tick(session, gameTick, Optional.empty());
    }

    public StepRunResult tick(
            GenericExecutionSession session,
            long gameTick,
            Optional<ResourceId> cancellationRequest) {
        Objects.requireNonNull(session, "session");
        cancellationRequest = Objects.requireNonNull(cancellationRequest, "cancellationRequest");
        if (session.status() != GenericExecutionSessionStatus.RUNNING) {
            throw new IllegalStateException("Runner requires a running session");
        }
        if (gameTick < session.lastProgressTick()) {
            throw new IllegalArgumentException(
                    "Runner tick must not move backwards from " + session.lastProgressTick());
        }

        if (cancellationRequest.isPresent()) {
            if (!session.currentStep().orElseThrow().cancellable()) {
                return result(
                        session,
                        StepRunOutcome.CANCELLATION_REJECTED,
                        0,
                        StepRunFailureCode.CANCELLATION_REJECTED);
            }
            GenericExecutionSession cancelled = session.cancel(
                    gameTick, cancellationRequest.orElseThrow());
            return result(cancelled, StepRunOutcome.CANCELLED, 0, null);
        }

        GenericExecutionStep step = session.currentStep().orElseThrow();
        if (gameTick - session.currentStepStartedTick() >= step.timeoutTicks()) {
            GenericExecutionSession timedOut = session.timeout(
                    gameTick,
                    StepRunFailureCode.TIMEOUT.resourceId(),
                    "Step " + step.stepId() + " exceeded " + step.timeoutTicks() + " ticks");
            return result(
                    timedOut, StepRunOutcome.TIMED_OUT, 0, StepRunFailureCode.TIMEOUT);
        }

        if (session.stepRunState() == GenericStepRunState.READY
                && session.currentAttempt() > 1
                && gameTick - session.lastProgressTick() < step.retryPolicy().backoffTicks()) {
            return result(session, StepRunOutcome.WAITING, 0, null);
        }

        GenericExecutionSession working = session;
        if (working.stepRunState() == GenericStepRunState.READY) {
            ConditionDecision preconditions = evaluatePreconditions(working, step, gameTick);
            if (preconditions.failed()) {
                return fail(
                        working,
                        gameTick,
                        preconditions.failureCode(),
                        preconditions.detail(),
                        0);
            }
            working = working.beginAction(gameTick);
        }

        int actionInvocations = 0;
        if (working.stepRunState() == GenericStepRunState.ACTION) {
            StepActionHandler handler = actionHandlers.get(step.action().handlerId());
            if (handler == null) {
                return fail(
                        working,
                        gameTick,
                        StepRunFailureCode.MISSING_HANDLER,
                        "No action handler registered for " + step.action().handlerId(),
                        0);
            }
            ActionHandlerResult actionResult = Objects.requireNonNull(
                    handler.invoke(step.action(), context(working, gameTick)),
                    "action handler result");
            actionInvocations = 1;
            working = applyReferences(working, actionResult);
            if (actionResult.madeProgress() && working.lastProgressTick() < gameTick) {
                working = working.markProgress(gameTick);
            }

            if (actionResult.status() == ActionHandlerStatus.FAILED) {
                ResourceId handlerFailure = actionResult.failureCode().orElseThrow();
                RetryPolicy retryPolicy = step.retryPolicy();
                if (retryPolicy.retryableFailures().contains(handlerFailure)) {
                    if (working.currentAttempt() < retryPolicy.maximumAttempts()) {
                        GenericExecutionSession retried = working.retryCurrentStep(
                                gameTick, handlerFailure);
                        return result(
                                retried,
                                StepRunOutcome.RETRY_SCHEDULED,
                                actionInvocations,
                                null);
                    }
                    return fail(
                            working,
                            gameTick,
                            StepRunFailureCode.RETRY_EXHAUSTED,
                            "Retry limit exhausted after handler failure " + handlerFailure,
                            actionInvocations);
                }
                return fail(
                        working,
                        gameTick,
                        StepRunFailureCode.ACTION_FAILED,
                        "Action handler failed with " + handlerFailure + ": "
                                + actionResult.detail().orElseThrow(),
                        actionInvocations);
            }
            if (actionResult.status() == ActionHandlerStatus.IN_PROGRESS) {
                return result(
                        working,
                        StepRunOutcome.ACTION_IN_PROGRESS,
                        actionInvocations,
                        null);
            }
            working = working.beginVerification(gameTick);
        }

        ConditionDecision verification = evaluateVerification(working, step, gameTick);
        if (verification.failed()) {
            return fail(
                    working,
                    gameTick,
                    verification.failureCode(),
                    verification.detail(),
                    actionInvocations);
        }
        if (verification.waiting()) {
            return result(working, StepRunOutcome.WAITING, actionInvocations, null);
        }
        RuleDecision ruleDecision = evaluateRule(working, step, gameTick);
        if (ruleDecision.failed()) {
            return fail(
                    working,
                    gameTick,
                    StepRunFailureCode.VERIFICATION_FAILED,
                    ruleDecision.detail(),
                    actionInvocations);
        }
        if (ruleDecision.timedOut()) {
            GenericExecutionSession timedOut = working.timeout(
                    gameTick,
                    StepRunFailureCode.TIMEOUT.resourceId(),
                    ruleDecision.detail());
            return result(
                    timedOut,
                    StepRunOutcome.TIMED_OUT,
                    actionInvocations,
                    StepRunFailureCode.TIMEOUT);
        }
        if (ruleDecision.waiting()) {
            return result(working, StepRunOutcome.WAITING, actionInvocations, null);
        }

        GenericExecutionSession completed = working.completeCurrentStep(gameTick);
        return result(
                completed,
                completed.status() == GenericExecutionSessionStatus.COMPLETED
                        ? StepRunOutcome.SESSION_COMPLETED
                        : StepRunOutcome.STEP_COMPLETED,
                actionInvocations,
                null);
    }

    private ConditionDecision evaluatePreconditions(
            GenericExecutionSession session,
            GenericExecutionStep step,
            long gameTick) {
        for (StepCondition condition : step.preconditions()) {
            EvaluatedCondition evaluated = evaluate(session, condition, gameTick);
            if (evaluated.missingEvaluator()) {
                return ConditionDecision.failed(
                        StepRunFailureCode.MISSING_EVALUATOR,
                        "No condition evaluator registered for " + condition.evaluatorId());
            }
            ConditionEvaluation value = evaluated.evaluation();
            if (value.status() != ConditionEvaluationStatus.SATISFIED) {
                String suffix = value.status() == ConditionEvaluationStatus.ERROR
                        ? ": " + value.detail().orElseThrow()
                        : "";
                return ConditionDecision.failed(
                        StepRunFailureCode.PRECONDITION_FAILED,
                        "Precondition " + condition.conditionId() + " was "
                                + value.status() + suffix);
            }
        }
        return ConditionDecision.ready();
    }

    private ConditionDecision evaluateVerification(
            GenericExecutionSession session,
            GenericExecutionStep step,
            long gameTick) {
        for (StepCondition condition : step.failureConditions()) {
            EvaluatedCondition evaluated = evaluate(session, condition, gameTick);
            if (evaluated.missingEvaluator()) {
                return ConditionDecision.failed(
                        StepRunFailureCode.MISSING_EVALUATOR,
                        "No condition evaluator registered for " + condition.evaluatorId());
            }
            ConditionEvaluation value = evaluated.evaluation();
            if (value.status() == ConditionEvaluationStatus.ERROR
                    || value.status() == ConditionEvaluationStatus.SATISFIED) {
                return ConditionDecision.failed(
                        StepRunFailureCode.VERIFICATION_FAILED,
                        "Failure condition " + condition.conditionId() + " was "
                                + value.status()
                                + value.detail().map(detail -> ": " + detail).orElse(""));
            }
        }
        for (StepCondition condition : step.successConditions()) {
            EvaluatedCondition evaluated = evaluate(session, condition, gameTick);
            if (evaluated.missingEvaluator()) {
                return ConditionDecision.failed(
                        StepRunFailureCode.MISSING_EVALUATOR,
                        "No condition evaluator registered for " + condition.evaluatorId());
            }
            ConditionEvaluation value = evaluated.evaluation();
            if (value.status() == ConditionEvaluationStatus.ERROR) {
                return ConditionDecision.failed(
                        StepRunFailureCode.VERIFICATION_FAILED,
                        "Success condition " + condition.conditionId() + " errored: "
                                + value.detail().orElseThrow());
            }
            if (value.status() == ConditionEvaluationStatus.UNSATISFIED) {
                return ConditionDecision.pending();
            }
        }
        return ConditionDecision.ready();
    }

    private EvaluatedCondition evaluate(
            GenericExecutionSession session,
            StepCondition condition,
            long gameTick) {
        StepConditionEvaluator evaluator = conditionEvaluators.get(condition.evaluatorId());
        if (evaluator == null) {
            return EvaluatedCondition.missing();
        }
        return EvaluatedCondition.value(Objects.requireNonNull(
                evaluator.evaluate(condition, context(session, gameTick)),
                "condition evaluation"));
    }

    private static GenericExecutionSession applyReferences(
            GenericExecutionSession session,
            ActionHandlerResult actionResult) {
        GenericExecutionSession working = session;
        for (VerificationEvidence reference : actionResult.evidence()) {
            working = working.recordEvidence(reference);
        }
        for (SessionWorldChangeReference reference : actionResult.worldChanges()) {
            working = working.recordWorldChange(reference);
        }
        return working;
    }

    private static boolean hasRequiredEvidence(
            GenericExecutionSession session,
            Set<ResourceId> requiredEvidence) {
        ResourceId currentStepId = session.currentStep().orElseThrow().stepId();
        Set<ResourceId> observedTypes = session.evidence().stream()
                .filter(reference -> reference.sourceStepId().equals(currentStepId))
                .filter(VerificationEvidence::passed)
                .map(VerificationEvidence::requirementId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return observedTypes.containsAll(requiredEvidence);
    }

    private RuleDecision evaluateRule(
            GenericExecutionSession session,
            GenericExecutionStep step,
            long gameTick) {
        GenericVerificationRule rule = verificationRules.get(step.stepId());
        if (rule == null) {
            return hasRequiredEvidence(session, step.requiredEvidence())
                    ? RuleDecision.passed()
                    : RuleDecision.pending();
        }
        Set<ResourceId> ruleRequired = rule.requiredEvidence().stream()
                .map(value -> value.requirementId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ruleRequired.equals(step.requiredEvidence())) {
            return RuleDecision.failed(
                    "Verification rule requirements do not match step " + step.stepId()
                            + ": step=" + step.requiredEvidence() + " rule=" + ruleRequired);
        }

        GenericVerificationResult evaluated = rule.evaluate(
                session.evidence(),
                step.stepId(),
                session.currentStepStartedTick(),
                gameTick);
        if (evaluated.status() == GenericVerificationStatus.PASSED) {
            return RuleDecision.passed();
        }
        if (evaluated.status() == GenericVerificationStatus.PENDING) {
            return RuleDecision.pending();
        }
        String detail = evaluated.failure().orElseThrow().code() + ": "
                + evaluated.failure().orElseThrow().detail();
        return evaluated.status() == GenericVerificationStatus.TIMED_OUT
                ? RuleDecision.timedOut(detail)
                : RuleDecision.failed(detail);
    }

    private static StepRunnerContext context(
            GenericExecutionSession session,
            long gameTick) {
        return new StepRunnerContext(
                session.sessionId(),
                session.currentStep().orElseThrow().stepId(),
                session.currentAttempt(),
                gameTick);
    }

    private static StepRunResult fail(
            GenericExecutionSession session,
            long gameTick,
            StepRunFailureCode failureCode,
            String detail,
            int actionInvocations) {
        GenericExecutionSession failed = session.fail(
                gameTick, failureCode.resourceId(), detail);
        return result(
                failed,
                StepRunOutcome.FAILED,
                actionInvocations,
                failureCode);
    }

    private static StepRunResult result(
            GenericExecutionSession session,
            StepRunOutcome outcome,
            int actionInvocations,
            StepRunFailureCode failureCode) {
        return new StepRunResult(
                session,
                outcome,
                actionInvocations,
                Optional.ofNullable(failureCode));
    }

    private static <T> Map<ResourceId, T> copyRegistry(
            Map<ResourceId, T> values,
            String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_REGISTERED_HANDLERS) {
            throw new IllegalArgumentException(name + " count exceeds " + MAX_REGISTERED_HANDLERS);
        }
        Map<ResourceId, T> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, T> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private record EvaluatedCondition(
            boolean missingEvaluator,
            ConditionEvaluation evaluation) {
        private static EvaluatedCondition missing() {
            return new EvaluatedCondition(true, null);
        }

        private static EvaluatedCondition value(ConditionEvaluation evaluation) {
            return new EvaluatedCondition(false, evaluation);
        }
    }

    private record ConditionDecision(
            boolean failed,
            boolean waiting,
            StepRunFailureCode failureCode,
            String detail) {
        private static ConditionDecision ready() {
            return new ConditionDecision(false, false, null, null);
        }

        private static ConditionDecision pending() {
            return new ConditionDecision(false, true, null, null);
        }

        private static ConditionDecision failed(
                StepRunFailureCode failureCode,
                String detail) {
            return new ConditionDecision(true, false, failureCode, detail);
        }
    }

    private record RuleDecision(
            boolean failed,
            boolean timedOut,
            boolean waiting,
            String detail) {
        private static RuleDecision passed() {
            return new RuleDecision(false, false, false, null);
        }

        private static RuleDecision pending() {
            return new RuleDecision(false, false, true, null);
        }

        private static RuleDecision failed(String detail) {
            return new RuleDecision(true, false, false, detail);
        }

        private static RuleDecision timedOut(String detail) {
            return new RuleDecision(false, true, false, detail);
        }
    }
}
