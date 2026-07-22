package dev.stevecreate.agent.core.placement;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable complete result from one bounded basic placement evaluation. */
public record PlacementFeasibilityReport(
        int targetCount,
        List<PlacementConflict> conflicts) {

    public PlacementFeasibilityReport {
        if (targetCount < 1 || targetCount > BasicPlacementFeasibility.MAX_TARGETS) {
            throw new IllegalArgumentException("Placement target count is outside the fixed bound");
        }
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        if (conflicts.size() > BasicPlacementFeasibility.MAX_CONFLICTS
                || conflicts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Placement conflict report exceeds its fixed bound");
        }
    }

    public boolean feasible() {
        return conflicts.isEmpty();
    }

    public List<BlockPos3i> conflictingPositions() {
        LinkedHashSet<BlockPos3i> positions = new LinkedHashSet<>();
        conflicts.forEach(conflict -> positions.add(conflict.position()));
        return List.copyOf(positions);
    }
}
