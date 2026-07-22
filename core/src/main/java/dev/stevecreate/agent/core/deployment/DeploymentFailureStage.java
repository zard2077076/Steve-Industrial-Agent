package dev.stevecreate.agent.core.deployment;

public enum DeploymentFailureStage {
    PLAN,
    ENVIRONMENT,
    POLICY,
    DRY_RUN,
    SNAPSHOT,
    AUTHORIZATION,
    RISK,
    BUDGET,
    BACKUP,
    APPROVAL,
    ROLLBACK,
    FINAL_GATE
}
