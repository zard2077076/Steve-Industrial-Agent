package dev.stevecreate.agent.core.diagnostic;

/** Provenance of a bounded observation; none of these grants mutation authority. */
public enum FactoryEvidenceSource {
    LIVE_WORLD,
    LIVE_WORLD_UNAVAILABLE,
    MATERIAL_LEDGER,
    WAREHOUSE_TOPOLOGY,
    DURABLE_RUNTIME_STATUS,
    NO_PROBE
}
