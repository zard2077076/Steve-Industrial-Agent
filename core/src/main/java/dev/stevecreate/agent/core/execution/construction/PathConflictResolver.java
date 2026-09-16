package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic no-collision path reservation table; it never teleports or computes a world path. */
public final class PathConflictResolver {
    private final Map<ResourceId, BotPathReservation> reservations = new LinkedHashMap<>();

    public synchronized Optional<PathConflict> reserve(BotPathReservation candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (reservations.containsKey(candidate.pathReservationId())) {
            return Optional.of(new PathConflict(
                    candidate.pathReservationId(), candidate.pathReservationId(),
                    candidate.positions().get(0), candidate.startTick(), "duplicate path reservation"));
        }
        for (BotPathReservation existing : reservations.values()) {
            for (int candidateIndex = 0; candidateIndex < candidate.positions().size(); candidateIndex++) {
                long tick = Math.addExact(candidate.startTick(), candidateIndex);
                long existingOffset = tick - existing.startTick();
                if (existingOffset >= 0 && existingOffset < existing.positions().size()
                        && existing.positions().get((int) existingOffset)
                        .equals(candidate.positions().get(candidateIndex))) {
                    return Optional.of(new PathConflict(
                            candidate.pathReservationId(), existing.pathReservationId(),
                            candidate.positions().get(candidateIndex), tick,
                            "two Bots would occupy the same navigation cell"));
                }
                if (candidateIndex > 0 && existingOffset > 0
                        && existingOffset < existing.positions().size()) {
                    int existingIndex = (int) existingOffset;
                    if (candidate.positions().get(candidateIndex)
                            .equals(existing.positions().get(existingIndex - 1))
                            && candidate.positions().get(candidateIndex - 1)
                            .equals(existing.positions().get(existingIndex))) {
                        return Optional.of(new PathConflict(
                                candidate.pathReservationId(), existing.pathReservationId(),
                                candidate.positions().get(candidateIndex), tick,
                                "two Bots would cross through each other in one tick"));
                    }
                }
            }
        }
        reservations.put(candidate.pathReservationId(), candidate);
        return Optional.empty();
    }

    public synchronized void release(ResourceId pathReservationId) {
        reservations.remove(Objects.requireNonNull(pathReservationId, "pathReservationId"));
    }

    public record PathConflict(
            ResourceId candidateReservationId,
            ResourceId conflictingReservationId,
            BlockPos3i position,
            long tick,
            String detail) {
        public PathConflict {
            Objects.requireNonNull(candidateReservationId, "candidateReservationId");
            Objects.requireNonNull(conflictingReservationId, "conflictingReservationId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(detail, "detail");
            if (tick < 0 || detail.isBlank()) {
                throw new IllegalArgumentException("Path conflict tick/detail is invalid");
            }
        }
    }
}
