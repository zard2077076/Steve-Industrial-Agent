package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Exclusive worker occupation of one verified task work position. */
public record WorkPositionReservation(
        ResourceId reservationId,
        ResourceId graphId,
        ResourceId taskId,
        ResourceId assignmentId,
        ResourceId workerId,
        BlockPos3i position,
        ReservationStatus status,
        long generation,
        long acquiredTick,
        long expiresTick) {
    public WorkPositionReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(status, "status");
        if (generation < 0 || acquiredTick < 0 || expiresTick < acquiredTick
                || (status == ReservationStatus.ACTIVE && expiresTick == acquiredTick)) {
            throw new IllegalArgumentException("Work-position reservation generation/ticks are invalid");
        }
    }

    public boolean activeAt(long tick) {
        return status == ReservationStatus.ACTIVE && tick >= acquiredTick && tick < expiresTick;
    }
}
