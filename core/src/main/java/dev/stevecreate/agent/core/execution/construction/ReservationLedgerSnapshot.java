package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

/** Immutable reload payload for all resource-bearing Bot state. */
public record ReservationLedgerSnapshot(
        Map<ResourceId, MaterialSource> sources,
        Map<ResourceId, MaterialReservation> reservations,
        Map<ResourceId, BotInventory> inventories,
        Map<ResourceId, MaterialDelivery> deliveries,
        MaterialReturnPolicy returnPolicy,
        long generation,
        long savedTick) {
    public ReservationLedgerSnapshot {
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        reservations = Map.copyOf(Objects.requireNonNull(reservations, "reservations"));
        inventories = Map.copyOf(Objects.requireNonNull(inventories, "inventories"));
        deliveries = Map.copyOf(Objects.requireNonNull(deliveries, "deliveries"));
        if (sources.size() > ConstructionContractValues.MAX_IDS
                || reservations.size() > ConstructionContractValues.MAX_IDS
                || inventories.size() > BotFleetCoordinator.MAX_WORKERS
                || deliveries.size() > ConstructionContractValues.MAX_IDS) {
            throw new IllegalArgumentException("Ledger snapshot exceeds a bounded collection limit");
        }
        sources.forEach((id, source) -> requireIdentity(id, source.sourceId(), "source"));
        reservations.forEach((id, reservation) ->
                requireIdentity(id, reservation.reservationId(), "reservation"));
        inventories.forEach((id, inventory) ->
                requireIdentity(id, inventory.workerId(), "inventory"));
        deliveries.forEach((id, delivery) ->
                requireIdentity(id, delivery.deliveryId(), "delivery"));
        Objects.requireNonNull(returnPolicy, "returnPolicy");
        if (generation < 0 || savedTick < 0) {
            throw new IllegalArgumentException("Ledger snapshot generation/tick must be non-negative");
        }
    }

    private static void requireIdentity(ResourceId key, ResourceId value, String kind) {
        if (!Objects.requireNonNull(key, kind + " key").equals(value)) {
            throw new IllegalArgumentException("Ledger " + kind + " key does not match its identity");
        }
    }
}
