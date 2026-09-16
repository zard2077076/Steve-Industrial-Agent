package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.Objects;

/** Bounded, loader-neutral facts used to rank a server-observed site candidate. */
public record PlacementCandidateFacts(
        String candidateId,
        BlockPos3i anchor,
        QuarterTurn orientation,
        LayoutVariant layoutVariant,
        int protectedConflicts,
        int unknownConflicts,
        int containerConflicts,
        int hazardConflicts,
        int demolitionCount,
        int terrainChanges,
        int materialCost,
        int pathBlockedCells,
        int botWorkBlockedCells,
        int logisticsBlockedCells,
        int kineticBlockedCells,
        int futureExpansionPenalty,
        int regionBoundaryViolations,
        int executionRisk,
        int translationDistance,
        int elevationChange,
        boolean currentSelection) {
    public PlacementCandidateFacts {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(layoutVariant, "layoutVariant");
        if (candidateId.isBlank() || candidateId.length() > 96) {
            throw new IllegalArgumentException("candidate ID is invalid");
        }
        int[] counts = {protectedConflicts, unknownConflicts, containerConflicts,
                hazardConflicts, demolitionCount, terrainChanges, materialCost,
                pathBlockedCells, botWorkBlockedCells, logisticsBlockedCells,
                kineticBlockedCells, futureExpansionPenalty, regionBoundaryViolations,
                executionRisk, translationDistance, elevationChange};
        if (java.util.Arrays.stream(counts).anyMatch(value -> value < 0)
                || executionRisk > 100 || translationDistance > 128 || elevationChange > 1) {
            throw new IllegalArgumentException("candidate facts exceed their bounds");
        }
    }

    public boolean hardConflictFree() {
        return protectedConflicts == 0 && unknownConflicts == 0
                && containerConflicts == 0 && hazardConflicts == 0
                && regionBoundaryViolations == 0;
    }
}
