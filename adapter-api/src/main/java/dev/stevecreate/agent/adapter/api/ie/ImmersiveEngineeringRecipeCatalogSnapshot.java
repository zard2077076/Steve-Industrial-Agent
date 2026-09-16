package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ImmersiveEngineeringRecipeCatalogSnapshot(
        RuntimeFingerprint runtime,
        long reloadGeneration,
        String catalogSha256,
        List<ImmersiveEngineeringRecipe> recipes) {
    public ImmersiveEngineeringRecipeCatalogSnapshot {
        Objects.requireNonNull(runtime, "runtime");
        if (reloadGeneration < 0) throw new IllegalArgumentException("IE reload generation is negative");
        Objects.requireNonNull(catalogSha256, "catalogSha256");
        if (!catalogSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IE recipe catalog fingerprint is invalid");
        }
        Objects.requireNonNull(recipes, "recipes");
        if (recipes.size() > 65_536) throw new IllegalArgumentException("IE recipe catalog exceeds bound");
        recipes = recipes.stream().sorted(Comparator.comparing(value -> value.recipeId().toString()))
                .toList();
        if (recipes.stream().map(ImmersiveEngineeringRecipe::recipeId).distinct().count()
                != recipes.size()) {
            throw new IllegalArgumentException("duplicate IE recipe identity");
        }
    }
}
