package dev.stevecreate.agent.core.warehouse;

public enum ProductionOrderStatus {
    ACTIVE,
    BATCH_IN_FLIGHT,
    COOLDOWN,
    PAUSED,
    TARGET_SATISFIED,
    CANCELLED
}
