package dev.stevecreate.agent.core.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic fail-closed scorer; lower scores are better. */
public final class SmartRelocationAdvisor {
    public static final int MAX_CANDIDATES = 216;

    public List<PlacementCandidateScore> rank(List<PlacementCandidateFacts> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
                || candidates.stream().map(PlacementCandidateFacts::candidateId).distinct().count()
                        != candidates.size()) {
            throw new IllegalArgumentException("candidate set is empty, duplicate or unbounded");
        }
        return candidates.stream().map(this::score)
                .sorted(Comparator.comparing(PlacementCandidateScore::recommendedSafe).reversed()
                        .thenComparingLong(PlacementCandidateScore::score)
                        .thenComparing(value -> value.candidate().candidateId()))
                .toList();
    }

    private PlacementCandidateScore score(PlacementCandidateFacts value) {
        long hard = (long) value.protectedConflicts() * 1_000_000_000L
                + (long) value.containerConflicts() * 900_000_000L
                + (long) value.unknownConflicts() * 800_000_000L
                + (long) value.hazardConflicts() * 700_000_000L
                + (long) value.regionBoundaryViolations() * 600_000_000L;
        long soft = (long) value.demolitionCount() * 1_000_000L
                + (long) value.terrainChanges() * 250_000L
                + (long) value.botWorkBlockedCells() * 100_000L
                + (long) value.pathBlockedCells() * 80_000L
                + (long) value.logisticsBlockedCells() * 60_000L
                + (long) value.kineticBlockedCells() * 60_000L
                + (long) value.futureExpansionPenalty() * 20_000L
                + (long) value.materialCost() * 1_000L
                + (long) value.executionRisk() * 100L
                + (long) value.translationDistance() * 10L
                + value.elevationChange() * 5L;
        ArrayList<String> reasons = new ArrayList<>();
        if (value.hardConflictFree()) reasons.add("hard_conflicts=0");
        else {
            if (value.protectedConflicts() > 0) reasons.add("protected_conflict");
            if (value.containerConflicts() > 0) reasons.add("container_conflict");
            if (value.unknownConflicts() > 0) reasons.add("unknown_conflict");
            if (value.hazardConflicts() > 0) reasons.add("hazard_conflict");
            if (value.regionBoundaryViolations() > 0) reasons.add("outside_authority");
        }
        reasons.add(value.demolitionCount() == 0 ? "demolition=0"
                : "demolition=" + value.demolitionCount());
        reasons.add(value.botWorkBlockedCells() == 0 ? "bot_access=clear" : "bot_access=blocked");
        reasons.add(value.layoutVariant().reservesExpansionBay()
                ? "future_expansion=reserved" : "future_expansion=limited");
        return new PlacementCandidateScore(value, value.hardConflictFree(),
                Math.addExact(hard, soft), reasons);
    }
}
