package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.Objects;
import java.util.Optional;

/** Non-player catalyst semantics with explicit return and state evidence. */
public record CatalystRequirement(
        Optional<RecipeIngredient> catalyst,
        boolean returnedAfterProcessing,
        boolean stateReadbackRequired) {
    public CatalystRequirement {
        catalyst = Objects.requireNonNull(catalyst, "catalyst");
        catalyst.ifPresent(value -> Objects.requireNonNull(value, "catalyst value"));
        if (catalyst.isEmpty() && (returnedAfterProcessing || stateReadbackRequired)) {
            throw new IllegalArgumentException("Absent catalyst cannot claim return or observation");
        }
        if (catalyst.isPresent() && (!returnedAfterProcessing || !stateReadbackRequired)) {
            throw new IllegalArgumentException("A catalyst must be returned and observed");
        }
    }

    public static CatalystRequirement none() {
        return new CatalystRequirement(Optional.empty(), false, false);
    }
}
