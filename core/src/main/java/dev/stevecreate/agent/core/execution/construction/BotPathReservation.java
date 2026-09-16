package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

/** Bounded time-indexed path; each entry is one adjacent, prevalidated navigation cell. */
public record BotPathReservation(
        ResourceId pathReservationId,
        ResourceId assignmentId,
        ResourceId workerId,
        List<BlockPos3i> positions,
        long startTick,
        long generation) {
    public static final int MAX_PATH_CELLS = 4_096;

    public BotPathReservation {
        Objects.requireNonNull(pathReservationId, "pathReservationId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(workerId, "workerId");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        if (positions.isEmpty() || positions.size() > MAX_PATH_CELLS) {
            throw new IllegalArgumentException("Bot path must be bounded and non-empty");
        }
        for (int index = 1; index < positions.size(); index++) {
            BlockPos3i prior = positions.get(index - 1);
            BlockPos3i current = positions.get(index);
            int distance = Math.abs(current.x() - prior.x())
                    + Math.abs(current.y() - prior.y())
                    + Math.abs(current.z() - prior.z());
            if (distance != 1) {
                throw new IllegalArgumentException("Bot path contains a teleport/non-adjacent step");
            }
        }
        if (startTick < 0 || generation < 0) {
            throw new IllegalArgumentException("Bot path generation/tick must be non-negative");
        }
        Math.addExact(startTick, positions.size() - 1L);
    }

    public long endTick() {
        return Math.addExact(startTick, positions.size() - 1L);
    }
}
