package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.industrial.RecipeProjectionPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The live recipe registry, projected into the goals an order may be derived from.
 *
 * <p>Describes recipes faithfully and decides nothing: admission is
 * {@link RecipeProjectionPolicy}'s, in core, where it is unit tested. Scanning is
 * memoised per registry generation because a player-facing quote must not walk 2600
 * recipes on every keystroke, and because two quotes for the same goal in one session
 * must agree.</p>
 */
public final class LiveRecipeCatalog {
    private static List<GoalCatalogEntry> cachedRecipes;
    private static Set<ResourceId> cachedRawMaterials;
    private static String cachedFingerprint;

    private LiveRecipeCatalog() {}

    /**
     * Every recipe the policy admits, read from the catalog the planner itself uses.
     *
     * <p>This deliberately does not scan the recipe manager. Doing so gave the expander
     * a different recipe set from the planner's: it would pick the crushing recipe
     * taking raw_copper while the planner only knew the raw_copper_block one, so the
     * chosen recipe was invisible to the component asked to plan it. Two views of the
     * same registry disagreeing is indistinguishable from a missing recipe, and cost
     * several rounds to tell apart.</p>
     */
    /**
     * Why the planner's own recipes were not admitted, counted by reason.
     *
     * <p>Reporting is not admission: this changes nothing about which recipes are used.
     * It exists because the only visible ratio was 226 admitted against 2610 scanned,
     * and that fraction compares a machine-executable set against every recipe in the
     * game including hand crafting — an 8.7% that means nothing. The number worth
     * knowing is how much of the planner's own catalog this policy turns away.</p>
     */
    public static synchronized Map<String, Integer> admissionRefusals(Level level) {
        Map<String, Integer> refusals = new LinkedHashMap<>();
        if (!(level instanceof ServerLevel serverLevel)) return refusals;
        RuntimeRecipeCatalogResult result =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(serverLevel).snapshot();
        if (!(result instanceof RuntimeRecipeCatalogResult.Success success)) return refusals;
        for (RuntimeRecipeCatalogEntry entry : success.snapshot().catalog().recipes()) {
            RecipeProjectionPolicy.Projection projection =
                    RecipeProjectionPolicy.project(describe(entry));
            if (projection.entry().isEmpty()) {
                String code = projection.code();
                int separator = code.indexOf(':');
                refusals.merge(separator < 0 ? code : code.substring(0, separator), 1,
                        Integer::sum);
            }
        }
        return refusals;
    }

    /**
     * Recipes of a supported type that the runtime catalog could not map, by reason.
     *
     * <p>Create's eleven admitted recipe types hold 472 recipes in this registry and the
     * catalog carries 261 of them, so more than two hundred are lost between "this is a
     * machine we can drive" and "the planner knows about it". That gap is inside
     * capabilities that already exist and already have physical evidence, which makes it
     * much cheaper ground than any new machine — but only if the reasons are visible,
     * and they were being collected and never read.</p>
     */
    public static synchronized Map<String, Integer> mappingLimitations(Level level) {
        Map<String, Integer> byReason = new LinkedHashMap<>();
        if (!(level instanceof ServerLevel serverLevel)) return byReason;
        RuntimeRecipeCatalogResult result =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(serverLevel).snapshot();
        if (!(result instanceof RuntimeRecipeCatalogResult.Success success)) return byReason;
        // Keyed by code and by the ingredient that caused it. Knowing that 123 recipes
        // have an unsupported ingredient says nothing about what to build; knowing they
        // are all fluids, or all tags, says exactly what to build next.
        success.snapshot().limitations().forEach(limitation -> byReason.merge(
                key(limitation), 1, Integer::sum));
        return byReason;
    }

