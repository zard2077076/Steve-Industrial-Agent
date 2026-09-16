package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-authoritative atomic material ledger for bounded test/dedicated sources and Bot inventory.
 *
 * <p>No method reaches into a world, player inventory or storage network. A loader adapter first
 * performs its exact read/write and then commits the matching bounded transition on the same
 * authoritative thread.</p>
 */
public final class ReservationLedger {
    private final Map<ResourceId, MaterialSource> sources = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialReservation> reservations = new LinkedHashMap<>();
    private final Map<ResourceId, BotInventory> inventories = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialDelivery> deliveries = new LinkedHashMap<>();
    private final MaterialReturnPolicy returnPolicy;
    private long generation;
    private long lastTick;

    public ReservationLedger(MaterialReturnPolicy returnPolicy) {
        this.returnPolicy = Objects.requireNonNull(returnPolicy, "returnPolicy");
    }

    public synchronized void registerSource(MaterialSource source) {
        Objects.requireNonNull(source, "source");
        if (sources.size() >= ConstructionContractValues.MAX_IDS) {
            throw new IllegalStateException("Material source registry reached its bounded limit");
        }
        if (sources.putIfAbsent(source.sourceId(), source) != null) {
            throw new IllegalArgumentException("Duplicate material source " + source.sourceId());
        }
    }

    public synchronized void registerInventory(BotInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        if (inventories.size() >= BotFleetCoordinator.MAX_WORKERS) {
            throw new IllegalStateException("Bot inventory registry reached the fleet bound");
        }
        if (inventories.putIfAbsent(inventory.workerId(), inventory) != null) {
            throw new IllegalArgumentException("Duplicate Bot inventory " + inventory.workerId());
        }
    }

