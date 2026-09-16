package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkVerifier;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidNetworkVerifier;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Read-only reconciliation and exact settlement for an IPO-03 resource binding. */
public final class IndustrialResourceRecoveryV1 {
    private IndustrialResourceRecoveryV1() {}

    public static RecoveryResult reconcile(
            IndustrialResourceBindingV1 expected,
            GlobalInventoryGraph observedWarehouse,
            EntityLogisticsBindingV1 observedLogistics,
            ElectricalNetworkGraph observedElectrical,
            FluidNetworkGraph observedFluid) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observedWarehouse, "observedWarehouse");
        Objects.requireNonNull(observedLogistics, "observedLogistics");
        if (!expected.warehouse().topologyFingerprint().equals(
                observedWarehouse.topologyFingerprint())) {
            return RecoveryResult.paused("WAREHOUSE_ENDPOINT_DRIFT");
        }
        if (!expected.entityLogistics().fingerprint().equals(observedLogistics.fingerprint())) {
            return RecoveryResult.paused("ENTITY_LOGISTICS_DRIFT");
        }
        if (expected.electricalNetwork().isPresent()) {
            if (observedElectrical == null || !expected.electricalNetwork().orElseThrow()
                    .topologyFingerprint().equals(observedElectrical.topologyFingerprint())) {
                return RecoveryResult.paused("ELECTRICAL_ENDPOINT_DRIFT");
            }
            if (!new ElectricalNetworkVerifier().verify(observedElectrical).accepted()) {
                return RecoveryResult.paused("ELECTRICAL_NETWORK_INVALID");
            }
        } else if (observedElectrical != null) {
            return RecoveryResult.paused("UNPLANNED_ELECTRICAL_NETWORK");
        }
        if (expected.fluidNetwork().isPresent()) {
            if (observedFluid == null || !expected.fluidNetwork().orElseThrow()
                    .topologyFingerprint().equals(observedFluid.topologyFingerprint())) {
                return RecoveryResult.paused("FLUID_ENDPOINT_DRIFT");
            }
            if (!new FluidNetworkVerifier().verify(observedFluid).accepted()) {
                return RecoveryResult.paused("FLUID_NETWORK_INVALID");
            }
        } else if (observedFluid != null) {
            return RecoveryResult.paused("UNPLANNED_FLUID_NETWORK");
        }
        return RecoveryResult.ready(expected.fingerprint());
    }

    public static SettlementResult settle(
            IndustrialResourceBindingV1 binding,
            WarehouseReservationSystem warehouse,
            EntityLogisticsSettlementV1 logistics,
            ProjectEnergyLedger energy,
            ProjectFluidLedger fluid) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(warehouse, "warehouse");
        Objects.requireNonNull(logistics, "logistics");
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(fluid, "fluid");
        var warehouseBalance = warehouse.balance(binding.projectId());
        var energyBalance = energy.balance(binding.projectId());
        var fluidBalance = fluid.balance(binding.projectId());
        Map<ResourceId, Long> settledEnergy = new LinkedHashMap<>();
        energy.transactions().values().stream()
                .filter(row -> row.projectId().equals(binding.projectId())
                        && row.state() == dev.stevecreate.agent.core.electrical.EnergyTransactionState.SETTLED)
                .sorted(Comparator.comparing(row -> row.transactionId().toString()))
                .forEach(row -> settledEnergy.merge(row.energyResource(), row.amountFe(), Math::addExact));
        Map<FluidIdentity, Long> consumedFluids = new LinkedHashMap<>();
        fluid.transactions().values().stream()
                .filter(row -> row.projectId().equals(binding.projectId())
                        && row.state() == dev.stevecreate.agent.core.fluid.FluidTransactionState.CONSUMED)
                .sorted(Comparator.comparing(row -> row.transactionId().toString()))
                .forEach(row -> consumedFluids.merge(row.identity(), row.amountMb(), Math::addExact));
        Map<ResourceId, Long> expectedWarehouse = new LinkedHashMap<>();
        binding.warehouseRequests().forEach(request -> expectedWarehouse.put(
                request.requestId(), request.quantity()));
        Map<ResourceId, Long> terminalWarehouse = new LinkedHashMap<>();
        warehouse.allocations().values().stream()
                .filter(row -> row.projectId().equals(binding.projectId())
                        && (row.status() == WarehouseReservationStatus.CONSUMED
                        || row.status() == WarehouseReservationStatus.RETURNED))
                .forEach(row -> terminalWarehouse.merge(
                        row.requestId(), row.quantity(), Math::addExact));
        boolean balanced = warehouseBalance.balanced()
                && warehouseBalance.outstanding() == 0
                && terminalWarehouse.equals(expectedWarehouse)
                && logistics.exactlySettles(binding.entityLogistics())
                && energyBalance.balanced()
                && fluidBalance.balanced()
                && fluidBalance.outstandingMb() == 0
                && settledEnergy.equals(binding.plannedEnergy())
                && consumedFluids.equals(binding.plannedFluids());
        String code = balanced ? "RESOURCE_LEDGER_BALANCED" : firstFailure(
                binding, warehouseBalance, energyBalance, fluidBalance,
                logistics, expectedWarehouse, terminalWarehouse, settledEnergy, consumedFluids);
        return new SettlementResult(balanced, code, Map.copyOf(settledEnergy),
                Map.copyOf(consumedFluids), warehouseBalance.unaccounted(),
                warehouseBalance.duplicateWithdrawals(), warehouseBalance.duplicateReturns(),
                logistics.duplicateCompletions(), logistics.unaccountedCarriedItems(),
                logistics.privateItemsTouched(), energyBalance.duplicateSettlements(),
                fluidBalance.duplicateWithdrawals(), fluidBalance.duplicateReturns());
    }

    private static String firstFailure(
            IndustrialResourceBindingV1 binding,
            WarehouseReservationSystem.WarehouseBalanceReport warehouse,
            ProjectEnergyLedger.BalanceReport energy,
            ProjectFluidLedger.BalanceReport fluid,
            EntityLogisticsSettlementV1 logistics,
            Map<ResourceId, Long> expectedWarehouse,
            Map<ResourceId, Long> terminalWarehouse,
            Map<ResourceId, Long> settledEnergy,
            Map<FluidIdentity, Long> consumedFluids) {
        if (!warehouse.balanced() || warehouse.outstanding() != 0) return "WAREHOUSE_LEDGER_UNBALANCED";
        if (!terminalWarehouse.equals(expectedWarehouse)) return "WAREHOUSE_SETTLEMENT_MISMATCH";
        if (!logistics.exactlySettles(binding.entityLogistics())) {
            return "ENTITY_LOGISTICS_SETTLEMENT_MISMATCH";
        }
        if (!energy.balanced()) return "ENERGY_LEDGER_UNBALANCED";
        if (!fluid.balanced() || fluid.outstandingMb() != 0) return "FLUID_LEDGER_UNBALANCED";
        if (!settledEnergy.equals(binding.plannedEnergy())) return "ENERGY_SETTLEMENT_MISMATCH";
        if (!consumedFluids.equals(binding.plannedFluids())) return "FLUID_SETTLEMENT_MISMATCH";
        return "RESOURCE_LEDGER_UNBALANCED";
    }

    public record RecoveryResult(boolean ready, String code, String bindingFingerprint) {
        public RecoveryResult {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(bindingFingerprint, "bindingFingerprint");
            if (ready == !code.equals("RESOURCE_BINDING_READY")
                    || ready == bindingFingerprint.isEmpty()) {
                throw new IllegalArgumentException("resource recovery result is inconsistent");
            }
        }
        static RecoveryResult ready(String fingerprint) {
            return new RecoveryResult(true, "RESOURCE_BINDING_READY", fingerprint);
        }
        static RecoveryResult paused(String code) {
            return new RecoveryResult(false, code, "");
        }
    }

    public record SettlementResult(
            boolean balanced,
            String code,
            Map<ResourceId, Long> settledEnergy,
            Map<FluidIdentity, Long> consumedFluids,
            long unaccountedWarehouseResources,
            int duplicateWarehouseWithdrawals,
            int duplicateWarehouseReturns,
            int duplicateLogisticsCompletions,
            long unaccountedCarriedItems,
            long privateItemsTouched,
            int duplicateEnergySettlements,
            int duplicateFluidWithdrawals,
            int duplicateFluidReturns) {
        public SettlementResult {
            Objects.requireNonNull(code, "code");
            settledEnergy = Map.copyOf(Objects.requireNonNull(settledEnergy, "settledEnergy"));
            consumedFluids = Map.copyOf(Objects.requireNonNull(consumedFluids, "consumedFluids"));
            if (unaccountedWarehouseResources < 0 || duplicateWarehouseWithdrawals < 0
                    || duplicateWarehouseReturns < 0 || duplicateLogisticsCompletions < 0
                    || unaccountedCarriedItems < 0 || privateItemsTouched < 0
                    || duplicateEnergySettlements < 0
                    || duplicateFluidWithdrawals < 0 || duplicateFluidReturns < 0
                    || balanced == !code.equals("RESOURCE_LEDGER_BALANCED")) {
                throw new IllegalArgumentException("resource settlement result is inconsistent");
            }
        }

        public IndustrialCompletionReportV1 completionReport(
                Map<ResourceId, Long> plannedMaterials,
                Map<ResourceId, Long> withdrawnMaterials,
                Map<ResourceId, Long> consumedMaterials,
                Map<ResourceId, Long> returnedMaterials,
                Map<ResourceId, Long> outputs,
                long salvageTransferred,
                long duplicateOutputs,
                boolean baselineRestored,
                String baselineHash) {
            if (!balanced) throw new IllegalStateException("unbalanced resources cannot complete an order");
            LinkedHashMap<ResourceId, Long> fluids = new LinkedHashMap<>();
            consumedFluids.forEach((identity, amount) -> fluids.put(identity.fluidId(), amount));
            return IndustrialCompletionReportV1.create(plannedMaterials, withdrawnMaterials,
                    consumedMaterials, returnedMaterials, settledEnergy, fluids, outputs,
                    salvageTransferred, duplicateWarehouseWithdrawals, duplicateWarehouseReturns,
                    duplicateEnergySettlements, duplicateOutputs,
                    Math.addExact(unaccountedWarehouseResources, unaccountedCarriedItems),
                    privateItemsTouched, baselineRestored, baselineHash);
        }
    }
}
