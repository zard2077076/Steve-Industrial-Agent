package dev.stevecreate.agent.core.deployment;

public enum WritableTestWorldStage {
    DISCOVERY,
    IDENTITY,
    REGION_SELECTION,
    REGION_PREVIEW,
    REGION_CONFIRMATION,
    BACKUP,
    READINESS,
    EXECUTION,
    CLEANUP,
    RECOVERY
}
