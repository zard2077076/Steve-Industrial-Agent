package dev.stevecreate.agent.core.deployment;

public sealed interface DeploymentReadinessResult
        permits DeploymentReadinessSuccess, DeploymentReadinessRefusal {
}
