package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.ImmutableRecipeCatalog;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves runtime ingredient alternatives into exact P-03 recipes for one typed goal. */
public final class RuntimeRecipeCatalogResolver {
    public RuntimeRecipeCatalogResolutionResult resolve(
            RuntimeRecipeCatalogSnapshot snapshot,
            ProductionGoal goal) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(goal, "goal");
        List<ResolvedRuntimeRecipe> resolutions = new ArrayList<>();
        List<RuntimeKnowledgeFailure> limitations = new ArrayList<>();
        for (RuntimeRecipeCatalogEntry entry : snapshot.catalog().recipes()) {
            EntryResolution result = resolveEntry(entry, snapshot, goal);
            if (result.failure().isPresent()) {
                limitations.add(result.failure().orElseThrow());
            } else {
                resolutions.add(result.resolution().orElseThrow());
            }
        }
        if (resolutions.isEmpty()) {
            RuntimeKnowledgeFailure failure = limitations.stream()
                    .filter(value -> value.recipeId().map(recipeId -> snapshot.catalog()
                            .find(recipeId)
                            .stream()
                            .flatMap(entry -> entry.outputs().stream())
                            .anyMatch(output -> output.resourceId().equals(goal.target()))).orElse(false))
                    .findFirst()
                    .orElseGet(() -> limitations.stream().findFirst().orElseThrow());
            return new RuntimeRecipeCatalogResolutionResult.Failure(failure);
        }
        List<CatalogRecipe> exactRecipes = resolutions.stream()
                .map(ResolvedRuntimeRecipe::resolvedRecipe)
                .toList();
        return new RuntimeRecipeCatalogResolutionResult.Success(new ResolvedRuntimeRecipeCatalog(
                new ImmutableRecipeCatalog(exactRecipes),
                resolutions,
                limitations,
                snapshot.runtimeFingerprint()));
    }

    private EntryResolution resolveEntry(
            RuntimeRecipeCatalogEntry entry,
            RuntimeRecipeCatalogSnapshot snapshot,
            ProductionGoal goal) {
        if (!entry.source().runtimeVerified()
                || !entry.source().runtimeFingerprint().equals(snapshot.runtimeFingerprint())) {
            return EntryResolution.failed(failure(
                    entry,
                    entry.inputs().get(0),
                    0,
                    snapshot,
                    goal,
                    RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                    "Runtime recipe source attribution does not match the active catalog fingerprint"));
        }
        List<ProcessResource> inputs = new ArrayList<>(entry.inputs().size());
        List<RuntimeIngredientSelection> selections = new ArrayList<>(entry.inputs().size());
        for (int index = 0; index < entry.inputs().size(); index++) {
            RecipeIngredient ingredient = entry.inputs().get(index);
            if (ingredient instanceof RecipeIngredient.TagReference tag
                    && !tag.runtimeFingerprint().equals(snapshot.runtimeFingerprint())) {
                return EntryResolution.failed(failure(
                        entry,
                        ingredient,
                        index,
                        snapshot,
                        goal,
                        RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                        "Tag candidate snapshot belongs to a different runtime fingerprint"));
            }
            Selection selection = select(ingredient, goal);
            if (selection.resource().isEmpty()) {
                return EntryResolution.failed(failure(
                        entry,
                        ingredient,
                        index,
                        snapshot,
                        goal,
                        selection.failureCode(),
                        selection.detail()));
            }
            ResourceId selected = selection.resource().orElseThrow();
            inputs.add(new ProcessResource(selected, GenericResourceType.ITEM, ingredient.amount()));
            selections.add(new RuntimeIngredientSelection(
                    entry.recipeId(),
                    index,
                    ingredient.kind(),
                    ingredient.canonicalIdentity(),
                    selected,
                    ingredient.amount(),
                    selection.reason().orElseThrow()));
        }
        CatalogRecipe exact = new CatalogRecipe(
                entry.recipeId(),
                entry.recipeType(),
                inputs,
                entry.outputs(),
                entry.optionalByproducts(),
                entry.requiredMachineCapabilities(),
                entry.requiredResourceTypes(),
                entry.processingTicks(),
                entry.source());
        return EntryResolution.succeeded(new ResolvedRuntimeRecipe(entry, exact, selections));
    }

    private Selection select(RecipeIngredient ingredient, ProductionGoal goal) {
        if (ingredient instanceof RecipeIngredient.ExactResource exact) {
            if (goal.materialConstraints().forbiddenResources().contains(exact.resourceId())) {
                return Selection.failed(
                        RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED,
                        "The exact ingredient is forbidden by the typed material constraints");
            }
            return Selection.succeeded(exact.resourceId(), IngredientSelectionReason.EXACT_RESOURCE);
        }
        if (ingredient instanceof RecipeIngredient.UnsupportedComplexIngredient) {
            return Selection.failed(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    "A complex ingredient cannot be resolved into an exact planning resource");
        }
        List<ResourceId> allowed = ingredient.runtimeCandidates().stream()
                .filter(candidate ->
                        !goal.materialConstraints().forbiddenResources().contains(candidate))
                .sorted(Comparator.comparing(ResourceId::toString))
                .toList();
        if (allowed.isEmpty()) {
            return Selection.failed(
                    RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED,
                    ingredient.runtimeCandidates().isEmpty()
                            ? "Runtime ingredient has no candidate snapshot"
                            : "Every runtime ingredient candidate is forbidden by material constraints");
        }
        Map<ResourceId, Long> owned = goal.ownedResources();
        Optional<ResourceId> selectedOwned = allowed.stream()
                .filter(candidate -> owned.getOrDefault(candidate, 0L) > 0)
                .sorted(Comparator
                        .<ResourceId>comparingLong(candidate -> owned.getOrDefault(candidate, 0L))
                        .reversed()
                        .thenComparing(ResourceId::toString))
                .findFirst();
        if (selectedOwned.isPresent()) {
            return Selection.succeeded(
                    selectedOwned.orElseThrow(), IngredientSelectionReason.OWNED_RESOURCE);
        }
        return Selection.succeeded(allowed.get(0), IngredientSelectionReason.CANONICAL_CANDIDATE);
    }

    private RuntimeKnowledgeFailure failure(
            RuntimeRecipeCatalogEntry entry,
            RecipeIngredient ingredient,
            int inputIndex,
            RuntimeRecipeCatalogSnapshot snapshot,
            ProductionGoal goal,
            RuntimeKnowledgeFailureCode code,
            String detail) {
        return new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.PLANNING,
                Optional.of(entry.recipeId()),
                Optional.of(goal.target()),
                Optional.of(bounded(
                        ingredient.canonicalIdentity(),
                        RuntimeKnowledgeFailure.MAX_TRACE_ENTRY_LENGTH)),
                entry.source().adapterId(),
                snapshot.runtimeFingerprint(),
                List.of(
                        "target:" + goal.target(),
                        "recipe:" + entry.recipeId(),
                        "input_index:" + inputIndex,
                        "ingredient_kind:" + ingredient.kind()),
                detail);
    }

    private static String bounded(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private record Selection(
            Optional<ResourceId> resource,
            Optional<IngredientSelectionReason> reason,
            RuntimeKnowledgeFailureCode failureCode,
            String detail) {
        private static Selection succeeded(
                ResourceId resource,
                IngredientSelectionReason reason) {
            return new Selection(
                    Optional.of(resource),
                    Optional.of(reason),
                    RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED,
                    "resolved");
        }

        private static Selection failed(
                RuntimeKnowledgeFailureCode code,
                String detail) {
            return new Selection(Optional.empty(), Optional.empty(), code, detail);
        }
    }

    private record EntryResolution(
            Optional<ResolvedRuntimeRecipe> resolution,
            Optional<RuntimeKnowledgeFailure> failure) {
        private static EntryResolution succeeded(ResolvedRuntimeRecipe resolution) {
            return new EntryResolution(Optional.of(resolution), Optional.empty());
        }

        private static EntryResolution failed(RuntimeKnowledgeFailure failure) {
            return new EntryResolution(Optional.empty(), Optional.of(failure));
        }
    }
}
