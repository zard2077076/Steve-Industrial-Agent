package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.industrial.CompositeGraphExpander;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.industrial.RecipeProjectionPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.forge1201.command.CompositeSiteLayoutProbe;
import dev.stevecreate.agent.forge1201.command.LiveRecipeCatalog;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.command.PilotDeploymentCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Read-only survey answering the question that decides where automatic expansion goes:
 * how much of a live registry can this architecture actually build?
 *
 * <p>Expansion has been shown to rediscover a hand-written graph from three recipes.
 * What that does not say is whether real recipe data yields more than a handful of
 * buildable products. A number here is worth more than an estimate: it says whether the
 * next constraint is the shape of the layout, the breadth of the handler set, or the
 * exactness the material ledger demands.</p>
 *
 * <p>It mutates nothing. It reads the recipe manager, projects what policy admits,
 * expands every admitted product and asks the layout contract — entirely offline —
 * whether the result could be built.</p>
 */
public final class DerivableProductSurveyFixture {
    public static final String ENABLE_PROPERTY = "steve_industrial.test.derivableProductSurvey";
    private static final int MAX_REPORTED_EXAMPLES = 12;

    private DerivableProductSurveyFixture() {}

    public static void run(MinecraftServer server, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("DerivableProductSurveyFixture");
        ServerLevel level = server.overworld();
        // One source of recipes, shared with planning. Scanning the recipe manager here
        // while preview() read the planner's catalog meant the survey expanded chains
        // the planner had never heard of, and reported its own inconsistency as a
        // missing recipe.
        List<GoalCatalogEntry> admitted = LiveRecipeCatalog.admittedRecipes(level);
        Map<String, Integer> refusals = new LinkedHashMap<>();
        int scanned = level.getRecipeManager().getRecipes().size();

        Set<ResourceId> raw = LiveRecipeCatalog.rawMaterials(level);
        // Planning reads a world snapshot, and an unloaded chunk yields a snapshot whose
        // fingerprint cannot match the geometry catalog's — reported as
        // WORLD_SNAPSHOT_STALE. The order fixture force-loads its site before planning;
        // this did not, which is the difference between the two paths.
        BlockPos probeBase = level.getSharedSpawnPos().offset(512, 0, 512);
        // Preview is read-only, so every product can share one observed clear site. The
        // old survey generated a unique 96-block-spaced site for every product, leaving
        // thousands of chunks resident and ending an otherwise successful survey with a
        // shutdown StackOverflowError. Reuse one site and restore only the force tickets
        // this fixture added before stopping the server.
        List<ChunkPos> forcedProbeChunks = forceProbeChunks(level, probeBase);
        List<String> buildable = new ArrayList<>();
        Map<String, Integer> expansionRefusals = new LinkedHashMap<>();
        Set<ResourceId> products = new LinkedHashSet<>();
        admitted.forEach(entry -> products.add(entry.target()));

        List<String> plannerFailures = new ArrayList<>();
        List<String> singleMachine = new ArrayList<>();
        int singleMachineExecutable = 0;
        List<String> singleMachineAsChain = new ArrayList<>();
        Map<String, Integer> oneStageRefusals = new LinkedHashMap<>();
        for (ResourceId product : products) {
            CompositeGraphExpander.Result result =
                    CompositeGraphExpander.expand(product, 1, admitted, raw);
            if (!result.success()) {
                expansionRefusals.merge(reason(result.code()), 1, Integer::sum);
                continue;
            }
            if (result.singleMachine().isPresent()) {
                singleMachine.add(product.toString());
                // Classification is not capability: the existing single-machine path
                // accepts eleven hard-coded (target, quantity) pairs. This counts how
                // many derived single-machine goals it would actually take today.
                if (PilotDeploymentCommand.acceptsSingleMachineGoal(level, product, 1)) {
                    singleMachineExecutable++;
                }
                // And separately: how many of them a warehouse could keep in stock. The
                // classification above is about what kind of goal it is; this is about
                // whether the production apparatus will take it. They were the same
                // number — zero — for as long as one machine meant no composite path.
                var asChain = CompositeGraphExpander.expandSingleMachineAsChain(
                        result.singleMachine().orElseThrow(), 1, raw);
                if (!asChain.success()) {
                    oneStageRefusals.merge(reason(asChain.code()), 1, Integer::sum);
                } else if (!CompositeSiteLayoutProbe.isBuildable(asChain.spec().orElseThrow())) {
                    oneStageRefusals.merge("LAYOUT_CONTRACT_REFUSED", 1, Integer::sum);
                } else {
                    var oneStagePreview =
                            PlayerCompositeOrderService.preview(level, product, probeBase);
                    if (oneStagePreview.success()) {
                        singleMachineAsChain.add(product.toString());
                    } else {
                        oneStageRefusals.merge(plannerReason(oneStagePreview.code()), 1,
                                Integer::sum);
                    }
                }
                continue;
            }
            CompositePlayerOrderSpecV1 spec = result.spec().orElseThrow();
            if (!CompositeSiteLayoutProbe.isBuildable(spec)) {
                expansionRefusals.merge("LAYOUT_CONTRACT_REFUSED", 1, Integer::sum);
                continue;
            }
            // The layout contract judges geometry and graph identity, which is all it
            // can see. It cannot know a stage burns fuel, so a chain can satisfy it and
            // still be refused at planning. Ask the same path an order uses, or this
            // number measures something other than what it claims — it read five, then
            // one, and both were wrong.
            PlayerCompositeOrderService.PreviewResult preview =
                    PlayerCompositeOrderService.preview(level, product, probeBase);
            if (!preview.success()) {
                expansionRefusals.merge(plannerReason(preview.code()), 1, Integer::sum);
                // WORLD_SNAPSHOT_STALE is a runtime-fingerprint mismatch between the
                // bound plan, the geometry catalog and the world snapshot — nothing to
                // do with where the site is. Recording which capabilities the chain used
                // is what tells us whether some of them lack runtime support.
                plannerFailures.add(product + " via " + spec.stages().stream()
                        .map(stage -> spec.graph().node(stage.nodeId()).capabilityId().path())
                        .toList());
                continue;
            }
            buildable.add(product.toString());
        }

        logger.info("DERIVABLE_PRODUCT_SURVEY recipesScanned={} recipesAdmitted={} "
                        + "distinctProducts={} buildableProducts={} rawInputs={}",
                scanned, admitted.size(), products.size(), buildable.size(), raw.size());
        logger.info("DERIVABLE_PRODUCT_SURVEY projectionRefusals={}", refusals);
        // The ratio that means something. recipesScanned counts every recipe in the game,
        // hand crafting included, so 226 of 2610 says nothing about this policy — the
        // question is how much of the planner's own machine catalog it turns away.
        // What the planner does not know about, by recipe type. The catalog holds 261 of
        // the game's 2610 recipes and admits 86.6% of those, so coverage is not limited by
        // the admission policy — it is limited by which recipe types reach the catalog at
        // all. This says which capability would be worth building next, and whether the
        // remainder is mostly hand crafting, which no machine executes.
        Map<String, Integer> allTypes = new java.util.TreeMap<>();
        for (var recipe : level.getRecipeManager().getRecipes()) {
            String type = String.valueOf(net.minecraftforge.registries.ForgeRegistries
                    .RECIPE_TYPES.getKey(recipe.getType()));
            allTypes.merge(type, 1, Integer::sum);
        }
        logger.info("DERIVABLE_PRODUCT_SURVEY recipeTypesInGame={}", allTypes);
        // Where the two hundred missing recipes went. Create's eleven admitted types hold
        // 472 recipes and the catalog carries 261; the difference is recipes of a
        // supported type that could not be mapped, and the reasons were being collected
        // and never read.
        logger.info("DERIVABLE_PRODUCT_SURVEY mapping[{}] limitations={}",
                LiveRecipeCatalog.mappingCounts(level),
                LiveRecipeCatalog.mappingLimitations(level));
        logger.info("DERIVABLE_PRODUCT_SURVEY plannerCatalog={} admissionRefusals={}",
                LiveRecipeCatalog.plannerCatalogSize(level),
                LiveRecipeCatalog.admissionRefusals(level));
        logger.info("DERIVABLE_PRODUCT_SURVEY expansionRefusals={}", expansionRefusals);
        // What bounds a quantity limit, reported before anything depends on it: a limit
        // computed from a duration nobody has looked at would be a guess with arithmetic
        // around it.
        Map<String, Integer> durations = new LinkedHashMap<>();
        admitted.forEach(entry -> durations.merge(
                entry.processingTicks().isPresent()
                        ? String.valueOf(entry.processingTicks().getAsLong()) : "unknown",
                1, Integer::sum));
        logger.info("DERIVABLE_PRODUCT_SURVEY processingTicks={}", durations);
        logger.info("DERIVABLE_PRODUCT_SURVEY singleMachineGoals={} singleMachineExecutableToday={}",
                singleMachine.size(), singleMachineExecutable);
        // How many goals would actually need a branching graph. BRANCH_MERGE exists as a
        // hand-written order and cannot be derived, and the obvious next step is to make
        // it derivable — but expansionRefusals has never once contained
        // EXPANSION_BRANCHING_NOT_LINEAR, so the honest question is whether any goal in
        // this catalog needs it at all. Counting is cheaper than building it and finding
        // out, which is the mistake C3b already made once.
        Map<String, Integer> branchWidths = new LinkedHashMap<>();
        List<String> branchingGoals = new ArrayList<>();
        Map<ResourceId, List<GoalCatalogEntry>> byOutput = new LinkedHashMap<>();
        admitted.forEach(recipe -> byOutput
                .computeIfAbsent(recipe.target(), ignored -> new ArrayList<>()).add(recipe));
        for (ResourceId product : products) {
            List<GoalCatalogEntry> makers = byOutput.get(product);
            if (makers == null || makers.isEmpty()) continue;
            GoalCatalogEntry step = makers.stream()
                    .min(java.util.Comparator
                            // Same rule the expander uses, tools included. A copy that
                            // counted differently would measure a selection nobody makes.
                            .comparingInt((GoalCatalogEntry entry) -> entry.inputsPerBatch().size()
                                    + entry.retainedToolsPerBatch().size())
                            .thenComparing(entry -> entry.recipe().toString()))
                    .orElseThrow();
            long makeable = step.inputsPerBatch().keySet().stream()
                    .filter(input -> !raw.contains(input))
                    .filter(byOutput::containsKey)
                    .count();
            branchWidths.merge("inputsThatCanBeMade=" + makeable, 1, Integer::sum);
            if (makeable > 1) branchingGoals.add(product + " via " + step.recipe());
        }
        logger.info("DERIVABLE_PRODUCT_SURVEY branchWidths={}", branchWidths);
        logger.info("DERIVABLE_PRODUCT_SURVEY branchingGoals={} examples={}",
                branchingGoals.size(),
                branchingGoals.stream().sorted().limit(MAX_REPORTED_EXAMPLES).toList());
        logger.info("DERIVABLE_PRODUCT_SURVEY oneStageRefusals={}", oneStageRefusals);
        logger.info("DERIVABLE_PRODUCT_SURVEY singleMachineMaintainable={} examples={}",
                singleMachineAsChain.size(),
                singleMachineAsChain.stream().sorted().limit(MAX_REPORTED_EXAMPLES).toList());
        logger.info("DERIVABLE_PRODUCT_SURVEY plannerFailures={}",
                plannerFailures.stream().sorted().limit(MAX_REPORTED_EXAMPLES).toList());
        // Which capabilities the buildable set actually spans. Picking products to
        // physically verify from an alphabetical list would pick ten variations of the
        // same wood-cutting chain and call it coverage.
        Map<String, Integer> byCapability = new LinkedHashMap<>();
        Map<String, String> firstOfCapability = new LinkedHashMap<>();
        for (String product : buildable) {
            CompositeGraphExpander.Result again = CompositeGraphExpander.expand(
                    ResourceId.parse(product), 1, admitted, raw);
            if (again.spec().isEmpty()) continue;
            CompositePlayerOrderSpecV1 spec = again.spec().orElseThrow();
            String shape = spec.stages().stream()
                    .map(stage -> spec.graph().node(stage.nodeId()).capabilityId().path())
                    .toList().toString();
            byCapability.merge(shape, 1, Integer::sum);
            firstOfCapability.putIfAbsent(shape, product);
        }
        logger.info("DERIVABLE_PRODUCT_SURVEY buildableByShape={}", byCapability);
        logger.info("DERIVABLE_PRODUCT_SURVEY oneProductPerShape={}", firstOfCapability);
        logger.info("DERIVABLE_PRODUCT_SURVEY buildableExamples={}",
                buildable.stream().sorted().limit(MAX_REPORTED_EXAMPLES).toList());
        logger.info("DERIVABLE_PRODUCT_SURVEY singleMachineExamples={}",
                singleMachine.stream().sorted().limit(MAX_REPORTED_EXAMPLES).toList());
        releaseProbeChunks(level, forcedProbeChunks);
        logger.info("DERIVABLE_PRODUCT_SURVEY PASS");
        server.halt(false);
    }

