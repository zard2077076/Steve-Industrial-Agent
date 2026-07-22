package dev.stevecreate.agent.core.placement;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded loader-neutral placement gate; it reports conflicts but never searches or mutates. */
public final class BasicPlacementFeasibility {
    public static final int MAX_TARGETS = 256;
    public static final int MAX_CONFLICTS = MAX_TARGETS * PlacementConflictKind.values().length;

    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator
            .comparingInt(BlockPos3i::x)
            .thenComparingInt(BlockPos3i::y)
            .thenComparingInt(BlockPos3i::z);
    private static final Comparator<ResourceId> ROLE_ORDER = Comparator.comparing(ResourceId::toString);

    private BasicPlacementFeasibility() {
    }

    public static PlacementFeasibilityReport evaluate(
            List<PlacementTarget> targets,
            List<PlacementSiteObservation> observations) {
        List<PlacementTarget> targetCopy = List.copyOf(Objects.requireNonNull(targets, "targets"));
        List<PlacementSiteObservation> observationCopy =
                List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (targetCopy.isEmpty() || targetCopy.size() > MAX_TARGETS
                || targetCopy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Placement targets must be non-empty and bounded");
        }
        if (observationCopy.isEmpty() || observationCopy.size() > MAX_TARGETS
                || observationCopy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Placement observations must be non-empty and bounded");
        }

        Map<BlockPos3i, List<ResourceId>> rolesByPosition = new TreeMap<>(POSITION_ORDER);
        for (PlacementTarget target : targetCopy) {
            rolesByPosition.computeIfAbsent(target.position(), ignored -> new ArrayList<>())
                    .add(target.roleId());
        }

        Map<BlockPos3i, PlacementSiteObservation> observationByPosition = new LinkedHashMap<>();
        for (PlacementSiteObservation observation : observationCopy) {
            if (observationByPosition.putIfAbsent(observation.position(), observation) != null) {
                throw new IllegalArgumentException("Placement observations must be non-null and unique by position");
            }
        }
        if (!observationByPosition.keySet().equals(rolesByPosition.keySet())) {
            throw new IllegalArgumentException("Placement observations must exactly cover every unique target position");
        }

        List<PlacementConflict> conflicts = new ArrayList<>();
        for (Map.Entry<BlockPos3i, List<ResourceId>> entry : rolesByPosition.entrySet()) {
            BlockPos3i position = entry.getKey();
            List<ResourceId> roles = entry.getValue().stream().distinct().sorted(ROLE_ORDER).toList();
            PlacementSiteObservation observation = observationByPosition.get(position);
            if (!observation.loaded()) {
                conflicts.add(new PlacementConflict(position, PlacementConflictKind.UNLOADED, roles));
            } else if (!observation.replaceable()) {
                conflicts.add(new PlacementConflict(position, PlacementConflictKind.NOT_REPLACEABLE, roles));
            }
            if (observation.protectedPosition()) {
                conflicts.add(new PlacementConflict(position, PlacementConflictKind.PROTECTED, roles));
            }
            if (entry.getValue().size() > 1) {
                conflicts.add(new PlacementConflict(
                        position,
                        PlacementConflictKind.INTERNAL_ROLE_CONFLICT,
                        roles));
            }
        }
        return new PlacementFeasibilityReport(targetCopy.size(), conflicts);
    }
}
