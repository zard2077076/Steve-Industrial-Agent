package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable user-readable typed explanation for an unsatisfied planning request. */
public record PlanningFailure(
        PlanningFailureCode code,
        PlanningFailureLocation location,
        List<ResourceId> tracePath,
        String reason,
        List<ResourceId> alternatives) {
    public static final int MAX_TRACE = 65;
    public static final int MAX_ALTERNATIVES = 64;
    public static final int MAX_REASON_LENGTH = 1_024;

    public PlanningFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        tracePath = copyIds(tracePath, "tracePath", MAX_TRACE, false);
        if (tracePath.isEmpty()) {
            throw new IllegalArgumentException("tracePath must identify the failed dependency path");
        }
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Failure reason must be nonblank and bounded");
        }
        alternatives = copyIds(alternatives, "alternatives", MAX_ALTERNATIVES, true);
    }

    private static List<ResourceId> copyIds(
            List<ResourceId> values,
            String name,
            int maximum,
            boolean sortAndRejectDuplicates) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        List<ResourceId> copy = new ArrayList<>(values.size());
        Set<ResourceId> unique = new HashSet<>();
        for (ResourceId value : values) {
            ResourceId id = Objects.requireNonNull(value, name + " element");
            if (sortAndRejectDuplicates && !unique.add(id)) {
                throw new IllegalArgumentException(name + " contains duplicate " + id);
            }
            copy.add(id);
        }
        if (sortAndRejectDuplicates) {
            copy.sort(Comparator.comparing(ResourceId::toString));
        }
        return List.copyOf(copy);
    }
}