    public synchronized MaterialLedgerResult reserve(MaterialReservationRequest request) {
        Objects.requireNonNull(request, "request");
        requireCurrentTick(request.requestedTick());
        if (reservations.size() >= ConstructionContractValues.MAX_IDS) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material reservation registry reached its bounded limit");
        }
        if (reservations.containsKey(request.reservationId())) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material reservation identity already exists: " + request.reservationId());
        }
        MaterialSource source = sources.get(request.sourceId());
        if (source == null || !source.authorizes(request.sessionId())) {
            return failure(ConstructionFailureCode.MATERIAL_SOURCE_FORBIDDEN,
                    "Material source is absent or does not authorize session " + request.sessionId());
        }
        if (!source.loaded()) {
            return failure(ConstructionFailureCode.CHUNK_NOT_LOADED,
                    "Authorized material source is not loaded: " + source.sourceId());
        }
        long available = uncommittedQuantity(source, request.resourceId());
        if (available < request.quantity()) {
            return failure(ConstructionFailureCode.MATERIAL_INSUFFICIENT,
                    "Material source has " + available + " uncommitted but requires "
                            + request.quantity());
        }
        MaterialReservation reservation = new MaterialReservation(
                request.reservationId(),
                request.sessionId(),
                request.taskId(),
                source.sourceId(),
                source.scope(),
                request.resourceId(),
                request.quantity(),
                request.quantity(),
                0,
                0,
                0,
                ReservationStatus.ACTIVE,
                nextGeneration(),
                request.requestedTick());
        reservations.put(reservation.reservationId(), reservation);
        lastTick = request.requestedTick();
        return success(reservation, source, Optional.empty(), Optional.empty());
    }

    public synchronized MaterialLedgerResult withdraw(
            ResourceId reservationId,
            ResourceId deliveryId,
            ResourceId workerId,
            long quantity,
            long tick) {
        if (quantity < 1 || tick < 0) {
            throw new IllegalArgumentException("Withdrawal quantity/tick is invalid");
        }
        requireCurrentTick(tick);
        MaterialReservation reservation = reservations.get(
                Objects.requireNonNull(reservationId, "reservationId"));
        if (reservation == null || reservation.status() != ReservationStatus.ACTIVE) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material reservation is absent or inactive: " + reservationId);
        }
        Objects.requireNonNull(deliveryId, "deliveryId");
        if (deliveries.containsKey(deliveryId)) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material delivery identity already exists: " + deliveryId);
        }
        if (deliveries.size() >= ConstructionContractValues.MAX_IDS) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material delivery registry reached its bounded limit");
        }
        MaterialSource source = sources.get(reservation.sourceId());
        BotInventory inventory = inventories.get(Objects.requireNonNull(workerId, "workerId"));
        if (source == null || inventory == null || !source.loaded()) {
            return failure(ConstructionFailureCode.WORKER_UNAVAILABLE,
                    "Source or Bot inventory is unavailable for withdrawal");
        }
        long reservable = reservation.reservedQuantity() - reservation.withdrawnQuantity();
        if (quantity > reservable || quantity > source.available(reservation.resourceId())) {
            return failure(ConstructionFailureCode.MATERIAL_INSUFFICIENT,
                    "Withdrawal exceeds reserved or physical source quantity");
        }
        if (quantity > inventory.remainingCapacity()) {
            return failure(ConstructionFailureCode.MATERIAL_INSUFFICIENT,
                    "Bot inventory lacks capacity for the withdrawal");
        }
        MaterialSource updatedSource = sourceWithDelta(
                source, reservation.resourceId(), -quantity, tick);
        BotInventory updatedInventory = inventoryWithDelta(
                inventory, reservation.resourceId(), quantity, tick);
        MaterialReservation updatedReservation = updateReservation(
                reservation,
                reservation.withdrawnQuantity() + quantity,
                reservation.deliveredQuantity(),
                reservation.returnedQuantity(),
                reservation.status(),
                tick);
        MaterialDelivery delivery = new MaterialDelivery(
                deliveryId,
                reservation.reservationId(),
                reservation.sessionId(),
                reservation.taskId(),
                workerId,
                reservation.resourceId(),
                quantity,
                0,
                0,
                MaterialDeliveryStatus.CARRYING,
                nextGeneration(),
                tick);
        sources.put(updatedSource.sourceId(), updatedSource);
        inventories.put(updatedInventory.workerId(), updatedInventory);
        reservations.put(updatedReservation.reservationId(), updatedReservation);
        deliveries.put(delivery.deliveryId(), delivery);
        lastTick = tick;
        return success(updatedReservation, updatedSource,
                Optional.of(updatedInventory), Optional.of(delivery));
    }

    public synchronized MaterialLedgerResult deliver(
            ResourceId deliveryId,
            long quantity,
            long tick) {
        return settle(deliveryId, quantity, tick, false);
    }

    public synchronized MaterialLedgerResult returnToSource(
            ResourceId deliveryId,
            long quantity,
            long tick) {
        return settle(deliveryId, quantity, tick, true);
    }

    public synchronized MaterialLedgerResult cancel(ResourceId reservationId, long tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        requireCurrentTick(tick);
        MaterialReservation reservation = reservations.get(
                Objects.requireNonNull(reservationId, "reservationId"));
        if (reservation == null || reservation.status() != ReservationStatus.ACTIVE) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Material reservation is absent or inactive: " + reservationId);
        }
        if (reservation.outstandingQuantity() > 0) {
            String action = returnPolicy == MaterialReturnPolicy.RETURN_TO_ORIGINAL_SOURCE
                    ? "Return every carried item to its exact source before cancellation"
                    : "Cancellation is refused while any Bot carries the material";
            return failure(ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION,
                    action);
        }
        MaterialSource source = sources.get(reservation.sourceId());
        MaterialReservation released = updateReservation(
                reservation,
                reservation.withdrawnQuantity(),
                reservation.deliveredQuantity(),
                reservation.returnedQuantity(),
                ReservationStatus.RELEASED,
                tick);
        reservations.put(released.reservationId(), released);
        lastTick = tick;
        return success(released, source, Optional.empty(), Optional.empty());
    }

    public synchronized ReservationLedgerSnapshot snapshot(long tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        requireCurrentTick(tick);
        lastTick = tick;
        return new ReservationLedgerSnapshot(
                sources, reservations, inventories, deliveries, returnPolicy, generation, tick);
    }

    public static ReservationLedger restore(ReservationLedgerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ReservationLedger ledger = new ReservationLedger(snapshot.returnPolicy());
        ledger.sources.putAll(snapshot.sources());
        ledger.reservations.putAll(snapshot.reservations());
        ledger.inventories.putAll(snapshot.inventories());
        ledger.deliveries.putAll(snapshot.deliveries());
        ledger.generation = snapshot.generation();
        ledger.lastTick = snapshot.savedTick();
        ledger.validateRestoredState();
        return ledger;
    }

    public synchronized Map<ResourceId, MaterialReservation> reservations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reservations));
    }

    public synchronized Map<ResourceId, MaterialDelivery> deliveries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(deliveries));
    }

    public synchronized Map<ResourceId, BotInventory> inventories() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(inventories));
    }

    private MaterialLedgerResult settle(
            ResourceId deliveryId,
            long quantity,
            long tick,
            boolean returning) {
        if (quantity < 1 || tick < 0) {
            throw new IllegalArgumentException("Settlement quantity/tick is invalid");
        }
        requireCurrentTick(tick);
        MaterialDelivery delivery = deliveries.get(
                Objects.requireNonNull(deliveryId, "deliveryId"));
        if (delivery == null || delivery.status() != MaterialDeliveryStatus.CARRYING
                || quantity > delivery.outstandingQuantity()) {
            return failure(ConstructionFailureCode.RESERVATION_CONFLICT,
                    "Delivery is absent, settled or lacks the requested outstanding quantity");
        }
        MaterialReservation reservation = reservations.get(delivery.reservationId());
        BotInventory inventory = inventories.get(delivery.workerId());
        MaterialSource source = sources.get(deliverySourceId(delivery));
        if (reservation == null || inventory == null || source == null
                || inventory.quantity(delivery.resourceId()) < quantity) {
            return failure(ConstructionFailureCode.WORLD_STATE_CHANGED,
                    "Ledger settlement no longer matches reservation/source/Bot inventory state");
        }
        long delivered = delivery.deliveredQuantity() + (returning ? 0 : quantity);
        long returned = delivery.returnedQuantity() + (returning ? quantity : 0);
        long remaining = delivery.withdrawnQuantity() - delivered - returned;
        MaterialDelivery updatedDelivery = new MaterialDelivery(
                delivery.deliveryId(),
                delivery.reservationId(),
                delivery.sessionId(),
                delivery.taskId(),
                delivery.workerId(),
                delivery.resourceId(),
                delivery.withdrawnQuantity(),
                delivered,
                returned,
                remaining == 0 ? MaterialDeliveryStatus.SETTLED : MaterialDeliveryStatus.CARRYING,
                nextGeneration(),
                tick);
        BotInventory updatedInventory = inventoryWithDelta(
                inventory, delivery.resourceId(), -quantity, tick);
        MaterialSource updatedSource = returning
                ? sourceWithDelta(source, delivery.resourceId(), quantity, tick)
                : source;
        MaterialReservation updatedReservation = updateReservation(
                reservation,
                reservation.withdrawnQuantity(),
                reservation.deliveredQuantity() + (returning ? 0 : quantity),
                reservation.returnedQuantity() + (returning ? quantity : 0),
                reservation.status(),
                tick);
        deliveries.put(updatedDelivery.deliveryId(), updatedDelivery);
        inventories.put(updatedInventory.workerId(), updatedInventory);
        sources.put(updatedSource.sourceId(), updatedSource);
        reservations.put(updatedReservation.reservationId(), updatedReservation);
        lastTick = tick;
        return success(updatedReservation, updatedSource,
                Optional.of(updatedInventory), Optional.of(updatedDelivery));
    }

    private ResourceId deliverySourceId(MaterialDelivery delivery) {
        MaterialReservation reservation = reservations.get(delivery.reservationId());
        return reservation == null ? delivery.reservationId() : reservation.sourceId();
    }

    private long uncommittedQuantity(MaterialSource source, ResourceId resourceId) {
        long committed = 0;
        for (MaterialReservation reservation : reservations.values()) {
            if (reservation.status() == ReservationStatus.ACTIVE
                    && reservation.sourceId().equals(source.sourceId())
                    && reservation.resourceId().equals(resourceId)) {
                committed = Math.addExact(committed,
                        reservation.reservedQuantity() - reservation.withdrawnQuantity());
            }
        }
        return Math.subtractExact(source.available(resourceId), committed);
    }

    private void validateRestoredState() {
        long maximumGeneration = 0;
        for (MaterialSource source : sources.values()) {
            maximumGeneration = Math.max(maximumGeneration, source.generation());
            requireSavedTick(source.updatedTick());
        }
        for (BotInventory inventory : inventories.values()) {
            maximumGeneration = Math.max(maximumGeneration, inventory.generation());
            requireSavedTick(inventory.updatedTick());
        }
        for (MaterialReservation reservation : reservations.values()) {
            maximumGeneration = Math.max(maximumGeneration, reservation.generation());
            requireSavedTick(reservation.updatedTick());
        }
        for (MaterialDelivery delivery : deliveries.values()) {
            maximumGeneration = Math.max(maximumGeneration, delivery.generation());
            requireSavedTick(delivery.updatedTick());
        }
        if (maximumGeneration > generation) {
            throw new IllegalArgumentException("Restored ledger generation is older than its rows");
        }
        Map<ResourceId, long[]> deliveryTotals = new LinkedHashMap<>();
        Map<ResourceId, Map<ResourceId, Long>> carriedByWorker = new LinkedHashMap<>();
        for (MaterialDelivery delivery : deliveries.values()) {
            MaterialReservation reservation = reservations.get(delivery.reservationId());
            if (reservation == null
                    || !reservation.sessionId().equals(delivery.sessionId())
                    || !reservation.taskId().equals(delivery.taskId())
                    || !reservation.resourceId().equals(delivery.resourceId())) {
                throw new IllegalArgumentException("Delivery does not match its restored reservation");
            }
            long[] totals = deliveryTotals.computeIfAbsent(delivery.reservationId(), ignored -> new long[3]);
            totals[0] = Math.addExact(totals[0], delivery.withdrawnQuantity());
            totals[1] = Math.addExact(totals[1], delivery.deliveredQuantity());
            totals[2] = Math.addExact(totals[2], delivery.returnedQuantity());
            if (delivery.outstandingQuantity() > 0) {
                carriedByWorker.computeIfAbsent(delivery.workerId(), ignored -> new LinkedHashMap<>())
                        .merge(delivery.resourceId(), delivery.outstandingQuantity(), Math::addExact);
            }
        }
        for (MaterialReservation reservation : reservations.values()) {
            MaterialSource source = sources.get(reservation.sourceId());
            if (source == null || source.scope() != reservation.sourceScope()
                    || !source.authorizes(reservation.sessionId())) {
                throw new IllegalArgumentException("Restored reservation source authority is invalid");
            }
            long[] totals = deliveryTotals.getOrDefault(reservation.reservationId(), new long[3]);
            if (totals[0] != reservation.withdrawnQuantity()
                    || totals[1] != reservation.deliveredQuantity()
                    || totals[2] != reservation.returnedQuantity()) {
                throw new IllegalArgumentException("Restored delivery totals do not match reservation arithmetic");
            }
            if (uncommittedQuantity(source, reservation.resourceId()) < 0) {
                throw new IllegalArgumentException("Restored material reservations overcommit a source");
            }
        }
        for (Map.Entry<ResourceId, Map<ResourceId, Long>> worker : carriedByWorker.entrySet()) {
            BotInventory inventory = inventories.get(worker.getKey());
            if (inventory == null) {
                throw new IllegalArgumentException("Outstanding delivery has no restored Bot inventory");
            }
            for (Map.Entry<ResourceId, Long> resource : worker.getValue().entrySet()) {
                if (inventory.quantity(resource.getKey()) < resource.getValue()) {
                    throw new IllegalArgumentException("Restored Bot inventory lacks carried material");
                }
            }
        }
        for (MaterialSource source : sources.values()) {
            for (ResourceId resourceId : source.availableQuantities().keySet()) {
                if (uncommittedQuantity(source, resourceId) < 0) {
                    throw new IllegalArgumentException("Restored material reservations overcommit a source");
                }
            }
        }
    }

    private MaterialSource sourceWithDelta(
            MaterialSource source,
            ResourceId resourceId,
            long delta,
            long tick) {
        Map<ResourceId, Long> quantities = new LinkedHashMap<>(source.availableQuantities());
        long updated = Math.addExact(source.available(resourceId), delta);
        if (updated < 0) throw new IllegalArgumentException("Material source would become negative");
        quantities.put(resourceId, updated);
        return new MaterialSource(
                source.sourceId(), source.scope(), source.accessPosition(),
                source.authorizedSessionIds(), quantities, source.loaded(),
                nextGeneration(), tick);
    }

    private BotInventory inventoryWithDelta(
            BotInventory inventory,
            ResourceId resourceId,
            long delta,
            long tick) {
        Map<ResourceId, Long> quantities = new LinkedHashMap<>(inventory.quantities());
        long updated = Math.addExact(inventory.quantity(resourceId), delta);
        if (updated < 0) throw new IllegalArgumentException("Bot inventory would become negative");
        quantities.put(resourceId, updated);
        return new BotInventory(
                inventory.workerId(), inventory.capacity(), quantities, nextGeneration(), tick);
    }

    private MaterialReservation updateReservation(
            MaterialReservation reservation,
            long withdrawn,
            long delivered,
            long returned,
            ReservationStatus status,
            long tick) {
        return new MaterialReservation(
                reservation.reservationId(),
                reservation.sessionId(),
                reservation.taskId(),
                reservation.sourceId(),
                reservation.sourceScope(),
                reservation.resourceId(),
                reservation.requestedQuantity(),
                reservation.reservedQuantity(),
                withdrawn,
                delivered,
                returned,
                status,
                nextGeneration(),
                tick);
    }

    private long nextGeneration() {
        generation = Math.addExact(generation, 1);
        return generation;
    }

    private void requireCurrentTick(long tick) {
        if (tick < lastTick) {
            throw new IllegalArgumentException("Material ledger tick cannot move backwards");
        }
    }

    private void requireSavedTick(long updatedTick) {
        if (updatedTick > lastTick) {
            throw new IllegalArgumentException("Restored ledger row is newer than its snapshot");
        }
    }

    private static MaterialLedgerResult.Success success(
            MaterialReservation reservation,
            MaterialSource source,
            Optional<BotInventory> inventory,
            Optional<MaterialDelivery> delivery) {
        return new MaterialLedgerResult.Success(reservation, source, inventory, delivery);
    }

    private static MaterialLedgerResult.Failure failure(
            ConstructionFailureCode code,
            String detail) {
        return new MaterialLedgerResult.Failure(code, detail);
    }
}
