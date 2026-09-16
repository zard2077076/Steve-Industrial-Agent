package dev.stevecreate.agent.core.industrial;

/** Non-repeatable side effects protected by the production-order journal. */
public enum MetalPressDurableEffect {
    MATERIAL_WITHDRAWAL,
    INPUT_ADMISSION,
    ENERGY_SETTLEMENT,
    OUTPUT_CLAIM,
    MATERIAL_RETURN,
    COMPLETION_REPORT,
    BASELINE_RESTORE
}
