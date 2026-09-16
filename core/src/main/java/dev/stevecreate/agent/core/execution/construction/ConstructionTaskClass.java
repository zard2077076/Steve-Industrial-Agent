package dev.stevecreate.agent.core.execution.construction;

/** Hybrid routing classification; it never grants an execution capability. */
public enum ConstructionTaskClass {
    ORDINARY_BLOCK,
    CREATE_MACHINE,
    ORIENTATION_SENSITIVE_COMPONENT,
    MATERIAL_TRANSPORT,
    OBSTRUCTION_CLEARANCE,
    SYSTEM_VERIFICATION,
    TEST_ONLY_RESOURCE,
    HIGH_RISK_INTERACTION,
    SHARED_INFRASTRUCTURE
}
