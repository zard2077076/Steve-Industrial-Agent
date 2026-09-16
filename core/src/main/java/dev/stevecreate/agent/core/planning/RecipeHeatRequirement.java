package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, non-executable heat contract retained beside one runtime recipe.
 *
 * <p>It distinguishes the physical heat source from fuel, rotational power and recipe inputs.
 * The value grants no inventory, placement or execution authority.</p>
 */
public record RecipeHeatRequirement(
        ResourceId requirementId,
        RecipeHeatTier heatTier,
        ResourceId sourceRecipeId,
        ResourceId sourceCapabilityId,
        boolean continuouslyRequired,
        Set<ResourceId> preStartVerificationIds,
        Set<ResourceId> runtimeEvidenceIds,
        Set<ResourceId> failureDiagnosticIds,
        Optional<ProcessResource> fuelPerExecution) {
    public static final int MAX_EVIDENCE_IDS = 16;

    public RecipeHeatRequirement {
        Objects.requireNonNull(requirementId, "requirementId");
        Objects.requireNonNull(heatTier, "heatTier");
        Objects.requireNonNull(sourceRecipeId, "sourceRecipeId");
        Objects.requireNonNull(sourceCapabilityId, "sourceCapabilityId");
        preStartVerificationIds = copyIds(
                preStartVerificationIds, "preStartVerificationIds", true);
        runtimeEvidenceIds = copyIds(
                runtimeEvidenceIds, "runtimeEvidenceIds", true);
        failureDiagnosticIds = copyIds(
                failureDiagnosticIds, "failureDiagnosticIds", true);
        fuelPerExecution = Objects.requireNonNull(
                fuelPerExecution, "fuelPerExecution");
        if (fuelPerExecution.isPresent()
                && fuelPerExecution.orElseThrow().resourceType()
                        != GenericResourceType.ITEM) {
            throw new IllegalArgumentException(
                    "Heat fuel must be a bounded ITEM resource");
        }
        if ((heatTier == RecipeHeatTier.HEATED) != fuelPerExecution.isPresent()) {
            throw new IllegalArgumentException(
                    "Exactly HEATED recipes require one explicit fuel budget");
        }
        if ((heatTier == RecipeHeatTier.NONE && continuouslyRequired)
                || (heatTier == RecipeHeatTier.HEATED && !continuouslyRequired)) {
            throw new IllegalArgumentException(
                    "NONE cannot require continuous heat and HEATED must require it");
        }
    }

    public static RecipeHeatRequirement none(
            ResourceId recipeId, ResourceId capabilityId) {
        return new RecipeHeatRequirement(
                new ResourceId(
                        "steve_industrial",
                        "heat_requirement/" + recipeId.namespace() + "/"
                                + recipeId.path()),
                RecipeHeatTier.NONE,
                recipeId,
                capabilityId,
                false,
                Set.of(new ResourceId(
                        "steve_industrial", "preflight/heat_source_unheated")),
                Set.of(new ResourceId(
                        "steve_industrial", "evidence/heat_tier_observed")),
                Set.of(new ResourceId(
                        "steve_industrial", "diagnostic/heat_requirement_mismatch")),
                Optional.empty());
    }

    private static Set<ResourceId> copyIds(
            Set<ResourceId> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > MAX_EVIDENCE_IDS) {
            throw new IllegalArgumentException(name + " count violates heat metadata bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(
                Comparator.comparing(ResourceId::toString));
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
