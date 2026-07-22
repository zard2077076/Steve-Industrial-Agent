package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

/** Gate result only; acceptance is not readiness or execution authority. */
public record HumanApprovalCheck(
        List<HumanApprovalFailure> failures,
        boolean accepted) {
    public HumanApprovalCheck {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        HumanApprovalFailure previous = null;
        for (HumanApprovalFailure failure : failures) {
            Objects.requireNonNull(failure, "failure");
            if (previous != null && previous.ordinal() >= failure.ordinal()) {
                throw new IllegalArgumentException("approval failures are duplicate or unordered");
            }
            previous = failure;
        }
        if (accepted != failures.isEmpty()) {
            throw new IllegalArgumentException("accepted disagrees with failures");
        }
    }
}
