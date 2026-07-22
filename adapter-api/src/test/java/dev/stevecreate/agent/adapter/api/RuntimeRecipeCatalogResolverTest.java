package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.ImmutableRuntimeRecipeCatalog;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RecipeSource;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeRecipeCatalogResolverTest {
    @Test
    void ownedCandidatesWinAndTiesUseStableResourceIdentity() {
        RuntimeRecipeCatalogEntry entry = recipe(
                "test:any",
                new RecipeIngredient.AnyOfResources(
                        List.of(id("test:zinc"), id("test:copper"), id("test:aluminum")), 2),
                "test:plate");
        ProductionGoal goal = goal(
                "test:plate",
                Map.of(id("test:zinc"), 8L, id("test:copper"), 8L));

        ResolvedRuntimeRecipeCatalog first = requireSuccess(resolve(List.of(entry), goal));
        ResolvedRuntimeRecipeCatalog second = requireSuccess(resolve(List.of(entry), goal));
        CatalogRecipe recipe = first.catalog().find(id("test:any")).orElseThrow();

        assertThat(recipe.inputs()).containsExactly(item("test:copper", 2));
        assertThat(first.catalog().recipes()).isEqualTo(second.catalog().recipes());
        assertThat(first.resolutions()).isEqualTo(second.resolutions());
        assertThat(first.limitations()).isEqualTo(second.limitations());
        assertThat(first.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.runtimeEntry()).isEqualTo(entry);
            assertThat(resolution.selections()).singleElement().satisfies(selection -> {
                assertThat(selection.reason()).isEqualTo(IngredientSelectionReason.OWNED_RESOURCE);
                assertThat(selection.ingredientKind()).isEqualTo(entry.inputs().get(0).kind());
                assertThat(selection.ingredientIdentity())
                        .isEqualTo(entry.inputs().get(0).canonicalIdentity());
                assertThat(selection.selectedResource()).isEqualTo(id("test:copper"));
            });
        });
    }

    @Test
    void tagIdentitySurvivesCanonicalFallbackResolution() {
        RecipeIngredient.TagReference tag = new RecipeIngredient.TagReference(
                id("forge:ingots/iron"),
                List.of(id("minecraft:iron_ingot"), id("example:iron_ingot")),
                "sha256:runtime",
                1);
        RuntimeRecipeCatalogEntry entry = recipe("test:pressing", tag, "test:sheet");

        ResolvedRuntimeRecipeCatalog resolved = requireSuccess(resolve(
                List.of(entry), goal("test:sheet", Map.of())));

        assertThat(resolved.catalog().find(id("test:pressing")).orElseThrow().inputs())
                .containsExactly(item("example:iron_ingot", 1));
        assertThat(resolved.resolutions().get(0).selections().get(0)).satisfies(selection -> {
            assertThat(selection.reason()).isEqualTo(IngredientSelectionReason.CANONICAL_CANDIDATE);
            assertThat(selection.ingredientKind()).isEqualTo(tag.kind());
            assertThat(selection.ingredientIdentity()).contains("tag:forge:ingots/iron");
        });
    }

    @Test
    void unresolvedTagIsTypedAndDoesNotEraseOtherResolvableRecipes() {
        RuntimeRecipeCatalogEntry exact = recipe(
                "test:exact",
                new RecipeIngredient.ExactResource(id("test:raw"), 1),
                "test:plate");
        RuntimeRecipeCatalogEntry emptyTag = recipe(
                "test:empty_tag",
                new RecipeIngredient.TagReference(
                        id("forge:missing"), List.of(), "sha256:runtime", 1),
                "test:other");

        ResolvedRuntimeRecipeCatalog partial = requireSuccess(resolve(
                List.of(emptyTag, exact), goal("test:plate", Map.of())));
        assertThat(partial.catalog().recipes()).extracting(CatalogRecipe::recipeId)
                .containsExactly(id("test:exact"));
        assertThat(partial.limitations()).singleElement().satisfies(failure -> {
            assertThat(failure.code())
                    .isEqualTo(RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED);
            assertThat(failure.recipeId()).contains(id("test:empty_tag"));
            assertThat(failure.targetResource()).contains(id("test:plate"));
            assertThat(failure.ingredientIdentity()).hasValueSatisfying(
                    identity -> assertThat(identity).contains("tag:forge:missing"));
        });

        RuntimeRecipeCatalogResolutionResult allUnresolved = resolve(
                List.of(emptyTag), goal("test:other", Map.of()));
        assertThat(allUnresolved).isInstanceOf(RuntimeRecipeCatalogResolutionResult.Failure.class);
        RuntimeKnowledgeFailure failure =
                ((RuntimeRecipeCatalogResolutionResult.Failure) allUnresolved).failure();
        assertThat(failure.code())
                .isEqualTo(RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED);
    }

    @Test
    void forbiddenCandidatesAreNeverSelected() {
        RuntimeRecipeCatalogEntry entry = recipe(
                "test:any",
                new RecipeIngredient.AnyOfResources(
                        List.of(id("test:allowed"), id("test:forbidden")), 1),
                "test:plate");
        ProductionGoal goal = new ProductionGoal(
                id("test:plate"),
                GenericResourceType.ITEM,
                1,
                Set.of(),
                Set.of(),
                Optional.empty(),
                new MaterialConstraints(Set.of(id("test:forbidden")), Map.of()),
                List.of(PlanningStrategyPreference.MINIMIZE_RAW_MATERIAL_TYPES),
                Map.of(id("test:forbidden"), 100L));

        ResolvedRuntimeRecipeCatalog resolved = requireSuccess(resolve(List.of(entry), goal));
        assertThat(resolved.catalog().find(id("test:any")).orElseThrow().inputs())
                .containsExactly(item("test:allowed", 1));
    }

    @Test
    void staleTagCandidateSnapshotReturnsTypedFingerprintFailure() {
        RuntimeRecipeCatalogEntry entry = recipe(
                "test:stale",
                new RecipeIngredient.TagReference(
                        id("forge:ingots/iron"),
                        List.of(id("minecraft:iron_ingot")),
                        "sha256:old-runtime",
                        1),
                "test:sheet");

        RuntimeRecipeCatalogResolutionResult result = resolve(
                List.of(entry), goal("test:sheet", Map.of()));

        assertThat(result).isInstanceOf(RuntimeRecipeCatalogResolutionResult.Failure.class);
        assertThat(((RuntimeRecipeCatalogResolutionResult.Failure) result).failure().code())
                .isEqualTo(RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH);
    }

    private static RuntimeRecipeCatalogResolutionResult resolve(
            List<RuntimeRecipeCatalogEntry> entries,
            ProductionGoal goal) {
        RuntimeFingerprint runtime = new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                Map.of("create", "6.0.6-150"),
                "test:adapter",
                1);
        RuntimeRecipeCatalogSnapshot snapshot = new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(entries),
                runtime,
                "sha256:runtime",
                "minecraft:overworld",
                0,
                entries.size(),
                entries.size(),
                List.of(),
                List.of());
        return new RuntimeRecipeCatalogResolver().resolve(snapshot, goal);
    }

    private static ResolvedRuntimeRecipeCatalog requireSuccess(
            RuntimeRecipeCatalogResolutionResult result) {
        assertThat(result).isInstanceOf(RuntimeRecipeCatalogResolutionResult.Success.class);
        return ((RuntimeRecipeCatalogResolutionResult.Success) result).resolved();
    }

    private static ProductionGoal goal(String target, Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id(target),
                GenericResourceType.ITEM,
                1,
                Set.of(),
                Set.of(),
                Optional.empty(),
                MaterialConstraints.none(),
                List.of(),
                owned);
    }

    private static RuntimeRecipeCatalogEntry recipe(
            String recipeId,
            RecipeIngredient input,
            String output) {
        return new RuntimeRecipeCatalogEntry(
                id(recipeId),
                id("create:pressing"),
                List.of(input),
                List.of(item(output, 1)),
                List.of(),
                Set.of(id("create:pressing")),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(240),
                new RecipeSource(id("test:adapter"), "create", "sha256:runtime", true));
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
