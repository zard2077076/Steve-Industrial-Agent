package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Exclusive lease for one verified construction/work position. */
public record PlacementReservation(
        ResourceId reservationId,
        ResourceId verifiedPhysicalPlanId,
        ResourceId sessionId,
        ResourceId taskId,
        BlockPos3i position,
        ExecutionMode mode,
        Optional<ResourceId> workerId,
        ReservationStatus status,
        long generation,
        long updatedTick,
        long expiresTick) {
    public PlacementReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(mode, "mode");
        workerId = Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(status, "status");
        if (mode == ExecutionMode.BOTS && workerId.isEmpty()) {
            throw new IllegalArgumentException("Bot placement reservations require a worker identity");
        }
        if (mode == ExecutionMode.DIRECT && workerId.isPresent()) {
            throw new IllegalArgumentException("Direct placement reservations cannot claim a Bot worker identity");
        }
        if (generation < 0 || updatedTick < 0 || expiresTick < updatedTick) {
            throw new IllegalArgumentException("Placement reservation generation/ticks are invalid");
        }
        if (status == ReservationStatus.ACTIVE && expiresTick == updatedTick) {
            throw new IllegalArgumentException("An active placement reservation must expire in the future");
        }
    }

    public boolean activeAt(long tick) {
        return status == ReservationStatus.ACTIVE && tick >= updatedTick && tick < expiresTick;
    }
}
