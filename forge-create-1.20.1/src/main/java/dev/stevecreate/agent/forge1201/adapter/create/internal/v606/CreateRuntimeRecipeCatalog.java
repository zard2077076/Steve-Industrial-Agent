package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.ImmutableRuntimeRecipeCatalog;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Exact Forge 1.20.1/Create 6.0.6 runtime-recipe boundary.
 *
 * <p>The live {@link RecipeManager} and its recipe objects never leave this class or remain in the
 * cache. R-02 maps every reliably expressible milling and pressing recipe rather than assuming
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
                    "No runtime milling or pressing recipe could be mapped without guessing");
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
        if (!(recipe instanceof ProcessingRecipe<?> processing)) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.RECIPE_TYPE_UNSUPPORTED,
                    Optional.empty(),
                    "Supported Create recipe type is not represented by ProcessingRecipe");
        }
        List<Ingredient> ingredients = processing.getIngredients();
        if (ingredients.size() != 1 || ingredients.get(0).isEmpty()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                    Optional.of("ingredients[count=" + ingredients.size() + "]"),
                    "Milling and pressing currently require exactly one nonempty item ingredient");
        }
        RecipeIngredient input = mapIngredient(ingredients.get(0), runtimeFingerprint);
        List<ProcessingOutput> rollableResults = processing.getRollableResults();
        if (rollableResults.isEmpty()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.empty(),
                    "Runtime recipe has no item output");
        }
        ProcessingOutput primary = rollableResults.get(0);
        requireProbability(primary.getChance(), "primary output");
        if (Float.compare(primary.getChance(), 1.0F) != 0) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.empty(),
                    "Primary output is probabilistic and cannot satisfy a deterministic production goal");
        }
        ProcessResource primaryOutput = mapOutput(primary.getStack(), "primary output");
        List<ProcessResource> byproducts = new ArrayList<>();
        List<RuntimeRecipeMappingWarning> warnings = new ArrayList<>();
        Set<ResourceKey> seenOutputs = new TreeSet<>(Comparator.comparing(ResourceKey::canonical));
        seenOutputs.add(ResourceKey.of(primaryOutput));
        for (int index = 1; index < rollableResults.size(); index++) {
            ProcessingOutput output = rollableResults.get(index);
            requireProbability(output.getChance(), "output[" + index + "]");
            ProcessResource byproduct = mapOutput(output.getStack(), "output[" + index + "]");
            if (!seenOutputs.add(ResourceKey.of(byproduct))) {
                throw new RecipeMappingLimitation(
                        RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                        Optional.empty(),
                        "Repeated output resource cannot preserve independent roll semantics: "
                                + byproduct.resourceId());
            }
            byproducts.add(byproduct);
            RuntimeRecipeMappingWarningCode warningCode = Float.compare(output.getChance(), 1.0F) == 0
                    ? RuntimeRecipeMappingWarningCode.GUARANTEED_SECONDARY_OUTPUT
                    : RuntimeRecipeMappingWarningCode.PROBABILISTIC_BYPRODUCT;
            warnings.add(new RuntimeRecipeMappingWarning(
                    resourceId(recipe.getId()),
                    warningCode,
                    List.of(
                            "output_index:" + index,
                            "resource:" + byproduct.resourceId(),
                            "chance:" + Float.toString(output.getChance())),
                    warningCode == RuntimeRecipeMappingWarningCode.PROBABILISTIC_BYPRODUCT
                            ? "Optional byproduct probability is preserved as mapping evidence, not guaranteed output"
                            : "Secondary guaranteed Create output is conservatively exposed as an optional byproduct"));
        }

        int duration = processing.getProcessingDuration();
        OptionalLong processingTicks = duration > 0
                ? OptionalLong.of(duration)
                : OptionalLong.empty();
        RuntimeRecipeCatalogEntry entry = new RuntimeRecipeCatalogEntry(
                resourceId(recipe.getId()),
                recipeType,
                List.of(input),
                List.of(primaryOutput),
                byproducts,
                Set.of(recipeType),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                processingTicks,
                new RecipeSource(
                        ADAPTER_ID,
                        recipe.getId().getNamespace(),
                        runtimeFingerprint,
                        true));
        return new Mapping(entry, warnings);
    }

    private RecipeIngredient mapIngredient(
            Ingredient ingredient,
            String runtimeFingerprint) {
        JsonElement json = ingredient.toJson();
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.size() == 1 && object.has("item") && object.get("item").isJsonPrimitive()) {
                return new RecipeIngredient.ExactResource(
                        resourceId(object.get("item").getAsString()), 1);
            }
            if (object.size() == 1 && object.has("tag") && object.get("tag").isJsonPrimitive()) {
                return new RecipeIngredient.TagReference(
                        resourceId(object.get("tag").getAsString()),
                        runtimeIngredientCandidates(ingredient),
                        runtimeFingerprint,
                        1);
            }
            unsupportedIngredient(json, "Ingredient object contains custom or compound semantics");
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            List<ResourceId> candidates = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    unsupportedIngredient(json, "Ingredient alternative is not an item object");
                }
                JsonObject object = element.getAsJsonObject();
                if (object.size() != 1
                        || !object.has("item")
                        || !object.get("item").isJsonPrimitive()) {
                    unsupportedIngredient(
                            json,
                            "Ingredient alternatives mix tags or custom semantics that cannot be preserved as one identity");
                }
                candidates.add(resourceId(object.get("item").getAsString()));
            }
            if (candidates.size() == 1) {
                return new RecipeIngredient.ExactResource(candidates.get(0), 1);
            }
            if (candidates.size() > 1) {
                return new RecipeIngredient.AnyOfResources(candidates, 1);
            }
        }
        unsupportedIngredient(json, "Ingredient JSON shape is empty or unsupported");
        throw new IllegalStateException("unreachable");
    }

    private List<ResourceId> runtimeIngredientCandidates(Ingredient ingredient) {
        TreeSet<ResourceId> candidates = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                candidates.add(resourceId(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            }
        }
        return List.copyOf(candidates);
    }

    private static void unsupportedIngredient(JsonElement json, String detail) {
        String identity = bounded(json.toString(), RecipeIngredient.MAX_IDENTITY_LENGTH);
        RecipeIngredient.UnsupportedComplexIngredient unsupported =
                new RecipeIngredient.UnsupportedComplexIngredient(identity, detail, 1);
        throw new RecipeMappingLimitation(
                RuntimeKnowledgeFailureCode.INGREDIENT_UNSUPPORTED,
                Optional.of(bounded(
                        unsupported.canonicalIdentity(),
                        RuntimeKnowledgeFailure.MAX_TRACE_ENTRY_LENGTH)),
                detail);
    }

    private static ProcessResource mapOutput(ItemStack stack, String identity) {
        if (stack.isEmpty() || stack.getCount() < 1) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.empty(),
                    identity + " is empty");
        }
        if (stack.hasTag()) {
            throw new RecipeMappingLimitation(
                    RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED,
                    Optional.empty(),
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
                    Optional.empty(),
                    identity + " has invalid chance " + chance);
        }
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

    private static boolean isSupportedType(ResourceId recipeType) {
        return MILLING_TYPE.equals(recipeType) || PRESSING_TYPE.equals(recipeType);
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
