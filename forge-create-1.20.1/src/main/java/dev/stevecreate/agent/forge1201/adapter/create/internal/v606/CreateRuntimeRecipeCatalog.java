package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeStage;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogAdapter;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogCache;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeMappingWarning;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeMappingWarningCode;
import dev.stevecreate.agent.adapter.api.create.FluidIngredientSemantics;
import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.ImmutableRuntimeRecipeCatalog;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RecipeHeatRequirement;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
import dev.stevecreate.agent.core.planning.RecipeSource;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Exact Forge 1.20.1/Create 6.0.6 runtime-recipe boundary.
 *
 * <p>The live {@link RecipeManager} and its recipe objects never leave this class or remain in the
 * cache. R-02 maps every reliably expressible crushing, milling and pressing recipe rather than assuming
 * that any default recipe ID exists.</p>
 */
public final class CreateRuntimeRecipeCatalog implements RuntimeRecipeCatalogAdapter {
    private static final ResourceId ADAPTER_ID = new ResourceId(
            "steve_industrial", "create_runtime_1_20_1_6_0_6");
    private static final int SCHEMA_VERSION = 1;
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_VERSION_PREFIX = "47.4.";
    private static final String CREATE_VERSION = "6.0.6";
    private static final ResourceId MILLING_TYPE = new ResourceId("create", "milling");
    private static final ResourceId PRESSING_TYPE = new ResourceId("create", "pressing");
    private static final ResourceId CRUSHING_TYPE = new ResourceId("create", "crushing");
    private static final ResourceId CUTTING_TYPE = new ResourceId("create", "cutting");
    private static final ResourceId SPLASHING_TYPE = new ResourceId("create", "splashing");
    private static final ResourceId SMOKING_TYPE = new ResourceId("minecraft", "smoking");
    private static final ResourceId HAUNTING_TYPE = new ResourceId("create", "haunting");
    private static final ResourceId BLASTING_TYPE = new ResourceId("minecraft", "blasting");
    private static final ResourceId MIXING_TYPE = new ResourceId("create", "mixing");
    private static final ResourceId COMPACTING_TYPE = new ResourceId("create", "compacting");
    private static final ResourceId DEPLOYING_TYPE = new ResourceId("create", "deploying");
    private static final List<String> INDUSTRIAL_MOD_IDS = List.of(
            "create", "mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions");

    private final ServerLevel level;
    private final RuntimeFingerprint runtime;
    private final RuntimeRecipeCatalogCache cache = new RuntimeRecipeCatalogCache();
    private volatile boolean reloadInProgress;

    public CreateRuntimeRecipeCatalog(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        Map<String, String> versions = new LinkedHashMap<>();
        for (String modId : INDUSTRIAL_MOD_IDS) {
            ModList.get().getModContainerById(modId).ifPresent(container ->
                    versions.put(modId, container.getModInfo().getVersion().toString()));
        }
        runtime = new RuntimeFingerprint(
                SharedConstants.getCurrentVersion().getName(),
                "forge",
                FMLLoader.versionInfo().forgeVersion(),
                versions,
                ADAPTER_ID.toString(),
                SCHEMA_VERSION);
    }

    @Override
    public ResourceId adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public RuntimeFingerprint runtime() {
        return runtime;
    }

