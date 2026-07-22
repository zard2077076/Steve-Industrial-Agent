package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;

/** Exact bounded location of a planning failure in one dependency expansion. */
public record PlanningFailureLocation(
        int depth,
        Optional<ResourceId> resourceId,
        Optional<ResourceId> recipeId) {
    public PlanningFailureLocation {
        if (depth < 0 || depth > ProductionGoal.MAX_PROCESSING_DEPTH + 1) {
            throw new IllegalArgumentException("Failure depth is outside the planning bound");
        }
        resourceId = Objects.requireNonNull(resourceId, "resourceId");
        recipeId = Objects.requireNonNull(recipeId, "recipeId");
        if (resourceId.isEmpty() && recipeId.isEmpty()) {
            throw new IllegalArgumentException("A failure location needs a resource or recipe");
        }
    }
}
