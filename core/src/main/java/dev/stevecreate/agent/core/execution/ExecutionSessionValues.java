package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class ExecutionSessionValues {
    private ExecutionSessionValues() {
    }

    static int attemptLimit(GenericExecutionPlan plan, int currentStepIndex) {
        int stepIndex = Math.min(currentStepIndex, plan.steps().size() - 1);
        return plan.steps().get(stepIndex).retryPolicy().maximumAttempts();
    }

    static List<ResourceId> copyCompletedSteps(
            GenericExecutionPlan plan,
            int currentStepIndex,
            List<ResourceId> values) {
        Objects.requireNonNull(values, "completedStepIds");
        if (values.size() != currentStepIndex) {
            throw new IllegalArgumentException(
                    "completedStepIds must exactly match the completed plan prefix");
        }
        List<ResourceId> copy = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            ResourceId value = Objects.requireNonNull(
                    values.get(index), "completedStepIds element");
            ResourceId expected = plan.steps().get(index).stepId();
            if (!value.equals(expected)) {
                throw new IllegalArgumentException(
                        "completedStepIds must exactly match the completed plan prefix");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    static List<VerificationEvidence> copyEvidence(
            GenericExecutionPlan plan,
            long startedTick,
            long lastProgressTick,
            List<VerificationEvidence> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.size() > GenericExecutionSession.MAX_EVIDENCE_REFERENCES) {
            throw new IllegalArgumentException(
                    "evidence count exceeds " + GenericExecutionSession.MAX_EVIDENCE_REFERENCES);
        }
        List<VerificationEvidence> copy = new ArrayList<>(values.size());
        Set<ResourceId> ids = new HashSet<>();
        for (VerificationEvidence value : values) {
            VerificationEvidence reference = Objects.requireNonNull(
                    value, "evidence element");
            if (!ids.add(reference.evidenceId())) {
                throw new IllegalArgumentException(
                        "Duplicate session evidence id: " + reference.evidenceId());
            }
            validateReference(
                    plan,
                    reference.sourceStepId(),
                    reference.observedTick(),
                    startedTick,
                    lastProgressTick);
            copy.add(reference);
        }
        return Collections.unmodifiableList(copy);
    }

    static List<SessionWorldChangeReference> copyWorldChanges(
            GenericExecutionPlan plan,
            long startedTick,
            long lastProgressTick,
            List<SessionWorldChangeReference> values) {
        Objects.requireNonNull(values, "worldChanges");
        if (values.size() > GenericExecutionSession.MAX_WORLD_CHANGE_REFERENCES) {
            throw new IllegalArgumentException(
                    "worldChanges count exceeds "
                            + GenericExecutionSession.MAX_WORLD_CHANGE_REFERENCES);
        }
        List<SessionWorldChangeReference> copy = new ArrayList<>(values.size());
        Set<ResourceId> ids = new HashSet<>();
        for (SessionWorldChangeReference value : values) {
            SessionWorldChangeReference reference = Objects.requireNonNull(
                    value, "worldChanges element");
            if (!ids.add(reference.changeId())) {
                throw new IllegalArgumentException(
                        "Duplicate session world change id: " + reference.changeId());
            }
            validateReference(
                    plan,
                    reference.sourceStepId(),
                    reference.recordedTick(),
                    startedTick,
                    lastProgressTick);
            copy.add(reference);
        }
        return Collections.unmodifiableList(copy);
    }

    static void validateTerminalState(
            GenericExecutionPlan plan,
            GenericExecutionSessionStatus status,
            long lastProgressTick,
            Optional<SessionFailure> failure,
            Optional<ResourceId> cancellationReason,
            OptionalLong terminalTick) {
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
        if (failure.isPresent()) {
            SessionFailure value = failure.orElseThrow();
            plan.step(value.sourceStepId());
            if (value.failedTick() != terminalTick.orElseThrow()) {
                throw new IllegalArgumentException("failure tick must equal terminalTick");
            }
        }
        boolean cancelled = status == GenericExecutionSessionStatus.CANCELLED;
        if (cancelled != cancellationReason.isPresent()) {
            throw new IllegalArgumentException(
                    "cancellationReason presence must match cancelled status");
        }
    }

    private static void validateReference(
            GenericExecutionPlan plan,
            ResourceId sourceStepId,
            long tick,
            long startedTick,
            long lastProgressTick) {
        plan.step(sourceStepId);
        if (tick < startedTick || tick > lastProgressTick) {
            throw new IllegalArgumentException(
                    "Session reference tick must be within the observed session interval");
        }
    }
}
