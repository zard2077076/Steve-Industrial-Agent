package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.ScoredCandidate;
import dev.stevecreate.agent.core.planning.VerifiedLogicalPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only runtime provenance around the existing non-executable verified logical plan. */
public record RuntimeVerifiedPlanningResult(
        ProductionGoal goal,
        ResolvedRuntimeRecipeCatalog resolvedRecipes,
        RuntimeMachineCapabilityCatalogSnapshot capabilities,
        List<ScoredCandidate> rankedCandidates,
        VerifiedLogicalPlan verifiedPlan,
        String runtimeFingerprint) {
    public RuntimeVerifiedPlanningResult {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(resolvedRecipes, "resolvedRecipes");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        if (rankedCandidates.isEmpty() || rankedCandidates.size() > 64) {
            throw new IllegalArgumentException("rankedCandidates count violates planning bounds");
        }
        Set<ResourceId> identities = new HashSet<>();
        rankedCandidates = rankedCandidates.stream()
                .map(value -> Objects.requireNonNull(value, "rankedCandidates element"))
                .peek(value -> {
                    if (!identities.add(value.candidate().candidateId())) {
                        throw new IllegalArgumentException(
                                "rankedCandidates contains duplicate candidate identity");
                    }
                })
                .toList();
        verifiedPlan = Objects.requireNonNull(verifiedPlan, "verifiedPlan");
        if (!goal.equals(verifiedPlan.candidate().goal())
                || !rankedCandidates.get(0).candidate().candidateId()
                        .equals(verifiedPlan.candidate().candidateId())) {
            throw new IllegalArgumentException(
                    "Verified plan must be the first ranked candidate for the supplied goal");
        }
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint", 2_048);
        if (!runtimeFingerprint.equals(resolvedRecipes.runtimeFingerprint())
                || !runtimeFingerprint.equals(capabilities.runtimeFingerprint())) {
            throw new IllegalArgumentException(
                    "Resolved recipes, capabilities and planning result must share one fingerprint");
        }
        Set<ResourceId> resolvedIds = new HashSet<>();
        resolvedRecipes.resolutions().forEach(value ->
                resolvedIds.add(value.resolvedRecipe().recipeId()));
        if (verifiedPlan.candidate().selectedRecipes().stream()
                .anyMatch(value -> !resolvedIds.contains(value.recipeId()))) {
            throw new IllegalArgumentException(
                    "Verified plan contains a recipe outside the resolved runtime catalog");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
