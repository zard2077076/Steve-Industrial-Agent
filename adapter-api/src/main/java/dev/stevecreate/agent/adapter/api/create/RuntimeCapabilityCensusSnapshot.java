package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Server-authoritative, read-only C-05 through C-10 census snapshot. */
public record RuntimeCapabilityCensusSnapshot(
        String runtimeFingerprint,
        String worldIdentity,
        long reloadGeneration,
        int recipeManagerCount,
        Map<ResourceId, Long> targetRecipeTypeCounts,
        List<CapabilityRecipeSemantics> recipes,
        boolean serverAuthoritative,
        boolean worldMutation) {
    public RuntimeCapabilityCensusSnapshot {
        runtimeFingerprint = CapabilityContracts.text(runtimeFingerprint, "runtimeFingerprint");
        worldIdentity = CapabilityContracts.text(worldIdentity, "worldIdentity");
        if (reloadGeneration < 0 || recipeManagerCount < 1) {
            throw new IllegalArgumentException("Reload generation or recipe count is invalid");
        }
        targetRecipeTypeCounts = CapabilityContracts.sortedMap(
                targetRecipeTypeCounts,
                Comparator.comparing(ResourceId::toString),
                "targetRecipeTypeCounts");
        for (CreateCapabilityId capability : CreateCapabilityId.values()) {
            if (!targetRecipeTypeCounts.containsKey(capability.recipeType())) {
                throw new IllegalArgumentException(
                        "Missing NOT_PRESENT-capable type count for " + capability.recipeType());
            }
        }
        long targetTotal = 0;
        for (Long count : targetRecipeTypeCounts.values()) {
            if (count < 0) {
                throw new IllegalArgumentException("Target recipe count cannot be negative");
            }
            targetTotal = Math.addExact(targetTotal, count);
        }
        recipes = CapabilityContracts.list(recipes, "recipes").stream()
                .sorted(Comparator.comparing(value -> value.recipeId().toString()))
                .toList();
        if (targetTotal != recipes.size() || !serverAuthoritative || worldMutation) {
            throw new IllegalArgumentException(
                    "Census must account for every target recipe and remain authoritative/read-only");
        }
        for (CapabilityRecipeSemantics recipe : recipes) {
            if (!runtimeFingerprint.equals(recipe.runtimeFingerprint())) {
                throw new IllegalArgumentException("Recipe runtime fingerprint drifted within census");
            }
        }
    }

    public Map<CreateCapabilityId, CapabilityCensusSummary> summaries() {
        Map<CreateCapabilityId, CapabilityCensusSummary> summaries =
                new EnumMap<>(CreateCapabilityId.class);
        for (CreateCapabilityId capability : CreateCapabilityId.values()) {
            List<CapabilityRecipeSemantics> matching = recipes.stream()
                    .filter(recipe -> recipe.capability() == capability)
                    .toList();
            long supported = matching.stream()
                    .filter(recipe -> recipe.support() == CapabilityRecipeSupport.SUPPORTED_PHASE_I)
                    .count();
            long semantics = matching.stream()
                    .filter(recipe -> recipe.support() == CapabilityRecipeSupport.SEMANTICS_ONLY)
                    .count();
            long unsupported = matching.stream()
                    .filter(recipe -> recipe.support() == CapabilityRecipeSupport.UNSUPPORTED)
                    .count();
            List<ResourceId> candidates = matching.stream()
                    .filter(recipe -> recipe.support() == CapabilityRecipeSupport.SUPPORTED_PHASE_I)
                    .map(CapabilityRecipeSemantics::recipeId)
                    .sorted(Comparator.comparing(ResourceId::toString))
                    .limit(16)
                    .toList();
            long discovered = targetRecipeTypeCounts.get(capability.recipeType());
            summaries.put(capability, new CapabilityCensusSummary(
                    capability,
                    discovered,
                    supported,
                    semantics,
                    unsupported,
                    discovered == 0,
                    candidates));
        }
        Map<CreateCapabilityId, CapabilityCensusSummary> ordered = new LinkedHashMap<>();
        CreateCapabilityId.canonicalValues().forEach(value -> ordered.put(value, summaries.get(value)));
        return Map.copyOf(ordered);
    }
}
