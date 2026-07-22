package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.GenericStepRunState;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.SessionFailure;
import dev.stevecreate.agent.core.execution.SessionWorldChangeReference;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Complete loader-neutral session state without trusting a persisted executable plan. */
public record RecoverySessionSnapshot(
        ResourceId sessionId,
        ResourceId planId,
        GenericExecutionSessionStatus status,
        int currentStepIndex,
        Optional<ResourceId> currentStepId,
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

    public RecoverySessionSnapshot {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(status, "status");
        if (currentStepIndex < 0 || currentStepIndex > GenericExecutionPlan.MAX_STEPS) {
            throw new IllegalArgumentException("currentStepIndex is outside recovery bounds");
        }
        currentStepId = Objects.requireNonNull(currentStepId, "currentStepId");
        boolean completed = status == GenericExecutionSessionStatus.COMPLETED;
        if (completed == currentStepId.isPresent()) {
            throw new IllegalArgumentException(
                    "currentStepId must be absent only for a completed session");
        }
        if (currentAttempt < 1 || currentAttempt > RetryPolicy.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("currentAttempt is outside recovery bounds");
        }
        Objects.requireNonNull(stepRunState, "stepRunState");
        if (startedTick < 0
                || currentStepStartedTick < startedTick
                || lastProgressTick < currentStepStartedTick) {
            throw new IllegalArgumentException("Recovery session ticks must be monotonic");
        }
        completedStepIds = copyUniqueIds(
                completedStepIds, "completedStepIds", GenericExecutionPlan.MAX_STEPS);
        evidence = copyEvidence(evidence);
        worldChanges = copyWorldChanges(worldChanges);
        failure = Objects.requireNonNull(failure, "failure");
        cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
        terminalTick = Objects.requireNonNull(terminalTick, "terminalTick");
        if (status.terminal() != terminalTick.isPresent()) {
            throw new IllegalArgumentException(
                    "terminalTick presence must match terminal session status");
        }
        if (terminalTick.isPresent() && terminalTick.getAsLong() != lastProgressTick) {
            throw new IllegalArgumentException("terminalTick must equal lastProgressTick");
        }
        if (status.failureTerminal() != failure.isPresent()) {
            throw new IllegalArgumentException(
                    "failure presence must match failed or timed-out status");
        }
        boolean cancelled = status == GenericExecutionSessionStatus.CANCELLED;
        if (cancelled != cancellationReason.isPresent()) {
            throw new IllegalArgumentException(
                    "cancellationReason presence must match cancelled status");
        }
    }

    public static RecoverySessionSnapshot capture(GenericExecutionSession session) {
        Objects.requireNonNull(session, "session");
        return new RecoverySessionSnapshot(
                session.sessionId(),
                session.plan().planId(),
                session.status(),
                session.currentStepIndex(),
                session.currentStep().map(value -> value.stepId()),
                session.currentAttempt(),
                session.stepRunState(),
                session.currentStepStartedTick(),
                session.startedTick(),
                session.lastProgressTick(),
                session.completedStepIds(),
                session.evidence(),
                session.worldChanges(),
                session.failure(),
                session.cancellationReason(),
                session.terminalTick());
    }

    GenericExecutionSession restore(GenericExecutionPlan trustedPlan) {
        Objects.requireNonNull(trustedPlan, "trustedPlan");
        if (!trustedPlan.planId().equals(planId)) {
            throw new IllegalArgumentException(
                    "Trusted plan id does not match recovery session plan id");
        }
        Optional<ResourceId> expectedCurrent = currentStepIndex < trustedPlan.steps().size()
                ? Optional.of(trustedPlan.steps().get(currentStepIndex).stepId())
                : Optional.empty();
        if (!expectedCurrent.equals(currentStepId)) {
            throw new IllegalArgumentException(
                    "Recovery current step does not match the trusted plan");
        }
        return new GenericExecutionSession(
                sessionId,
                trustedPlan,
                status,
                currentStepIndex,
                currentAttempt,
                stepRunState,
                currentStepStartedTick,
                startedTick,
                lastProgressTick,
                completedStepIds,
                evidence,
                worldChanges,
                failure,
                cancellationReason,
                terminalTick);
    }

    private static List<ResourceId> copyUniqueIds(
            List<ResourceId> values,
            String name,
            int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " count exceeds " + maximum);
        }
        List<ResourceId> copy = List.copyOf(values);
        if (copy.stream().anyMatch(Objects::isNull)
                || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must contain unique non-null ids");
        }
        return copy;
    }

    private static List<VerificationEvidence> copyEvidence(
            List<VerificationEvidence> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.size() > GenericExecutionSession.MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException("evidence count exceeds recovery bounds");
        }
        List<VerificationEvidence> copy = List.copyOf(values);
        Set<ResourceId> ids = new HashSet<>();
        for (VerificationEvidence value : copy) {
            if (value == null || !ids.add(value.evidenceId())) {
                throw new IllegalArgumentException(
                        "evidence must contain unique non-null identities");
            }
        }
        return copy;
    }

    private static List<SessionWorldChangeReference> copyWorldChanges(
            List<SessionWorldChangeReference> values) {
        Objects.requireNonNull(values, "worldChanges");
        if (values.size() > GenericExecutionSession.MAX_WORLD_CHANGE_REFERENCES) {
            throw new IllegalArgumentException("worldChanges count exceeds recovery bounds");
        }
        List<SessionWorldChangeReference> copy = List.copyOf(values);
        Set<ResourceId> ids = new HashSet<>();
        for (SessionWorldChangeReference value : copy) {
            if (value == null || !ids.add(value.changeId())) {
                throw new IllegalArgumentException(
                        "worldChanges must contain unique non-null identities");
            }
        }
        return copy;
    }
}
