package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

/** Read-only scope-check result. Passing does not construct readiness or a write permit. */
public record RegionAuthorizationCheck(
        List<RegionAuthorizationFailure> failures,
        boolean allowed) {
    public RegionAuthorizationCheck {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        RegionAuthorizationFailure previous = null;
        for (RegionAuthorizationFailure failure : failures) {
            Objects.requireNonNull(failure, "failure");
            if (previous != null && previous.ordinal() >= failure.ordinal()) {
                throw new IllegalArgumentException("failures are duplicate or unordered");
            }
            previous = failure;
        }
        if (allowed != failures.isEmpty()) {
            throw new IllegalArgumentException("allowed disagrees with failures");
        }
    }
}
