package dev.stevecreate.agent.core.planning;

/** Stable expected-failure identities for candidate-to-logical-graph mapping. */
public enum LogicalGraphMappingFailureCode {
    CANDIDATE_INCONSISTENT,
    MACHINE_CAPABILITY_MISSING,
    MACHINE_CAPABILITY_INCOMPATIBLE,
    CAPABILITY_ADAPTER_UNAVAILABLE,
    RESOURCE_TYPE_UNSUPPORTED,
    INPUT_ALLOCATION_UNRESOLVED,
    TARGET_OUTPUT_MISSING,
    TARGET_OUTPUT_AMBIGUOUS,
    QUANTITY_OVERFLOW
}