    /**
     * Force-loads the ground a probe plans on, matching what the order fixture does.
     * Planning is read-only, but it snapshots the world, and an unloaded region cannot
     * produce a snapshot consistent with the geometry catalog.
     */
    private static List<ChunkPos> forceProbeChunks(ServerLevel level, BlockPos origin) {
        List<ChunkPos> added = new ArrayList<>();
        for (int chunkX = (origin.getX() - 32) >> 4; chunkX <= (origin.getX() + 48) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - 32) >> 4; chunkZ <= (origin.getZ() + 48) >> 4;
                    chunkZ++) {
                long packed = ChunkPos.asLong(chunkX, chunkZ);
                if (!level.getForcedChunks().contains(packed)) {
                    level.setChunkForced(chunkX, chunkZ, true);
                    added.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        return List.copyOf(added);
    }

    private static void releaseProbeChunks(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    /** Describes a live recipe without deciding anything about it. */
    private static RecipeProjectionPolicy.RecipeCandidate describe(Recipe<?> recipe, Level level) {
        ItemStack result = safeResult(recipe, level);
        Map<ResourceId, Long> inputs = new LinkedHashMap<>();
        boolean tagInputs = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] options = ingredient.getItems();
            if (options.length != 1) {
                tagInputs = true;
                continue;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(options[0].getItem());
            if (itemId == null) {
                tagInputs = true;
                continue;
            }
            inputs.merge(ResourceId.parse(itemId.toString()),
                    (long) Math.max(1, options[0].getCount()), Math::addExact);
        }
        ResourceLocation outputId = result.isEmpty()
                ? null : BuiltInRegistries.ITEM.getKey(result.getItem());
        return new RecipeProjectionPolicy.RecipeCandidate(
                ResourceId.parse(recipe.getId().toString()),
                ResourceId.parse(recipeTypeId(recipe)),
                outputId == null ? ResourceId.parse("minecraft:air") : ResourceId.parse(outputId.toString()),
                result.isEmpty() ? 0 : result.getCount(),
                inputs, tagInputs, false, result.isEmpty() ? 0 : 1);
    }

    private static ItemStack safeResult(Recipe<?> recipe, Level level) {
        try {
            return recipe.getResultItem(level.registryAccess());
        } catch (RuntimeException ignored) {
            // A recipe whose result cannot be resolved without context is simply not
            // a candidate; refusing is correct and needs no diagnosis here.
            return ItemStack.EMPTY;
        }
    }

    private static String recipeTypeId(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return id == null ? "unknown:unknown" : id.toString();
    }

    /** Anything an admitted recipe consumes but none of them produces. */
    private static Set<ResourceId> rawMaterials(List<GoalCatalogEntry> admitted) {
        Set<ResourceId> produced = new LinkedHashSet<>();
        admitted.forEach(entry -> produced.add(entry.target()));
        Set<ResourceId> raw = new LinkedHashSet<>();
        admitted.forEach(entry -> entry.inputsPerBatch().keySet().stream()
                .filter(input -> !produced.contains(input))
                .forEach(raw::add));
        return raw;
    }

    /**
     * Refusal codes carry the offending id; the histogram wants the reason alone —
     * except for chain length, where the number is the finding. Knowing 168 chains are
     * the wrong length says nothing about what to build next; knowing how many are two
     * steps versus four decides it.
     */
    /**
     * Keeps the planner's own words. A bare COMPOSITE_STAGE_PLAN_REFUSED says a stage
     * was refused and nothing about why, which is how an aggregate sent two rounds of
     * work at the wrong cause.
     */
    private static String plannerReason(String code) {
        // Only a stage-plan refusal has the four-part shape below. Everything else is
        // already its own message, and splitting it dropped the useful half — the
        // exact-item refusal came out as the tail of one resource id.
        if (!code.startsWith("COMPOSITE_STAGE_PLAN_REFUSED")) {
            return "PLANNER[" + code.substring(0, Math.min(code.length(), 200)) + "]";
        }
        String[] parts = code.split(":", 4);
        String detail = parts.length >= 4 ? parts[3] : code;
        return "PLANNER[" + detail.substring(0, Math.min(detail.length(), 160)) + "]";
    }

    private static String reason(String code) {
        if (code.startsWith("EXPANSION_CHAIN_LENGTH_UNSUPPORTED")) return code;
        // A contract rejection's message is the finding; the code alone says only that
        // something refused, which is what made the previous survey unactionable.
        if (code.startsWith("EXPANSION_REJECTED_BY_CONTRACT")) {
            int separator = code.indexOf(':');
            String message = separator < 0 ? code : code.substring(separator + 1);
            return "CONTRACT[" + message.substring(0, Math.min(message.length(), 64)) + "]";
        }
        int separator = code.indexOf(':');
        return separator < 0 ? code : code.substring(0, separator);
    }
}
