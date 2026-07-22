package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.PlanningVerificationCheck;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic concise output plus complete structured trace for the read-only command. */
public final class RuntimePlanningCommandFormatter {
    public RuntimePlanningCommandReport format(RuntimePlanningResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof RuntimePlanningResult.Failure failed) {
            return failure(failed.failure());
        }
        return success(((RuntimePlanningResult.Success) result).result());
    }

    private static RuntimePlanningCommandReport success(RuntimeVerifiedPlanningResult result) {
        CandidatePlan candidate = result.verifiedPlan().candidate();
        List<ResourceId> selectedRecipeIds = candidate.quantityConversions().stream()
                .map(CandidateQuantityConversion::recipeId)
                .toList();
        Set<ResourceId> selectedRecipeSet = new LinkedHashSet<>(selectedRecipeIds);
        List<String> recipes = candidate.selectedRecipes().stream()
                .map(RuntimePlanningCommandFormatter::formatRecipe)
                .toList();
        List<String> ingredients = result.resolvedRecipes().resolutions().stream()
                .filter(value -> selectedRecipeSet.contains(value.resolvedRecipe().recipeId()))
                .flatMap(value -> value.selections().stream())
                .map(value -> "ingredient=" + value.ingredientKind() + ":"
                        + value.selectedResource() + " identity=" + quote(value.ingredientIdentity())
                        + " reason=" + value.reason())
                .toList();
        List<String> conversions = candidate.quantityConversions().stream()
                .map(RuntimePlanningCommandFormatter::formatConversion)
                .toList();
        Set<ResourceId> adapters = new LinkedHashSet<>();
        candidate.selectedRecipes().stream()
                .map(value -> value.source().adapterId())
                .sorted(Comparator.comparing(ResourceId::toString))
                .forEach(adapters::add);
        List<String> trace = new ArrayList<>();
        trace.add(result.goal().target().toString());
        selectedRecipeIds.forEach(value -> trace.add(value.toString()));

        String headline = "Plan verified target=" + result.goal().target()
                + " quantity=" + result.goal().quantity()
                + " candidates=" + result.rankedCandidates().size()
                + " verification=PASS fingerprint=" + result.runtimeFingerprint();
        String resources = String.join(";", recipes)
                + " raw=" + formatResources(candidate.rawMaterials())
                + " owned=" + formatResources(candidate.ownedResourcesUsed())
                + " intermediate=" + formatResources(candidate.intermediateResources())
                + " capabilities=" + formatIds(candidate.requiredMachineCapabilities())
                + " adapter=" + formatIds(adapters);
        String detail = String.join(";", ingredients)
                + " conversions=" + bracket(conversions)
                + " trace=" + bracket(trace);
        String structured = "runtimePlanning={status=PASS,target=" + result.goal().target()
                + ",quantity=" + result.goal().quantity()
                + ",candidateCount=" + result.rankedCandidates().size()
                + ",recipes=" + bracket(recipes)
                + ",raw=" + formatResources(candidate.rawMaterials())
                + ",owned=" + formatResources(candidate.ownedResourcesUsed())
                + ",intermediates=" + formatResources(candidate.intermediateResources())
                + ",capabilities=" + formatIds(candidate.requiredMachineCapabilities())
                + ",adapters=" + formatIds(adapters)
                + ",ingredients=" + bracket(ingredients)
                + ",conversions=" + bracket(conversions)
                + ",verificationChecks=" + bracket(result.verifiedPlan().evidence().keySet()
                        .stream().map(PlanningVerificationCheck::name).toList())
                + ",fingerprint=" + result.runtimeFingerprint()
                + ",trace=" + bracket(trace) + "}";
        return new RuntimePlanningCommandReport(
                true, 1, List.of(headline, resources, detail), structured);
    }

    private static RuntimePlanningCommandReport failure(RuntimeKnowledgeFailure failure) {
        String recipe = failure.recipeId().map(Object::toString).orElse("none");
        String target = failure.targetResource().map(Object::toString).orElse("none");
        String ingredient = failure.ingredientIdentity().orElse("none");
        String trace = bracket(failure.trace());
        String line = "Plan failed code=" + failure.code()
                + " stage=" + failure.stage()
                + " target=" + target
                + " recipe=" + recipe
                + " ingredient=" + quote(ingredient)
                + " adapter=" + failure.adapterId()
                + " fingerprint=" + failure.runtimeFingerprint()
                + " reason=" + quote(failure.detail())
                + " trace=" + trace;
        String structured = "runtimePlanning={status=FAIL,code=" + failure.code()
                + ",stage=" + failure.stage()
                + ",target=" + target
                + ",recipe=" + recipe
                + ",ingredient=" + quote(ingredient)
                + ",adapter=" + failure.adapterId()
                + ",fingerprint=" + failure.runtimeFingerprint()
                + ",detail=" + quote(failure.detail())
                + ",trace=" + trace + "}";
        return new RuntimePlanningCommandReport(false, 0, List.of(line), structured);
    }

    private static String formatRecipe(CatalogRecipe recipe) {
        return "recipe=" + recipe.recipeId()
                + " type=" + recipe.recipeType()
                + " source=" + recipe.source().sourceModId()
                + "@" + recipe.source().adapterId()
                + " runtimeDiscovered=" + recipe.source().runtimeVerified();
    }

    private static String formatConversion(CandidateQuantityConversion conversion) {
        return conversion.recipeId() + ":executions=" + conversion.executions()
                + ":inputs=" + formatResources(conversion.inputs())
                + ":outputs=" + formatResources(conversion.outputs())
                + ":byproducts=" + formatResources(conversion.byproducts());
    }

    private static String formatResources(List<ProcessResource> resources) {
        return bracket(resources.stream()
                .map(value -> value.resourceId() + "@" + value.amount())
                .toList());
    }

    private static String formatIds(Set<ResourceId> values) {
        return bracket(values.stream().map(Object::toString).toList());
    }

    private static String bracket(List<String> values) {
        return "[" + String.join(",", values) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