    /**
     * How one limitation is grouped in the survey line.
     *
     * <p>An unexpected mapping failure gets its recipe and its detail verbatim. Those are
     * the ones nobody predicted, so a count of them says nothing and there are never many
     * — the whole registry produced one. Everything else groups by shape, because a
     * hundred distinct item ids in a log line is not a finding.</p>
     */
    private static String key(RuntimeKnowledgeFailure limitation) {
        if (limitation.code() == RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED) {
            String detail = limitation.detail();
            return limitation.code().name() + "["
                    + limitation.recipeId().map(ResourceId::toString).orElse("unknown") + " "
                    + detail.substring(0, Math.min(detail.length(), 140)) + "]";
        }
        // Grouped by capability too. Knowing that 66 recipes are refused for byproducts
        // does not say what it would cost to accept them; knowing they are all mixing
        // says one spec and one executor, and knowing they are spread across eight says
        // eight of each.
        String capability = limitation.trace().stream()
                .filter(entry -> entry.startsWith("recipe_type:"))
                .findFirst()
                .map(entry -> entry.substring("recipe_type:".length()))
                .orElse("unknown");
        return limitation.code().name() + limitation.ingredientIdentity()
                .map(identity -> "[" + shape(identity) + "]").orElse("")
                + "@" + capability;
    }

    /**
     * The kind of an ingredient identity, not the identity itself.
     *
     * <p>Two hundred distinct item ids in a survey line is unreadable and says nothing;
     * "tag" against "fluid" against "exact" is the whole answer.</p>
     */
    private static String shape(String identity) {
        // Output reasons name themselves; they are already the answer.
        if (identity.startsWith("output_")) return identity;
        if (identity.startsWith("#") || identity.contains("tag")) return "tag";
        // A fluid identity already names its fluids and which side they are on.
        if (identity.startsWith("fluid")) return identity;
        if (identity.isBlank()) return "blank";
        return identity.contains(":") ? identity.substring(0, identity.indexOf(':')) : "other";
    }

    /** Discovered against mapped, so the loss is a subtraction rather than a guess. */
    public static synchronized String mappingCounts(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return "unavailable";
        RuntimeRecipeCatalogResult result =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(serverLevel).snapshot();
        if (!(result instanceof RuntimeRecipeCatalogResult.Success success)) return "unavailable";
        var snapshot = success.snapshot();
        return "discovered=" + snapshot.discoveredRecipeCount()
                + " mapped=" + snapshot.mappedRecipeCount()
                + " limitations=" + snapshot.limitations().size()
                + " warnings=" + snapshot.warnings().size();
    }

