package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import java.util.Objects;
import java.util.Optional;

/** Restored resource ledgers after exact live graph reconciliation; contains no mutation adapter. */
public record IndustrialResourceRuntimeV1(
        IndustrialResourceBindingV1 binding,
        WarehouseReservationSystem warehouse,
        ProjectEnergyLedger energy,
        ProjectFluidLedger fluid,
        Optional<EntityLogisticsSettlementV1> logisticsSettlement,
        long restoredTick) {
    public IndustrialResourceRuntimeV1 {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(warehouse, "warehouse");
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(fluid, "fluid");
        logisticsSettlement = Objects.requireNonNull(logisticsSettlement, "logisticsSettlement");
        if (restoredTick < 0) throw new IllegalArgumentException("restored tick is invalid");
    }

    public IndustrialResourceCheckpointV1 checkpoint(long tick) {
        if (tick < restoredTick) throw new IllegalArgumentException("checkpoint tick moved backwards");
        return new IndustrialResourceCheckpointV1(binding.projectId(), binding.fingerprint(),
                warehouse.snapshot(), energy.snapshot(), fluid.snapshot(), logisticsSettlement, tick);
    }

    public static RestoreResult restore(
            IndustrialResourceBindingV1 binding,
            IndustrialResourceCheckpointV1 checkpoint,
            GlobalInventoryGraph observedWarehouse,
            EntityLogisticsBindingV1 observedLogistics,
            ElectricalNetworkGraph observedElectrical,
            FluidNetworkGraph observedFluid,
            long tick) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!checkpoint.projectId().equals(binding.projectId())
                || !checkpoint.bindingFingerprint().equals(binding.fingerprint())) {
            return RestoreResult.refused("RESOURCE_BINDING_CHECKPOINT_DRIFT");
        }
        if (tick < checkpoint.savedTick()) return RestoreResult.refused("RESOURCE_TICK_REGRESSION");
        var reconciled = IndustrialResourceRecoveryV1.reconcile(binding, observedWarehouse,
                observedLogistics, observedElectrical, observedFluid);
        if (!reconciled.ready()) return RestoreResult.refused(reconciled.code());
        if (!warehouseContentsMatch(binding, checkpoint, observedWarehouse)) {
            return RestoreResult.refused("WAREHOUSE_CONTENTS_DRIFT");
        }
        if (!energyContentsMatch(binding, checkpoint, observedElectrical)) {
            return RestoreResult.refused("ELECTRICAL_CONTENTS_DRIFT");
        }
        if (!fluidContentsMatch(binding, checkpoint, observedFluid)) {
            return RestoreResult.refused("FLUID_CONTENTS_DRIFT");
        }
        try {
            return RestoreResult.restored(new IndustrialResourceRuntimeV1(binding,
                    WarehouseReservationSystem.restore(observedWarehouse, checkpoint.warehouse()),
                    ProjectEnergyLedger.restore(checkpoint.energy()),
                    ProjectFluidLedger.restore(checkpoint.fluid()),
                    checkpoint.logisticsSettlement(), tick));
        } catch (IllegalArgumentException failure) {
            return RestoreResult.refused("RESOURCE_LEDGER_CHECKPOINT_INVALID");
        }
    }

    private static boolean warehouseContentsMatch(
            IndustrialResourceBindingV1 binding,
            IndustrialResourceCheckpointV1 checkpoint,
            GlobalInventoryGraph observed) {
        for (var initialEndpoint : binding.warehouse().endpoints().values()) {
            var actualEndpoint = observed.endpoints().get(initialEndpoint.endpointId());
            if (actualEndpoint == null) return false;
            java.util.Map<dev.stevecreate.agent.core.warehouse.WarehouseResourceKey, Long> delta =
                    new java.util.LinkedHashMap<>();
            checkpoint.warehouse().allocations().values().stream()
                    .filter(row -> row.endpointId().equals(initialEndpoint.endpointId())
                            && (row.status() == dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus.WITHDRAWN
                            || row.status() == dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus.DELIVERED
                            || row.status() == dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus.CONSUMED
                            || row.status() == dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus.RETURN_PENDING))
                    .forEach(row -> delta.merge(row.resource(), row.quantity(), Math::addExact));
            java.util.Set<dev.stevecreate.agent.core.warehouse.WarehouseResourceKey> resources =
                    new java.util.HashSet<>(initialEndpoint.contents().keySet());
            resources.addAll(actualEndpoint.contents().keySet());
            resources.addAll(delta.keySet());
            if (resources.stream().anyMatch(resource ->
                    actualEndpoint.contents().getOrDefault(resource, 0L)
                            != initialEndpoint.contents().getOrDefault(resource, 0L)
                            - delta.getOrDefault(resource, 0L))) return false;
        }
        return true;
    }

    private static boolean energyContentsMatch(
            IndustrialResourceBindingV1 binding,
            IndustrialResourceCheckpointV1 checkpoint,
            ElectricalNetworkGraph observed) {
        if (binding.electricalNetwork().isEmpty()) return observed == null;
        ElectricalNetworkGraph initial = binding.electricalNetwork().orElseThrow();
        for (var transaction : checkpoint.energy().transactions().values()) {
            var initialNode = initial.nodes().get(transaction.sourceEndpointId());
            var actualNode = observed.nodes().get(transaction.sourceEndpointId());
            if (initialNode == null || actualNode == null) return false;
            if (initialNode.kind() == dev.stevecreate.agent.core.electrical.ElectricalNodeKind.STORAGE) {
                long delta = transaction.state()
                        == dev.stevecreate.agent.core.electrical.EnergyTransactionState.SETTLED
                        ? transaction.amountFe() : 0;
                if (actualNode.storedEnergy() != initialNode.storedEnergy() - delta) return false;
            }
        }
        return true;
    }

    private static boolean fluidContentsMatch(
            IndustrialResourceBindingV1 binding,
            IndustrialResourceCheckpointV1 checkpoint,
            FluidNetworkGraph observed) {
        if (binding.fluidNetwork().isEmpty()) return observed == null;
        FluidNetworkGraph initial = binding.fluidNetwork().orElseThrow();
        java.util.Map<dev.stevecreate.agent.core.model.ResourceId, Long> delta =
                new java.util.LinkedHashMap<>();
        checkpoint.fluid().transactions().values().stream()
                .filter(row -> row.state() == dev.stevecreate.agent.core.fluid.FluidTransactionState.WITHDRAWN
                        || row.state() == dev.stevecreate.agent.core.fluid.FluidTransactionState.DELIVERED
                        || row.state() == dev.stevecreate.agent.core.fluid.FluidTransactionState.CONSUMED
                        || row.state() == dev.stevecreate.agent.core.fluid.FluidTransactionState.RETURN_PENDING)
                .forEach(row -> delta.merge(row.sourceEndpointId(), row.amountMb(), Math::addExact));
        for (var entry : delta.entrySet()) {
            var initialNode = initial.nodes().get(entry.getKey());
            var actualNode = observed.nodes().get(entry.getKey());
            if (initialNode == null || actualNode == null
                    || actualNode.amountMb() != initialNode.amountMb() - entry.getValue()
                    || (actualNode.amountMb() > 0
                    && !actualNode.contents().equals(initialNode.contents()))) return false;
        }
        return true;
    }

    public record RestoreResult(
            boolean restored,
            String code,
            Optional<IndustrialResourceRuntimeV1> runtime) {
        public RestoreResult {
            Objects.requireNonNull(code, "code");
            runtime = Objects.requireNonNull(runtime, "runtime");
            if (restored == !code.equals("RESOURCE_RUNTIME_RESTORED")
                    || restored != runtime.isPresent()) {
                throw new IllegalArgumentException("resource restore result is inconsistent");
            }
        }
        static RestoreResult restored(IndustrialResourceRuntimeV1 runtime) {
            return new RestoreResult(true, "RESOURCE_RUNTIME_RESTORED", Optional.of(runtime));
        }
        static RestoreResult refused(String code) {
            return new RestoreResult(false, code, Optional.empty());
        }
    }
}