    @Override
    public synchronized RuntimeRecipeCatalogResult snapshot() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    RuntimeKnowledgeFailureCode.WRONG_THREAD,
                    RuntimeKnowledgeStage.RUNTIME_VALIDATION,
                    runtime.canonicalIdentity(),
                    List.of("server_thread:required"),
                    "Runtime recipe discovery must run on the authoritative server thread");
        }
        if (reloadInProgress) {
            return failure(
                    RuntimeKnowledgeFailureCode.DATAPACK_RELOAD_IN_PROGRESS,
                    RuntimeKnowledgeStage.RELOAD_VALIDATION,
                    runtime.canonicalIdentity(),
                    List.of("reload_generation:" + cache.generation()),
                    "Recipe catalog cannot be observed while datapack reload is in progress");
        }
        if (!ModList.get().isLoaded("create")) {
            return failure(
                    RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE,
                    RuntimeKnowledgeStage.RUNTIME_VALIDATION,
                    runtime.canonicalIdentity(),
                    List.of("required_mod:create"),
                    "Required runtime mod is not loaded: create");
        }
        String createVersion = runtime.industrialModVersions().get("create");
        if (!MINECRAFT_VERSION.equals(runtime.minecraftVersion())
                || !"forge".equals(runtime.loader())
                || !runtime.loaderVersion().startsWith(FORGE_VERSION_PREFIX)
                || !supportsCreateVersion(createVersion)) {
            return failure(
                    RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                    RuntimeKnowledgeStage.RUNTIME_VALIDATION,
                    runtime.canonicalIdentity(),
                    List.of(
                            "minecraft:" + runtime.minecraftVersion(),
                            "forge:" + runtime.loaderVersion(),
                            "create:" + Objects.toString(createVersion, "missing")),
                    "Create runtime recipe catalog requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6");
        }

        RecipeManager recipeManager = level.getRecipeManager();
        if (recipeManager == null) {
            return failure(
                    RuntimeKnowledgeFailureCode.RUNTIME_RECIPE_MANAGER_UNAVAILABLE,
                    RuntimeKnowledgeStage.RECIPE_DISCOVERY,
                    runtime.canonicalIdentity(),
                    List.of("recipe_manager:missing"),
                    "The authoritative server world has no RecipeManager");
        }

        List<Recipe<?>> recipes = new ArrayList<>();
        String worldIdentity = level.dimension().location().toString();
        String runtimeFingerprint;
        try {
            recipeManager.getRecipeIds()
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(recipeId -> {
                        Recipe<?> recipe = recipeManager.byKey(recipeId).orElseThrow(() ->
                                new IllegalStateException(
                                        "RecipeManager listed an ID without a recipe: " + recipeId));
                        recipes.add(recipe);
                    });
            runtimeFingerprint = fingerprint(recipes, worldIdentity, cache.generation());
        } catch (RuntimeException failure) {
            return failure(
                    RuntimeKnowledgeFailureCode.RUNTIME_RECIPE_MANAGER_UNAVAILABLE,
                    RuntimeKnowledgeStage.RECIPE_DISCOVERY,
                    runtime.canonicalIdentity(),
                    List.of("recipe_manager:enumeration_failed", "exception:" + failure.getClass().getName()),
                    "RecipeManager enumeration failed without exposing a partial catalog: "
                            + safeMessage(failure));
        }

        Optional<RuntimeRecipeCatalogSnapshot> cached = cache.find(
                worldIdentity, runtimeFingerprint, cache.generation());
        if (cached.isPresent()) {
            return new RuntimeRecipeCatalogResult.Success(cached.orElseThrow());
        }
        try {
            RuntimeRecipeCatalogResult mapped = mapDiscoveredRecipes(
                    recipes, worldIdentity, runtimeFingerprint, cache.generation());
            if (mapped instanceof RuntimeRecipeCatalogResult.Success success) {
                cache.store(success.snapshot());
            }
            return mapped;
        } catch (RuntimeException failure) {
            return failure(
                    RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED,
                    RuntimeKnowledgeStage.RECIPE_MAPPING,
                    runtimeFingerprint,
                    List.of("recipe_mapping:failed", "exception:" + failure.getClass().getName()),
                    "Runtime recipe mapping failed without exposing a partial catalog: "
                            + safeMessage(failure));
        }
    }

    @Override
    public synchronized long invalidateForReload() {
        reloadInProgress = true;
        return cache.invalidate(cache.generation() + 1);
    }

    /** Ends one reload generation; the next read rebuilds from the live RecipeManager. */
    public synchronized void completeReload() {
        reloadInProgress = false;
    }

    public boolean reloadInProgress() {
        return reloadInProgress;
    }

    private RuntimeRecipeCatalogResult mapDiscoveredRecipes(
            Collection<Recipe<?>> recipes,
            String worldIdentity,
            String runtimeFingerprint,
            long reloadGeneration) {
        Objects.requireNonNull(recipes, "recipes");
        List<RuntimeRecipeCatalogEntry> mapped = new ArrayList<>();
        List<RuntimeKnowledgeFailure> limitations = new ArrayList<>();
        List<RuntimeRecipeMappingWarning> warnings = new ArrayList<>();
        int supportedTypeCount = 0;
        for (Recipe<?> recipe : recipes) {
            ResourceId recipeType = recipeType(recipe);
            if (!isSupportedType(recipeType)) {
                continue;
            }
            supportedTypeCount++;
            try {
                Mapping mapping = mapSupportedRecipe(recipe, recipeType, runtimeFingerprint);
                mapped.add(mapping.recipe());
                warnings.addAll(mapping.warnings());
            } catch (RecipeMappingLimitation limitation) {
                limitations.add(limitation(
                        recipe,
                        recipeType,
                        runtimeFingerprint,
                        limitation.code(),
                        limitation.ingredientIdentity(),
                        limitation.getMessage()));
            } catch (RuntimeException unexpected) {
                limitations.add(limitation(
                        recipe,
                        recipeType,
                        runtimeFingerprint,
                        RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED,
                        Optional.empty(),
                        "Unexpected mapping failure " + unexpected.getClass().getName()
                                + ": " + safeMessage(unexpected)));
            }
        }
        if (mapped.isEmpty()) {
            return failure(
                    RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED,
                    RuntimeKnowledgeStage.CATALOG_BUILD,
                    runtimeFingerprint,
                    List.of(
                            "world:" + worldIdentity,
                            "reload_generation:" + reloadGeneration,
                            "recipe_manager:discovered=" + recipes.size(),
                            "supported_types:discovered=" + supportedTypeCount,
                            "supported_types:rejected=" + limitations.size()),
                    "No runtime crushing, milling or pressing recipe could be mapped without guessing");
        }
        return new RuntimeRecipeCatalogResult.Success(new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(mapped),
                runtime,
                runtimeFingerprint,
                worldIdentity,
                reloadGeneration,
                recipes.size(),
                mapped.size(),
                limitations,
                warnings));
    }

    private Mapping mapSupportedRecipe(
            Recipe<?> recipe,
            ResourceId recipeType,
            String runtimeFingerprint) {
            if (recipe instanceof AbstractCookingRecipe cooking) {
                return mapCookingRecipe(
                        cooking, recipeType, runtimeFingerprint);
            }
            if (!(recipe instanceof ProcessingRecipe<?> processing)) {
                throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.RECIPE_TYPE_UNSUPPORTED,
                    Optional.empty(),
                    "Supported Create recipe type is not represented by ProcessingRecipe");
        }
        // A fluid product is a different question from a fluid ingredient, and refused
        // for now rather than forever.
        //
        // Refused because an order today delivers to a chest and settles by counting item
        // stacks, and a fluid has nowhere to land in that. Not because it cannot be done:
        // give the order a tank to deliver into and settlement counts millibuckets the
        // same way it counts stacks, and ProjectFluidLedger, FluidRoute and
        // ForgeFluidTransactionExecutor are already here for exactly that. What is
        // missing is an order model that can say "the product is a fluid, measured in
        // millibuckets" — targetQuantity is 1..64 of an item — plus a tank in the layout
        // and a settlement that reads it.
        //
        // Said plainly because the first version of this comment called it a hole in the
        // arithmetic, which overstated it and would have told the next person not to
        // bother.
        if (!processing.getFluidResults().isEmpty()) {
            StringBuilder produced = new StringBuilder("fluid_product");
            processing.getFluidResults().forEach(result ->
                    produced.append(" out=").append(net.minecraftforge.registries
                            .ForgeRegistries.FLUIDS.getKey(result.getFluid())));
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of(produced.toString()),
                    "A fluid product cannot be delivered to a chest or settled against a "
                            + "ledger that counts item stacks");
        }
        // Water-input mixing is the first exact pour boundary. Fluid stays separate from
        // item Ingredients and from the item ledger; the execution metadata binds the
        // millibuckets to the verified step, while the material bill buys whole buckets.
        // Compacting is deliberately still closed until its own press handler and physical
        // gate receive the same treatment.
        List<ProcessResource> fluidInputs = new ArrayList<>();
        if (!processing.getFluidIngredients().isEmpty()) {
            if (!MIXING_TYPE.equals(recipeType)
                    && !COMPACTING_TYPE.equals(recipeType)) {
                throw new RecipeMappingLimitation(
                        RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                        Optional.of("fluid_pour_unsupported@" + recipeType),
                        "Fluid pouring is physically verified only for Basin/Mixer and Basin/Press recipes");
            }
            LinkedHashMap<ResourceId, Long> amounts = new LinkedHashMap<>();
            for (var ingredient : processing.getFluidIngredients()) {
                FluidIngredientSemantics semantics = CreateRuntimeIngredientMapper.mapFluid(
                        ingredient, runtimeFingerprint);
                ResourceId selected = semantics.kind()
                        == FluidIngredientSemantics.Kind.EXACT_RESOURCE
                        ? semantics.identity().orElse(null)
                        : semantics.runtimeCandidates().stream()
                                .filter(POURABLE_FLUIDS::contains)
                                .findFirst().orElse(null);
                if (selected == null || !POURABLE_FLUIDS.contains(selected)
                        || PlacementItemBinding.bucketsFor(
                                selected, semantics.amountMilliBuckets()).isEmpty()) {
                    throw new RecipeMappingLimitation(
                            RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                            Optional.of("fluid_bucket_unavailable:" + semantics),
                            "The exact fluid has no reviewed whole-bucket material binding");
                }
                amounts.merge(selected, semantics.amountMilliBuckets(), Math::addExact);
            }
            amounts.forEach((fluid, amount) -> fluidInputs.add(new ProcessResource(
                    fluid, GenericResourceType.FLUID, amount)));
        }
        if (processing.getRequiredHeat() != HeatCondition.NONE
                && (!MIXING_TYPE.equals(recipeType)
                || processing.getRequiredHeat() != HeatCondition.HEATED)) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    Optional.of("heat:" + processing.getRequiredHeat()),
                    processing.getRequiredHeat() == HeatCondition.SUPERHEATED
                            ? "SUPERHEATED remains outside the C-08 ordinary-fuel safety boundary"
                            : "This capability does not admit the observed heat requirement");
        }
        List<Ingredient> ingredients;
        RecipeIngredient retainedTool = null;
        if (DEPLOYING_TYPE.equals(recipeType)) {
            if (!(processing instanceof ItemApplicationRecipe deploying)) {
                throw new RecipeMappingLimitation(
                        RuntimeKnowledgeFailureCode.RECIPE_TYPE_UNSUPPORTED,
                        Optional.empty(),
                        "Deployer Phase I requires ItemApplicationRecipe");
            }
            // A deployer that keeps its tool is the ordinary case, not an unsupported
            // one. This refused all of them for want of "typed disposition metadata" —
            // 110 of the registry's 112 deploying recipes, measured — while the metadata
            // it wanted already existed everywhere it mattered: RETAINED_TOOL,
            // ProjectMaterialDisposition.LEASE, and the material factory's retainedTools
            // argument. Nothing was missing except a value being carried from here to
            // there.
            retainedTool = deploying.shouldKeepHeldItem()
                    ? mapIngredient(deploying.getRequiredHeldItem(), runtimeFingerprint) : null;
            ingredients = retainedTool == null
                    ? List.of(deploying.getProcessedItem(), deploying.getRequiredHeldItem())
                    : List.of(deploying.getProcessedItem());
        } else {
            ingredients = List.copyOf(processing.getIngredients());
        }
        boolean singleInputType = CRUSHING_TYPE.equals(recipeType)
                || MILLING_TYPE.equals(recipeType)
                || PRESSING_TYPE.equals(recipeType)
                || CUTTING_TYPE.equals(recipeType)
                || SPLASHING_TYPE.equals(recipeType)
                || SMOKING_TYPE.equals(recipeType)
                || HAUNTING_TYPE.equals(recipeType)
                || BLASTING_TYPE.equals(recipeType);
        // A deployer has two ingredients when it spends its held item and one when it
        // keeps it, because a kept tool is carried as a retained tool and not as an
        // input. Demanding two regardless turned every retained-tool recipe into
        // "ingredients[count=1]" — the same 110 recipes that were previously refused for
        // being retained at all, refused one line further down.
        int requiredCount = DEPLOYING_TYPE.equals(recipeType)
                ? (retainedTool == null ? 2 : 1)
                : singleInputType ? 1 : -1;
        if (ingredients.isEmpty()
                || (requiredCount >= 0 && ingredients.size() != requiredCount)
                || ingredients.stream().anyMatch(Ingredient::isEmpty)) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    Optional.of("ingredients[count=" + ingredients.size() + "]"),
                    requiredCount >= 0
                            ? "Recipe requires exactly " + requiredCount
                                    + " nonempty item ingredient(s)"
                            : "Basin Phase I requires one or more nonempty item ingredients");
        }
        List<RecipeIngredient> inputs = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            inputs.add(mapIngredient(ingredient, runtimeFingerprint));
        }
        List<ProcessingOutput> rollableResults = processing.getRollableResults();
        if (rollableResults.isEmpty()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_none"),
                    "Runtime recipe has no item output");
        }
        ProcessingOutput primary = rollableResults.get(0);
        requireProbability(primary.getChance(), "primary output");
        if (Float.compare(primary.getChance(), 1.0F) != 0) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_probabilistic"),
                    "Primary output is probabilistic and cannot satisfy a deterministic production goal");
        }
        ProcessResource primaryOutput = mapOutput(primary.getStack(), "primary output");
        // Crushing and milling both drop byproducts, and both now carry them through to a
        // spec and an executor that tolerate one. Widened a capability at a time: the
        // others still refuse here, because their executors would call a byproduct an
        // unexpected item and fail the batch for it.
        boolean byproductsSupported = CRUSHING_TYPE.equals(recipeType)
                || MILLING_TYPE.equals(recipeType);
        if (!byproductsSupported && rollableResults.size() != 1) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_multiple_variants"),
                    "C-06 through C-10 Phase I requires one deterministic item output");
        }
        // One byproduct stated as several independent rolls is folded into one line.
        //
        // Create writes a crushing byproduct as separate chances — one guaranteed, one at
        // three quarters, one at a quarter — and this model holds one line per resource.
        // Refusing the recipe over it cost 30 of crushing's own recipes, in the one
        // capability that already supports byproducts at all.
        //
        // Safe because nothing reads a byproduct's declared amount: the executor asks the
        // world what it actually made
        // (output.counts().getOrDefault(byproduct.resourceId(), 0)) and a byproduct never
        // takes part in whether an order is met. The folded amount is what every roll
        // landing would give, an upper bound rather than an expectation, and the warning
        // says so for anyone who later wants to read it.
        LinkedHashMap<ResourceKey, ProcessResource> byproductsByResource = new LinkedHashMap<>();
        List<RuntimeRecipeMappingWarning> warnings = new ArrayList<>();
        ResourceKey primaryKey = ResourceKey.of(primaryOutput);
        for (int index = 1; index < rollableResults.size(); index++) {
            ProcessingOutput output = rollableResults.get(index);
            requireProbability(output.getChance(), "output[" + index + "]");
            ProcessResource byproduct = mapOutput(output.getStack(), "output[" + index + "]");
            ResourceKey key = ResourceKey.of(byproduct);
            if (key.canonical().equals(primaryKey.canonical())) {
                // An extra roll of the primary product is luck, and a plan must not spend
                // it. Create writes crushing as one guaranteed drop plus further chances
                // at the same item; adding those to the guaranteed amount would have an
                // order wait for three when one is all it is owed, and wait until it
                // timed out. The guaranteed quantity is the roll at certainty, and this
                // is not it.
                //
                // Dropped rather than refused: refusing cost 30 of crushing's recipes,
                // and the surplus arrives in the chest either way — settlement already
                // accepts more than was promised.
                warnings.add(new RuntimeRecipeMappingWarning(
                        resourceId(recipe.getId()),
                        RuntimeRecipeMappingWarningCode.INDEPENDENT_ROLLS_FOLDED,
                        List.of("output_index:" + index,
                                "resource:" + byproduct.resourceId(),
                                "chance:" + Float.toString(output.getChance())),
                        "Extra roll of the primary product is not counted toward the "
                                + "guaranteed quantity"));
                continue;
            }
            ProcessResource existing = byproductsByResource.get(key);
            boolean folded = existing != null;
            byproductsByResource.put(key, folded
                    ? new ProcessResource(byproduct.resourceId(), byproduct.resourceType(),
                            Math.addExact(existing.amount(), byproduct.amount()))
                    : byproduct);
            RuntimeRecipeMappingWarningCode warningCode = folded
                    ? RuntimeRecipeMappingWarningCode.INDEPENDENT_ROLLS_FOLDED
                    : Float.compare(output.getChance(), 1.0F) == 0
                    ? RuntimeRecipeMappingWarningCode.GUARANTEED_SECONDARY_OUTPUT
                    : RuntimeRecipeMappingWarningCode.PROBABILISTIC_BYPRODUCT;
            warnings.add(new RuntimeRecipeMappingWarning(
                    resourceId(recipe.getId()),
                    warningCode,
                    List.of(
                            "output_index:" + index,
                            "resource:" + byproduct.resourceId(),
                            "chance:" + Float.toString(output.getChance())),
                    switch (warningCode) {
                        case INDEPENDENT_ROLLS_FOLDED ->
                                "Independent rolls of one byproduct folded into a single upper bound";
                        case PROBABILISTIC_BYPRODUCT ->
                                "Optional byproduct probability is preserved as mapping evidence, not guaranteed output";
                        default ->
                                "Secondary guaranteed Create output is conservatively exposed as an optional byproduct";
                    }));
        }
        List<ProcessResource> byproducts = new ArrayList<>(byproductsByResource.values());

        int duration = processing.getProcessingDuration();
        OptionalLong processingTicks = duration > 0
                ? OptionalLong.of(duration)
                : OptionalLong.empty();
        RuntimeRecipeCatalogEntry entry = new RuntimeRecipeCatalogEntry(
                resourceId(recipe.getId()),
                recipeType,
                inputs,
                List.of(primaryOutput),
                byproducts,
                Set.of(recipeType),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                processingTicks,
                new RecipeSource(
                        ADAPTER_ID,
                        recipe.getId().getNamespace(),
                        runtimeFingerprint,
                        true),
                heatRequirement(
                        resourceId(recipe.getId()),
                        recipeType,
                        processing.getRequiredHeat()),
                retainedTool == null ? List.of() : List.of(retainedTool),
                List.copyOf(fluidInputs));
        return new Mapping(entry, warnings);
    }

    private Mapping mapCookingRecipe(
            AbstractCookingRecipe cooking,
            ResourceId recipeType,
            String runtimeFingerprint) {
        if (!SMOKING_TYPE.equals(recipeType) && !BLASTING_TYPE.equals(recipeType)) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.RECIPE_TYPE_UNSUPPORTED,
                    Optional.empty(),
                    "Only smoking and blasting cooking recipes belong to C-06");
        }
        List<Ingredient> ingredients = cooking.getIngredients();
        if (ingredients.size() != 1 || ingredients.get(0).isEmpty()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    Optional.of("ingredients[count=" + ingredients.size() + "]"),
                    "Fan cooking requires exactly one nonempty item ingredient");
        }
        RecipeIngredient input = mapIngredient(ingredients.get(0), runtimeFingerprint);
        ProcessResource output = mapOutput(
                cooking.getResultItem(level.registryAccess()), "primary output");
        OptionalLong processingTicks = cooking.getCookingTime() > 0
                ? OptionalLong.of(cooking.getCookingTime())
                : OptionalLong.empty();
        RuntimeRecipeCatalogEntry entry = new RuntimeRecipeCatalogEntry(
                resourceId(cooking.getId()),
                recipeType,
                List.of(input),
                List.of(output),
                List.of(),
                Set.of(recipeType),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                processingTicks,
                new RecipeSource(
                        ADAPTER_ID,
                        cooking.getId().getNamespace(),
                        runtimeFingerprint,
                        true));
        return new Mapping(entry, List.of());
    }

    private RecipeIngredient mapIngredient(
            Ingredient ingredient,
            String runtimeFingerprint) {
        RecipeIngredient mapped = CreateRuntimeIngredientMapper.mapItem(
                ingredient, runtimeFingerprint, 1);
        if (mapped instanceof RecipeIngredient.UnsupportedComplexIngredient unsupported) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    Optional.of(bounded(
                            unsupported.canonicalIdentity(),
                            RuntimeKnowledgeFailure.MAX_TRACE_ENTRY_LENGTH)),
                    unsupported.detail());
        }
        return mapped;
    }

    private static ProcessResource mapOutput(ItemStack stack, String identity) {
        if (stack.isEmpty() || stack.getCount() < 1) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_empty_stack"),
                    identity + " is empty");
        }
        if (stack.hasTag()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_nbt"),
                    identity + " carries NBT that the item-resource model cannot preserve");
        }
        return new ProcessResource(
                resourceId(BuiltInRegistries.ITEM.getKey(stack.getItem())),
                GenericResourceType.ITEM,
                stack.getCount());
    }

    private static void requireProbability(float chance, String identity) {
        if (!Float.isFinite(chance) || chance <= 0.0F || chance > 1.0F) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.of("output_invalid_chance"),
                    identity + " has invalid chance " + chance);
        }
    }

    private static RecipeHeatRequirement heatRequirement(
            ResourceId recipeId,
            ResourceId capabilityId,
            HeatCondition heat) {
        if (heat == HeatCondition.NONE) {
            return RecipeHeatRequirement.none(recipeId, capabilityId);
        }
        if (heat != HeatCondition.HEATED || !MIXING_TYPE.equals(capabilityId)) {
            return new RecipeHeatRequirement(
                    id("steve_industrial:heat_requirement/unsupported"),
                    heat == HeatCondition.SUPERHEATED
                            ? RecipeHeatTier.SUPERHEATED
                            : RecipeHeatTier.UNSUPPORTED_HEAT_REQUIREMENT,
                    recipeId,
                    capabilityId,
                    true,
                    Set.of(id("steve_industrial:preflight/heat_requirement_supported")),
                    Set.of(id("steve_industrial:evidence/heat_tier_observed")),
                    Set.of(id("steve_industrial:diagnostic/heat_requirement_unsupported")),
                    Optional.empty());
        }
        return new RecipeHeatRequirement(
                id("steve_industrial:heat_requirement/create_heated_mixing"),
                RecipeHeatTier.HEATED,
                recipeId,
                capabilityId,
                true,
                Set.of(
                        id("steve_industrial:preflight/blaze_burner_owned"),
                        id("steve_industrial:preflight/ordinary_fuel_reserved")),
                Set.of(
                        id("create:evidence/blaze_burner_kindled"),
                        id("create:evidence/mixing_heat_observed")),
                Set.of(
                        id("steve_industrial:diagnostic/fuel_reservation_missing"),
                        id("steve_industrial:diagnostic/heat_requirement_mismatch")),
                Optional.of(new ProcessResource(
                        id("minecraft:coal"), GenericResourceType.ITEM, 1)));
    }

    private RuntimeKnowledgeFailure limitation(
            Recipe<?> recipe,
            ResourceId recipeType,
            String runtimeFingerprint,
            RuntimeKnowledgeFailureCode code,
            Optional<String> ingredientIdentity,
            String detail) {
        return new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.RECIPE_MAPPING,
                Optional.of(resourceId(recipe.getId())),
                Optional.empty(),
                ingredientIdentity,
                ADAPTER_ID,
                runtimeFingerprint,
                List.of("recipe:" + recipe.getId(), "recipe_type:" + recipeType),
                detail);
    }

    /**
     * Fluids a plan can buy, which is the same pair the placement binding already covers.
     *
     * <p>An order pays for a bucket and the executor pours it, exactly as it pays for a
     * water bucket and places a water source behind a fan. Chocolate and honey have no
     * such supply and their two recipes stay refused.</p>
     */
    private static final Set<ResourceId> POURABLE_FLUIDS = Set.of(
            ResourceId.parse("minecraft:water"),
            ResourceId.parse("minecraft:lava"));

    private static boolean isSupportedType(ResourceId recipeType) {
        return CRUSHING_TYPE.equals(recipeType)
                || MILLING_TYPE.equals(recipeType)
                || PRESSING_TYPE.equals(recipeType)
                || CUTTING_TYPE.equals(recipeType)
                || SPLASHING_TYPE.equals(recipeType)
                || SMOKING_TYPE.equals(recipeType)
                || HAUNTING_TYPE.equals(recipeType)
                || BLASTING_TYPE.equals(recipeType)
                || MIXING_TYPE.equals(recipeType)
                || COMPACTING_TYPE.equals(recipeType)
                || DEPLOYING_TYPE.equals(recipeType);
    }

    private static ResourceId recipeType(Recipe<?> recipe) {
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (type == null) {
            return new ResourceId("steve_industrial", "unregistered_recipe_type");
        }
        return resourceId(type);
    }

    private static ResourceId resourceId(ResourceLocation value) {
        Objects.requireNonNull(value, "resource location");
        return new ResourceId(value.getNamespace(), value.getPath());
    }

    private static ResourceId resourceId(String value) {
        return ResourceId.parse(value);
    }

    private static ResourceId id(String value) {
        return resourceId(value);
    }

    private static String bounded(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private String fingerprint(
            Collection<Recipe<?>> recipes,
            String worldIdentity,
            long reloadGeneration) {
        List<String> identities = new ArrayList<>(recipes.size());
        for (Recipe<?> recipe : recipes) {
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            identities.add(recipe.getId() + "|" + Objects.toString(typeId, "unregistered"));
        }
        identities.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, runtime.canonicalIdentity());
            update(digest, worldIdentity);
            update(digest, Long.toString(reloadGeneration));
            for (String identity : identities) {
                update(digest, identity);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM lacks required SHA-256 support", impossible);
        }
    }

    private RuntimeRecipeCatalogResult failure(
            RuntimeKnowledgeFailureCode code,
            RuntimeKnowledgeStage stage,
            String fingerprint,
            List<String> trace,
            String detail) {
        return new RuntimeRecipeCatalogResult.Failure(new RuntimeKnowledgeFailure(
                code,
                stage,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ADAPTER_ID,
                fingerprint,
                trace,
                detail));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static boolean supportsCreateVersion(String version) {
        return CREATE_VERSION.equals(version)
                || (version != null && version.startsWith(CREATE_VERSION + "-"));
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 1_024 ? message : message.substring(0, 1_024);
    }

    private record Mapping(
            RuntimeRecipeCatalogEntry recipe,
            List<RuntimeRecipeMappingWarning> warnings) {
        private Mapping {
            Objects.requireNonNull(recipe, "recipe");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }

        private String canonical() {
            return resourceType.serializedName() + ":" + resourceId;
        }
    }

    private static final class RecipeMappingLimitation extends RuntimeException {
        private final RuntimeKnowledgeFailureCode code;
        private final Optional<String> ingredientIdentity;

        private RecipeMappingLimitation(
                RuntimeKnowledgeFailureCode code,
                Optional<String> ingredientIdentity,
                String detail) {
            super(detail);
            this.code = Objects.requireNonNull(code, "code");
            this.ingredientIdentity = Objects.requireNonNull(
                    ingredientIdentity, "ingredientIdentity");
        }

        private RuntimeKnowledgeFailureCode code() {
            return code;
        }

        private Optional<String> ingredientIdentity() {
            return ingredientIdentity;
        }
    }
}
