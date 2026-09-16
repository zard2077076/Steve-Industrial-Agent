package dev.stevecreate.agent.core.industrial;

import java.util.Objects;
import java.util.Map;
import dev.stevecreate.agent.core.model.ResourceId;

/** Auditable terminal reconciliation for one exact one-ingot/one-plate order. */
public record MetalPressCompletionReport(
        long plannedMaterialUnits,
        long withdrawnMaterialUnits,
        long consumedMaterialUnits,
        long returnedMaterialUnits,
        long energyConsumedFe,
        long outputCount,
        int duplicateWithdrawals,
        int duplicateEnergySettlements,
        int duplicateOutputs,
        int duplicateReturns,
        long unaccountedItems,
        long privateItemsTouched,
        boolean materialLedgerBalanced,
        boolean baselineRestored,
        String baselineHash) {
    public MetalPressCompletionReport {
        if (plannedMaterialUnits < 0 || withdrawnMaterialUnits < 0
                || consumedMaterialUnits < 0 || returnedMaterialUnits < 0
                || energyConsumedFe < 0 || outputCount < 0
                || duplicateWithdrawals < 0 || duplicateEnergySettlements < 0
                || duplicateOutputs < 0 || duplicateReturns < 0
                || unaccountedItems < 0 || privateItemsTouched < 0) {
            throw new IllegalArgumentException("completion-report counters cannot be negative");
        }
        Objects.requireNonNull(baselineHash, "baselineHash");
        if (baselineHash.length() != 64) {
            throw new IllegalArgumentException("baseline hash must be SHA-256");
        }
        boolean exactBalance = withdrawnMaterialUnits == consumedMaterialUnits + returnedMaterialUnits
                && duplicateWithdrawals == 0 && duplicateEnergySettlements == 0
                && duplicateOutputs == 0 && duplicateReturns == 0
                && unaccountedItems == 0 && privateItemsTouched == 0;
        if (materialLedgerBalanced != exactBalance) {
            throw new IllegalArgumentException("material-ledger balance claim is inconsistent");
        }
    }

    public boolean accepted(long expectedEnergyFe) {
        return materialLedgerBalanced && baselineRestored && energyConsumedFe == expectedEnergyFe
                && outputCount == 1 && duplicateWithdrawals == 0
                && duplicateEnergySettlements == 0 && duplicateOutputs == 0
                && duplicateReturns == 0 && privateItemsTouched == 0;
    }

    /** Additive projection consumed by the common industrial player-order report. */
    public IndustrialCompletionReportV1 toIndustrialReport() {
        ResourceId units = ResourceId.parse("steve_industrial:material_units");
        ResourceId fe = ResourceId.parse("immersiveengineering:fe");
        ResourceId plate = ResourceId.parse("immersiveengineering:plate_iron");
        return IndustrialCompletionReportV1.create(
                Map.of(units, plannedMaterialUnits),
                Map.of(units, withdrawnMaterialUnits),
                Map.of(units, consumedMaterialUnits),
                Map.of(units, returnedMaterialUnits),
                Map.of(fe, energyConsumedFe), Map.of(), Map.of(plate, outputCount), 0,
                duplicateWithdrawals, duplicateReturns, duplicateEnergySettlements,
                duplicateOutputs, unaccountedItems, privateItemsTouched,
                baselineRestored, baselineHash);
    }
}