    /** How many recipes the planner itself knows, before this policy judges any of them. */
    public static synchronized int plannerCatalogSize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return 0;
        RuntimeRecipeCatalogResult result =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(serverLevel).snapshot();
        return result instanceof RuntimeRecipeCatalogResult.Success success
                ? success.snapshot().catalog().recipes().size() : 0;
    }

    public static synchronized List<GoalCatalogEntry> admittedRecipes(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return List.of();
        RuntimeRecipeCatalogResult result =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(serverLevel).snapshot();
        if (!(result instanceof RuntimeRecipeCatalogResult.Success success)) return List.of();
        RuntimeRecipeCatalogSnapshot snapshot = success.snapshot();
        if (cachedRecipes == null || !snapshot.runtimeFingerprint().equals(cachedFingerprint)) {
            List<GoalCatalogEntry> admitted = new ArrayList<>();
            for (RuntimeRecipeCatalogEntry entry : snapshot.catalog().recipes()) {
                RecipeProjectionPolicy.project(describe(entry)).entry().ifPresent(admitted::add);
            }
            cachedRecipes = List.copyOf(admitted);
            cachedRawMaterials = rawMaterials(cachedRecipes);
            cachedFingerprint = snapshot.runtimeFingerprint();
        }
        return cachedRecipes;
    }

    /** Describes one planner-known recipe without deciding anything about it. */
    private static RecipeProjectionPolicy.RecipeCandidate describe(RuntimeRecipeCatalogEntry entry) {
        Map<ResourceId, Long> inputs = new LinkedHashMap<>();
        boolean inexactInputs = false;
        for (RecipeIngredient ingredient : entry.inputs()) {
            if (ingredient instanceof RecipeIngredient.ExactResource exact) {
                inputs.merge(exact.resourceId(), exact.amount(), Math::addExact);
            } else {
                // A tag names a set; the ledger reserves one exact thing. Choosing from
                // the set is what makes the recipe orderable, and it is the same kind of
                // choice the expander already makes when it picks which recipe to use.
                //
                // 93 recipes were refused rather than choosing. That refusal read as a
                // capability we lack, and it is not: what we lacked was a rule for
                // picking. Vanilla first, then alphabetical, so "any log" lands on an oak
                // log rather than whichever mod sorted first, and the same registry always
                // yields the same pick.
                //
                // The cost, plainly: a player who has cherry logs and no oak is told they
                // are short of oak. They can see which item was chosen — it is on the bill
                // they approve before anything is reserved — which is why this is a worse
                // answer than reserving the whole set but a much better one than refusing.
                ResourceId chosen = chooseCandidate(ingredient);
                if (chosen == null) {
                    inexactInputs = true;
                } else {
                    inputs.merge(chosen, ingredient.amount(), Math::addExact);
                }
            }
        }
        // Tools come across separately and stay separate. Merging a deployer's saw into
        // inputs would multiply it by the batch count and then ask the player to hand
        // over six saws to make six planks.
        Map<ResourceId, Long> retainedTools = new LinkedHashMap<>();
        for (RecipeIngredient tool : entry.retainedTools()) {
            if (tool instanceof RecipeIngredient.ExactResource exact) {
                retainedTools.merge(exact.resourceId(), exact.amount(), Math::addExact);
            } else {
                // A tool named by a tag gets the same treatment an ingredient does, by the
                // same rule. Deployers are usually told to hold "any saw" rather than one
                // specific saw, so refusing here undid much of what choosing ingredients
                // had just bought.
                ResourceId chosenTool = chooseCandidate(tool);
                if (chosenTool == null) {
                    inexactInputs = true;
                } else {
                    retainedTools.merge(chosenTool, tool.amount(), Math::addExact);
                }
            }
        }
        // Millibuckets, carried separately all the way. Merging them into inputs would put
        // a thousand of something into a ledger that counts item stacks.
        Map<ResourceId, Long> fluidInputs = new LinkedHashMap<>();
        entry.fluidInputs().forEach(fluid ->
                fluidInputs.merge(fluid.resourceId(), fluid.amount(), Math::addExact));
        ProcessResource output = entry.outputs().isEmpty() ? null : entry.outputs().get(0);
        // The duration comes along. It is what bounds how much material one step can
        // move before the executor's fixed deadline, and without it a quantity limit
        // would have to be one number guessed for every recipe.
        return new RecipeProjectionPolicy.RecipeCandidate(
                entry.recipeId(), entry.recipeType(),
                output == null ? ResourceId.parse("minecraft:air") : output.resourceId(),
                output == null ? 0 : output.amount(), inputs, inexactInputs,
                !entry.optionalByproducts().isEmpty(), entry.outputs().size(),
                entry.processingTicks(), Map.copyOf(retainedTools),
                Map.copyOf(fluidInputs));
    }

    /**
     * Which member of a tag to reserve.
     *
     * <p>Vanilla namespace first, then alphabetical, so "any log" lands on an oak log
     * rather than whichever mod happened to sort first, and the same registry always
     * yields the same pick. One rule, used for ingredients and for the tools a deployer
     * holds — those are tags at least as often.</p>
     */
    private static ResourceId chooseCandidate(RecipeIngredient ingredient) {
        return ingredient.runtimeCandidates().stream()
                .min(Comparator
                        .comparing((ResourceId id) -> id.namespace().equals("minecraft") ? 0 : 1)
                        .thenComparing(ResourceId::toString))
                .orElse(null);
    }

    /** Inputs an admitted recipe consumes that none of them produces. */
    public static synchronized Set<ResourceId> rawMaterials(Level level) {
        admittedRecipes(level);
        return cachedRawMaterials;
    }

    /** Drops the memo; a reload changes the runtime fingerprint anyway. */
    public static synchronized void invalidate() {
        cachedRecipes = null;
        cachedRawMaterials = null;
        cachedFingerprint = null;
    }

    private static Set<ResourceId> rawMaterials(List<GoalCatalogEntry> admitted) {
        Set<ResourceId> produced = new LinkedHashSet<>();
        admitted.forEach(entry -> produced.add(entry.target()));
        Set<ResourceId> raw = new LinkedHashSet<>();
        admitted.forEach(entry -> entry.inputsPerBatch().keySet().stream()
                .filter(input -> !produced.contains(input))
                .forEach(raw::add));
        return Set.copyOf(raw);
    }
}
