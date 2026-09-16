package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ProjectEnergyLedgerSnapshot;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedgerSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSnapshot;
import java.util.Objects;
import java.util.Optional;

/** One durable IPO-03 checkpoint; live physical graphs must be re-registered after reload. */
public record IndustrialResourceCheckpointV1(
        ResourceId projectId,
        String bindingFingerprint,
        WarehouseReservationSnapshot warehouse,
        ProjectEnergyLedgerSnapshot energy,
        ProjectFluidLedgerSnapshot fluid,
        Optional<EntityLogisticsSettlementV1> logisticsSettlement,
        long savedTick) {
    public IndustrialResourceCheckpointV1 {
        Objects.requireNonNull(projectId, "projectId");
        if (bindingFingerprint == null || !bindingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("industrial resource binding fingerprint is invalid");
        }
        Objects.requireNonNull(warehouse, "warehouse");
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(fluid, "fluid");
        logisticsSettlement = Objects.requireNonNull(logisticsSettlement, "logisticsSettlement");
        if (savedTick < 0 || energy.lastTick() > savedTick || fluid.lastTick() > savedTick) {
            throw new IllegalArgumentException("industrial resource checkpoint tick is invalid");
        }
    }
}
