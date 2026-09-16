package dev.stevecreate.agent.core.execution.composite;

import dev.stevecreate.agent.core.execution.construction.ReservationStatus;
import dev.stevecreate.agent.core.execution.construction.SharedInfrastructureReservation;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reference-counted ownership updates for verified infrastructure shared by independent lines. */
public final class SharedInfrastructureLeaseRegistry {
    private final Map<ResourceId, SharedInfrastructureReservation> reservations =
            new LinkedHashMap<>();

    public SharedInfrastructureLeaseRegistry(SharedInfrastructureReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (reservation.status() != ReservationStatus.ACTIVE) {
            throw new IllegalArgumentException("Shared infrastructure must begin active");
        }
        reservations.put(reservation.reservationId(), reservation);
    }

    public SharedInfrastructureReservation acquire(
            ResourceId reservationId, ResourceId sessionId, long tick) {
        Objects.requireNonNull(sessionId, "sessionId");
        SharedInfrastructureReservation current = require(reservationId);
        if (current.status() != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Shared infrastructure is not active");
        }
        Set<ResourceId> owners = new LinkedHashSet<>(current.owningSessionIds());
        if (!owners.add(sessionId)) return current;
        SharedInfrastructureReservation updated = copy(
                current, owners, ReservationStatus.ACTIVE, tick);
        reservations.put(reservationId, updated);
        return updated;
    }

    public ReleaseResult release(
            ResourceId reservationId, ResourceId sessionId, long tick) {
        Objects.requireNonNull(sessionId, "sessionId");
        SharedInfrastructureReservation current = require(reservationId);
        Set<ResourceId> owners = new LinkedHashSet<>(current.owningSessionIds());
        boolean removed = owners.remove(sessionId);
        ReservationStatus status = owners.isEmpty()
                ? ReservationStatus.RELEASED : ReservationStatus.ACTIVE;
        SharedInfrastructureReservation updated = removed
                ? copy(current, owners, status, tick) : current;
        reservations.put(reservationId, updated);
        return new ReleaseResult(updated, removed, owners.isEmpty());
    }

    public SharedInfrastructureReservation reservation(ResourceId reservationId) {
        return require(reservationId);
    }

    private SharedInfrastructureReservation require(ResourceId reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        SharedInfrastructureReservation value = reservations.get(reservationId);
        if (value == null) throw new IllegalArgumentException("Unknown shared reservation " + reservationId);
        return value;
    }

    private static SharedInfrastructureReservation copy(
            SharedInfrastructureReservation current,
            Set<ResourceId> owners,
            ReservationStatus status,
            long tick) {
        if (tick < current.updatedTick()) {
            throw new IllegalArgumentException("Shared infrastructure tick moved backwards");
        }
        return new SharedInfrastructureReservation(
                current.reservationId(), current.verifiedPhysicalPlanId(),
                current.infrastructureId(), owners, current.positions(), status,
                Math.addExact(current.generation(), 1), tick);
    }

    public record ReleaseResult(
            SharedInfrastructureReservation reservation,
            boolean ownerReleased,
            boolean cleanupAllowed) {
        public ReleaseResult { Objects.requireNonNull(reservation, "reservation"); }
    }
}
