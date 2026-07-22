package dev.stevecreate.agent.core.deployment;

import java.util.Objects;

public record DeploymentReadinessSuccess(DeploymentReadyPlan plan)
        implements DeploymentReadinessResult {
    public DeploymentReadinessSuccess {
        Objects.requireNonNull(plan, "plan");
    }
}
