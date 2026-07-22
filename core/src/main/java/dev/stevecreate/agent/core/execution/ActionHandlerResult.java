package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable output from at most one bounded world-action invocation. */
public record ActionHandlerResult(
        ActionHandlerStatus status,
        boolean madeProgress,
        List<VerificationEvidence> evidence,
        List<SessionWorldChangeReference> worldChanges,
        Optional<ResourceId> failureCode,
        Optional<String> detail) {
    public static final int MAX_REFERENCES_PER_INVOCATION = 64;
    public static final int MAX_DETAIL_LENGTH = 512;

    public ActionHandlerResult {
        Objects.requireNonNull(status, "status");
        evidence = copyReferences(evidence, "evidence");
        worldChanges = copyReferences(worldChanges, "worldChanges");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        detail = Objects.requireNonNull(detail, "detail");
        boolean failed = status == ActionHandlerStatus.FAILED;
        if (failed != failureCode.isPresent() || failed != detail.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a failed action result carries failure detail");
        }
        if (failed && madeProgress) {
            throw new IllegalArgumentException("A failed action result cannot claim progress");
        }
        detail.ifPresent(value -> requireDetail(value, "detail"));
    }

    public static ActionHandlerResult inProgress(boolean madeProgress) {
        return inProgress(madeProgress, List.of());
    }

    public static ActionHandlerResult inProgress(
            boolean madeProgress,
            List<SessionWorldChangeReference> worldChanges) {
        return new ActionHandlerResult(
                ActionHandlerStatus.IN_PROGRESS,
                madeProgress,
                List.of(),
                worldChanges,
                Optional.empty(),
                Optional.empty());
    }

    public static ActionHandlerResult succeeded(
            List<VerificationEvidence> evidence,
            List<SessionWorldChangeReference> worldChanges) {
        return new ActionHandlerResult(
                ActionHandlerStatus.SUCCEEDED,
                true,
                evidence,
                worldChanges,
                Optional.empty(),
                Optional.empty());
    }

    public static ActionHandlerResult failed(ResourceId failureCode, String detail) {
        return failed(failureCode, detail, List.of());
    }

    public static ActionHandlerResult failed(
            ResourceId failureCode,
            String detail,
            List<SessionWorldChangeReference> worldChanges) {
        return new ActionHandlerResult(
                ActionHandlerStatus.FAILED,
                false,
                List.of(),
                worldChanges,
                Optional.of(Objects.requireNonNull(failureCode, "failureCode")),
                Optional.of(requireDetail(detail, "detail")));
    }

    private static <T> List<T> copyReferences(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_REFERENCES_PER_INVOCATION) {
            throw new IllegalArgumentException(
                    name + " count exceeds " + MAX_REFERENCES_PER_INVOCATION);
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireDetail(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_DETAIL_LENGTH + " characters");
        }
        return value;
    }
}
