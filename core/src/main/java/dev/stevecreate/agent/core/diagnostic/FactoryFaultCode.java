package dev.stevecreate.agent.core.diagnostic;

/** Stable player-facing failure vocabulary for the read-only diagnosis report. */
public enum FactoryFaultCode {
    MATERIAL_SHORTAGE,
    OUTPUT_CAPACITY_EXHAUSTED,
    LOGISTICS_ROUTE_BLOCKED,
    ENERGY_INSUFFICIENT,
    STRUCTURE_OR_ORIENTATION_MISMATCH,
    RESERVATION_DRIFT
}
