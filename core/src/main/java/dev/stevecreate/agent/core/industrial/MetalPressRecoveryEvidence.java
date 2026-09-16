package dev.stevecreate.agent.core.industrial;

/** Server-observed facts used to reconcile a persisted order after reload. */
public record MetalPressRecoveryEvidence(
        boolean materialsStillReserved,
        boolean withdrawnMaterialsAccountedFor,
        boolean deliveryAccountedFor,
        boolean structurePresent,
        boolean multiblockFormed,
        boolean moldInstalled,
        boolean powerNetworkPresent,
        boolean machineReceivedPower,
        boolean inputAdmitted,
        long measuredEnergyConsumedFe,
        long exactOutputCount,
        boolean returnAccountedFor,
        boolean reportPersisted,
        boolean baselineRestored) {
    public MetalPressRecoveryEvidence {
        if (measuredEnergyConsumedFe < 0 || exactOutputCount < 0 || exactOutputCount > 1) {
            throw new IllegalArgumentException("metal-press recovery evidence is out of bounds");
        }
    }
}
