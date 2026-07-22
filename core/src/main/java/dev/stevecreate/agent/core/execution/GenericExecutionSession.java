package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable, serializable-shaped in-memory execution state with validated lifecycle transitions. */
public record GenericExecutionSession(
        ResourceId sessionId,
        GenericExecutionPlan plan,
        GenericExecutionSessionStatus status,
        int currentStepIndex,
        int currentAttempt,
        GenericStepRunState stepRunState,
        long currentStepStartedTick,
        long startedTick,
        long lastProgressTick,
        List<ResourceId> completedStepIds,
        List<VerificationEvidence> evidence,
        List<SessionWorldChangeReference> worldChanges,
        Optional<SessionFailure> failure,
        Optional<ResourceId> cancellationReason,
        OptionalLong terminalTick) {
    public static final int MAX_EVIDENCE_REFERENCES = 1_024;
    public static final int MAX_WORLD_CHANGE_REFERENCES = 4_096;

    public GenericExecutionSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(status, "status");
        if (currentStepIndex < 0 || currentStepIndex > plan.steps().size()) {
            throw new IllegalArgumentException("currentStepIndex is outside the plan");
        }
        if (status == GenericExecutionSessionStatus.COMPLETED) {
            if (currentStepIndex != plan.steps().size()) {
                throw new IllegalArgumentException("A completed session must be past the last step");
            }
        } else if (currentStepIndex >= plan.steps().size()) {
            throw new IllegalArgumentException("A non-completed session must have a current step");
        }
        int maximumAttempts = ExecutionSessionValues.attemptLimit(plan, currentStepIndex);
        if (currentAttempt < 1 || currentAttempt > maximumAttempts) {
            throw new IllegalArgumentException(
                    "currentAttempt must be between 1 and " + maximumAttempts);
        }
        Objects.requireNonNull(stepRunState, "stepRunState");
        if (startedTick < 0
                || currentStepStartedTick < startedTick
                || lastProgressTick < currentStepStartedTick) {
            throw new IllegalArgumentException(
                    "Session ticks must be non-negative and monotonic");
        }
        if (status == GenericExecutionSessionStatus.COMPLETED
                && stepRunState != GenericStepRunState.READY) {
            throw new IllegalArgumentException("A completed session must have a ready cursor");
        }

        completedStepIds = ExecutionSessionValues.copyCompletedSteps(
                plan, currentStepIndex, completedStepIds);
        evidence = ExecutionSessionValues.copyEvidence(
                plan, startedTick, lastProgressTick, evidence);
        worldChanges = ExecutionSessionValues.copyWorldChanges(
                plan, startedTick, lastProgressTick, worldChanges);
        failure = Objects.requireNonNull(failure, "failure");
        cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
        terminalTick = Objects.requireNonNull(terminalTick, "terminalTick");
        ExecutionSessionValues.validateTerminalState(
                plan,
                status,
                lastProgressTick,
                failure,
                cancellationReason,
                terminalTick);
    }

    public static GenericExecutionSession start(
            ResourceId sessionId,
            GenericExecutionPlan plan,
            long startedTick) {
        return new GenericExecutionSession(
                sessionId,
                plan,
                GenericExecutionSessionStatus.RUNNING,
                0,
                1,
                GenericStepRunState.READY,
                startedTick,
                startedTick,
                startedTick,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty());
    }

    public Optional<GenericExecutionStep> currentStep() {
        return currentStepIndex < plan.steps().size()
                ? Optional.of(plan.steps().get(currentStepIndex))
                : Optional.empty();
    }

    public Optional<GenericExecutionPhase> currentPhase() {
        return currentStep().map(GenericExecutionStep::phase);
    }

    public GenericExecutionSession beginAction(long tick) {
        ensureRunning();
        if (stepRunState != GenericStepRunState.READY) {
            throw new IllegalStateException(
                    "Current step is not ready to begin an action: " + stepRunState);
        }
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                GenericStepRunState.ACTION,
                currentStepStartedTick,
                requireNextTick(tick),
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession beginVerification(long tick) {
        ensureRunning();
        if (stepRunState != GenericStepRunState.ACTION) {
            throw new IllegalStateException(
                    "Current step is not executing an action: " + stepRunState);
        }
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                GenericStepRunState.VERIFY,
                currentStepStartedTick,
                requireNextTick(tick),
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession markProgress(long tick) {
        ensureRunning();
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                requireNextTick(tick),
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    /**
     * Starts a fresh timeout window after an exact recovery rescan.
     *
     * <p>This transition deliberately preserves the current step cursor, attempt, run state,
     * evidence and world-change references. Callers must expose it only after persisted state has
     * been reconciled against the live world.</p>
     */
    public GenericExecutionSession rebaseRecoveryTiming(long tick) {
        ensureRunning();
        long resumedTick = requireNextTick(tick);
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                resumedTick,
                resumedTick,
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession recordEvidence(VerificationEvidence reference) {
        ensureRunning();
        Objects.requireNonNull(reference, "reference");
        requireCurrentStepSource(reference.sourceStepId(), "evidence");
        long tick = requireNextTick(reference.observedTick());
        List<VerificationEvidence> next = new ArrayList<>(evidence);
        next.add(reference);
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                tick,
                completedStepIds,
                next,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession recordWorldChange(SessionWorldChangeReference reference) {
        ensureRunning();
        Objects.requireNonNull(reference, "reference");
        requireCurrentStepSource(reference.sourceStepId(), "world change");
        long tick = requireNextTick(reference.recordedTick());
        List<SessionWorldChangeReference> next = new ArrayList<>(worldChanges);
        next.add(reference);
        return copy(
                status,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                tick,
                completedStepIds,
                evidence,
                next,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession retryCurrentStep(long tick, ResourceId failureCode) {
        ensureRunning();
        Objects.requireNonNull(failureCode, "failureCode");
        RetryPolicy policy = currentStep().orElseThrow().retryPolicy();
        if (!policy.retryableFailures().contains(failureCode)) {
            throw new IllegalStateException(
                    "Current step does not allow retry for failure: " + failureCode);
        }
        if (currentAttempt >= policy.maximumAttempts()) {
            throw new IllegalStateException("Current step retry limit is exhausted");
        }
        return copy(
                status,
                currentStepIndex,
                currentAttempt + 1,
                GenericStepRunState.READY,
                currentStepStartedTick,
                requireNextTick(tick),
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    public GenericExecutionSession completeCurrentStep(long tick) {
        ensureRunning();
        long progressTick = requireNextTick(tick);
        List<ResourceId> completed = new ArrayList<>(completedStepIds);
        completed.add(currentStep().orElseThrow().stepId());
        int nextIndex = currentStepIndex + 1;
        boolean completedPlan = nextIndex == plan.steps().size();
        return copy(
                completedPlan
                        ? GenericExecutionSessionStatus.COMPLETED
                        : GenericExecutionSessionStatus.RUNNING,
                nextIndex,
                1,
                GenericStepRunState.READY,
                progressTick,
                progressTick,
                completed,
                evidence,
                worldChanges,
                Optional.empty(),
                Optional.empty(),
                completedPlan ? OptionalLong.of(progressTick) : OptionalLong.empty());
    }

    public GenericExecutionSession cancel(long tick, ResourceId reason) {
        ensureRunning();
        Objects.requireNonNull(reason, "reason");
        if (!currentStep().orElseThrow().cancellable()) {
            throw new IllegalStateException("Current step does not allow cancellation");
        }
        long terminal = requireNextTick(tick);
        return copy(
                GenericExecutionSessionStatus.CANCELLED,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                terminal,
                completedStepIds,
                evidence,
                worldChanges,
                Optional.empty(),
                Optional.of(reason),
                OptionalLong.of(terminal));
    }

    public GenericExecutionSession timeout(long tick, ResourceId failureCode, String detail) {
        return terminalFailure(
                GenericExecutionSessionStatus.TIMED_OUT,
                tick,
                failureCode,
                detail);
    }

    public GenericExecutionSession fail(long tick, ResourceId failureCode, String detail) {
        return terminalFailure(
                GenericExecutionSessionStatus.FAILED,
                tick,
                failureCode,
                detail);
    }

    private GenericExecutionSession terminalFailure(
            GenericExecutionSessionStatus terminalStatus,
            long tick,
            ResourceId failureCode,
            String detail) {
        ensureRunning();
        long terminal = requireNextTick(tick);
        SessionFailure terminalFailure = new SessionFailure(
                failureCode,
                currentStep().orElseThrow().stepId(),
                terminal,
                detail);
        return copy(
                terminalStatus,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                terminal,
                completedStepIds,
                evidence,
                worldChanges,
                Optional.of(terminalFailure),
                Optional.empty(),
                OptionalLong.of(terminal));
    }

    private GenericExecutionSession copy(
            GenericExecutionSessionStatus nextStatus,
            int nextStepIndex,
            int nextAttempt,
            GenericStepRunState nextRunState,
            long nextStepStartedTick,
            long nextProgressTick,
            List<ResourceId> nextCompletedSteps,
            List<VerificationEvidence> nextEvidence,
            List<SessionWorldChangeReference> nextWorldChanges,
            Optional<SessionFailure> nextFailure,
            Optional<ResourceId> nextCancellationReason,
            OptionalLong nextTerminalTick) {
        return new GenericExecutionSession(
                sessionId,
                plan,
                nextStatus,
                nextStepIndex,
                nextAttempt,
                nextRunState,
                nextStepStartedTick,
                startedTick,
                nextProgressTick,
                nextCompletedSteps,
                nextEvidence,
                nextWorldChanges,
                nextFailure,
                nextCancellationReason,
                nextTerminalTick);
    }

    private void ensureRunning() {
        if (status != GenericExecutionSessionStatus.RUNNING) {
            throw new IllegalStateException("Session is already terminal: " + status);
        }
    }

    private long requireNextTick(long tick) {
        if (tick < lastProgressTick) {
            throw new IllegalArgumentException(
                    "Progress tick must not move backwards from " + lastProgressTick);
        }
        return tick;
    }

    private void requireCurrentStepSource(ResourceId sourceStepId, String kind) {
        ResourceId current = currentStep().orElseThrow().stepId();
        if (!current.equals(sourceStepId)) {
            throw new IllegalArgumentException(
                    "Session " + kind + " must belong to current step " + current);
        }
    }

}
