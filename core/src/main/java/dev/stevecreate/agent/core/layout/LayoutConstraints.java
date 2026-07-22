package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Caller-owned, typed bounds for deterministic layout search. */
public record LayoutConstraints(
        BlockPos3i anchor,
        List<QuarterTurn> allowedOrientations,
        int maximumCandidates,
        int maximumSearchRadius,
        int maximumRouteLength,
        int searchBudget,
        long itemRouteCapacity,
        long rotationalPowerCapacity,
        long stressCapacity,
        PlacementSnapshot snapshot) {
    public LayoutConstraints {
        Objects.requireNonNull(anchor, "anchor");
        if (anchor.equals(new BlockPos3i(0, 0, 0))) {
            throw new IllegalArgumentException("anchor must identify an explicit non-placeholder test area");
        }
        Objects.requireNonNull(allowedOrientations, "allowedOrientations");
        allowedOrientations = List.copyOf(new LinkedHashSet<>(allowedOrientations));
        if (allowedOrientations.isEmpty() || allowedOrientations.size() > 3
                || allowedOrientations.contains(QuarterTurn.CLOCKWISE_180)) {
            throw new IllegalArgumentException("only the three accepted Create orientations are allowed");
        }
        if (maximumCandidates < 1 || maximumCandidates > 256
                || maximumSearchRadius < 1 || maximumSearchRadius > 128
                || maximumRouteLength < 1 || maximumRouteLength > 512
                || searchBudget < 1 || searchBudget > 1_000_000) {
            throw new IllegalArgumentException("layout search bound is outside its hard limit");
        }
        if (itemRouteCapacity < 1 || rotationalPowerCapacity < 1 || stressCapacity < 1) {
            throw new IllegalArgumentException("layout capacities must be positive");
        }
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
