package dev.stevecreate.agent.core.deployment;

import java.util.List;
import java.util.Objects;

public record DeploymentReadinessRefusal(
        List<DeploymentReadinessFailure> failures,
        List<DeploymentFailure> details)
        implements DeploymentReadinessResult {
    public DeploymentReadinessRefusal {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        details = List.copyOf(Objects.requireNonNull(details, "details"));
        if (failures.isEmpty()) throw new IllegalArgumentException("refusal requires failures");
        if (details.size() != failures.size()) {
            throw new IllegalArgumentException("every refusal requires one complete detail");
        }
        DeploymentReadinessFailure previous = null;
        for (DeploymentReadinessFailure failure : failures) {
            Objects.requireNonNull(failure, "failure");
            if (previous != null && previous.ordinal() >= failure.ordinal()) {
                throw new IllegalArgumentException("failures are duplicate or unordered");
            }
            previous = failure;
        }
        if (details.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("refusal details cannot contain null");
        }
    }
}
