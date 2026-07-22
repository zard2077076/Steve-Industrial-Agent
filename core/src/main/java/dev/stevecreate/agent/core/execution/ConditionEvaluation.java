package dev.stevecreate.agent.core.execution;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Bounded typed condition result; evaluator exceptions are not hidden by this value. */
public record ConditionEvaluation(
        ConditionEvaluationStatus status,
        Optional<ResourceId> failureCode,
        Optional<String> detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public ConditionEvaluation {
        Objects.requireNonNull(status, "status");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        detail = Objects.requireNonNull(detail, "detail");
        if ((status == ConditionEvaluationStatus.ERROR) != failureCode.isPresent()
                || (status == ConditionEvaluationStatus.ERROR) != detail.isPresent()) {
            throw new IllegalArgumentException(
                    "Only an error condition evaluation carries failure detail");
        }
        detail.ifPresent(value -> requireDetail(value, "detail"));
    }

    public static ConditionEvaluation satisfied() {
        return new ConditionEvaluation(
                ConditionEvaluationStatus.SATISFIED, Optional.empty(), Optional.empty());
    }

    public static ConditionEvaluation unsatisfied() {
        return new ConditionEvaluation(
                ConditionEvaluationStatus.UNSATISFIED, Optional.empty(), Optional.empty());
    }

    public static ConditionEvaluation error(ResourceId failureCode, String detail) {
        return new ConditionEvaluation(
                ConditionEvaluationStatus.ERROR,
                Optional.of(Objects.requireNonNull(failureCode, "failureCode")),
                Optional.of(requireDetail(detail, "detail")));
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
