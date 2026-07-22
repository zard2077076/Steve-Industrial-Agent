package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeMappingWarning;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;

/** Writes bounded structured evidence derived only from the live isolated RecipeManager. */
public final class DeceasedCraftRuntimeKnowledgeExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> CREATE_TYPES = List.of(
            "create:milling",
            "create:pressing",
            "create:sequenced_assembly",
            "create:mixing",
            "create:compacting",
            "create:crushing",
            "create:cutting",
            "create:deploying",
            "create:splashing",
            "create:haunting",
            "create:filling",
            "create:emptying",
            "create:mechanical_crafting");

    private DeceasedCraftRuntimeKnowledgeExporter() {
    }

    public static ExportSummary export(ServerLevel level, Path actualGameDir) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(actualGameDir, "actualGameDir");
        check(level.getServer().isSameThread(), "RecipeManager export requires the server thread");

        List<Recipe<?>> recipes = level.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .toList();
        check(!recipes.isEmpty(), "RecipeManager exposed no runtime recipes");

        Map<String, Long> typeCounts = countBy(recipes, DeceasedCraftRuntimeKnowledgeExporter::recipeType);
        Map<String, Long> namespaceCounts = countBy(
                recipes, recipe -> recipe.getId().getNamespace());
        Map<String, Long> serializerCounts = countBy(
                recipes, DeceasedCraftRuntimeKnowledgeExporter::recipeSerializer);
        long typeTotal = typeCounts.values().stream().mapToLong(Long::longValue).sum();
        check(typeTotal == recipes.size(), "Recipe type distribution does not account for every recipe");

        RuntimeRecipeCatalogResult catalogResult =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(level).snapshot();
        if (!(catalogResult instanceof RuntimeRecipeCatalogResult.Success success)) {
            RuntimeKnowledgeFailure failure =
                    ((RuntimeRecipeCatalogResult.Failure) catalogResult).failure();
            throw new IllegalStateException(
                    "PACK_RECIPE_MAPPING_FAILED code=" + failure.code()
                            + " stage=" + failure.stage()
                            + " detail=" + failure.detail());
        }
        RuntimeRecipeCatalogSnapshot snapshot = success.snapshot();

        List<SequencedAssemblyRecipe> sequenced = recipes.stream()
                .filter(SequencedAssemblyRecipe.class::isInstance)
                .map(SequencedAssemblyRecipe.class::cast)
                .toList();
        long sequencedPressingSteps = sequenced.stream()
                .flatMap(recipe -> recipe.getSequence().stream())
                .filter(step -> "create:pressing".equals(recipeType(step.getRecipe())))
                .count();

        JsonObject root = new JsonObject();
        root.addProperty("schema", "steve-industrial:r09-runtime-knowledge/v1");
        root.addProperty("evidenceSource", "isolated-authoritative-RecipeManager");
        root.addProperty("staticScriptCountsUsedAsRuntimeTruth", false);
        root.addProperty("worldMutation", false);
        root.addProperty("executionSessionCreated", false);
        root.addProperty("implementationBinding", false);
        root.addProperty("runtimeFingerprint", snapshot.runtimeFingerprint());
        root.addProperty("worldIdentity", snapshot.worldIdentity());
        root.addProperty("reloadGeneration", snapshot.reloadGeneration());

        JsonObject summary = new JsonObject();
        summary.addProperty("recipeManagerTotal", recipes.size());
        summary.addProperty("mappedMillingPressing", snapshot.mappedRecipeCount());
        summary.addProperty("rejectedMillingPressing", snapshot.limitations().size());
        summary.addProperty("mappingWarnings", snapshot.warnings().size());
        summary.addProperty("sequencedAssemblyRecipes", sequenced.size());
        summary.addProperty("sequencedPressingSteps", sequencedPressingSteps);
        summary.addProperty("loadedModContainers", ModList.get().getMods().size());
        root.add("summary", summary);
        root.add("recipeTypeCounts", mapToJson(typeCounts));
        root.add("recipeNamespaceCounts", mapToJson(namespaceCounts));
        root.add("recipeSerializerCounts", mapToJson(serializerCounts));

        JsonObject createCounts = new JsonObject();
        for (String type : CREATE_TYPES) {
            createCounts.addProperty(type, typeCounts.getOrDefault(type, 0L));
        }
        createCounts.addProperty("create:washing_alias_of_splashing",
                typeCounts.getOrDefault("create:splashing", 0L));
        root.add("createRecipeTypeCounts", createCounts);
        root.add("mapping", mappingJson(snapshot));
        root.add("sequencedAssembly", sequencedJson(sequenced));
        root.add("sourceClues", sourceCluesJson(recipes));
        root.add("loadedMods", loadedModsJson());

        Path evidenceRoot = actualGameDir.resolve("r09-evidence").normalize();
        check(evidenceRoot.startsWith(actualGameDir), "Runtime evidence path escaped gameDir");
        Files.createDirectories(evidenceRoot);
        Path output = evidenceRoot.resolve("runtime-knowledge.json");
        Path temporary = evidenceRoot.resolve("runtime-knowledge.json.tmp");
        Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, output,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return new ExportSummary(
                output,
                recipes.size(),
                typeCounts.getOrDefault("create:milling", 0L),
                typeCounts.getOrDefault("create:pressing", 0L),
                sequenced.size(),
                sequencedPressingSteps,
                snapshot.mappedRecipeCount(),
                snapshot.limitations().size(),
                snapshot.warnings().size(),
                snapshot.runtimeFingerprint());
    }

    private static JsonObject mappingJson(RuntimeRecipeCatalogSnapshot snapshot) {
        JsonObject mapping = new JsonObject();
        Map<String, Long> mappedByType = new TreeMap<>();
        JsonArray mappedRecipes = new JsonArray();
        for (RuntimeRecipeCatalogEntry entry : snapshot.catalog().recipes()) {
            mappedByType.merge(entry.recipeType().toString(), 1L, Long::sum);
            JsonObject value = new JsonObject();
            value.addProperty("recipeId", entry.recipeId().toString());
            value.addProperty("recipeType", entry.recipeType().toString());
            value.addProperty("sourceModId", entry.source().sourceModId());
            value.addProperty("sourceAdapter", entry.source().adapterId().toString());
            value.addProperty("runtimeVerified", entry.source().runtimeVerified());
            value.add("inputs", ingredientsJson(entry.inputs()));
            value.add("outputs", resourcesJson(entry.outputs()));
            value.add("optionalByproducts", resourcesJson(entry.optionalByproducts()));
            value.addProperty("processingTicks",
                    entry.processingTicks().isPresent()
                            ? entry.processingTicks().getAsLong()
                            : null);
            mappedRecipes.add(value);
        }
        mapping.add("mappedByType", mapToJson(mappedByType));
        mapping.add("mappedRecipes", mappedRecipes);

        Map<String, Long> limitationsByCode = new TreeMap<>();
        JsonArray limitations = new JsonArray();
        for (RuntimeKnowledgeFailure failure : snapshot.limitations()) {
            limitationsByCode.merge(failure.code().name(), 1L, Long::sum);
            JsonObject value = new JsonObject();
            value.addProperty("code", failure.code().name());
            value.addProperty("stage", failure.stage().name());
            value.addProperty("recipeId", failure.recipeId().map(Object::toString).orElse(""));
            value.addProperty("ingredientIdentity", failure.ingredientIdentity().orElse(""));
            value.addProperty("detail", failure.detail());
            JsonArray trace = new JsonArray();
            failure.trace().forEach(trace::add);
            value.add("trace", trace);
            limitations.add(value);
        }
        mapping.add("limitationsByCode", mapToJson(limitationsByCode));
        mapping.add("limitations", limitations);

        Map<String, Long> warningsByCode = new TreeMap<>();
        JsonArray warnings = new JsonArray();
        for (RuntimeRecipeMappingWarning warning : snapshot.warnings()) {
            warningsByCode.merge(warning.code().name(), 1L, Long::sum);
            JsonObject value = new JsonObject();
            value.addProperty("code", warning.code().name());
            value.addProperty("recipeId", warning.recipeId().toString());
            value.addProperty("detail", warning.detail());
            JsonArray trace = new JsonArray();
            warning.trace().forEach(trace::add);
            value.add("trace", trace);
            warnings.add(value);
        }
        mapping.add("warningsByCode", mapToJson(warningsByCode));
        mapping.add("warnings", warnings);
        return mapping;
    }

    private static JsonObject sequencedJson(List<SequencedAssemblyRecipe> recipes) {
        JsonObject result = new JsonObject();
        result.addProperty("supportStatus", "typed-unsupported");
        result.addProperty("unsupportedCode", "RECIPE_TYPE_UNSUPPORTED");
        result.addProperty("reason",
                "Current RecipeCatalog cannot preserve ordered steps, transitional item and loop count; no sequence is flattened into ordinary pressing");
        JsonArray values = new JsonArray();
        for (SequencedAssemblyRecipe recipe : recipes) {
            JsonObject value = new JsonObject();
            value.addProperty("recipeId", recipe.getId().toString());
            value.addProperty("recipeType", recipeType(recipe));
            value.addProperty("loops", recipe.getLoops());
            value.add("ingredient", recipe.getIngredient().toJson());
            value.add("transitionalItem", itemStackJson(recipe.getTransitionalItem()));
            JsonArray results = new JsonArray();
            recipe.resultPool.forEach(output -> results.add(processingOutputJson(output)));
            value.add("resultPool", results);
            JsonArray steps = new JsonArray();
            List<SequencedRecipe<?>> sequence = recipe.getSequence();
            for (int index = 0; index < sequence.size(); index++) {
                ProcessingRecipe<?> stepRecipe = sequence.get(index).getRecipe();
                JsonObject step = new JsonObject();
                step.addProperty("index", index);
                step.addProperty("recipeId", stepRecipe.getId().toString());
                step.addProperty("recipeType", recipeType(stepRecipe));
                JsonArray ingredients = new JsonArray();
                stepRecipe.getIngredients().forEach(ingredient -> ingredients.add(ingredient.toJson()));
                step.add("ingredients", ingredients);
                JsonArray outputs = new JsonArray();
                stepRecipe.getRollableResults().forEach(output -> outputs.add(processingOutputJson(output)));
                step.add("outputs", outputs);
                steps.add(step);
            }
            value.add("steps", steps);
            value.addProperty("currentCatalogExpressible", false);
            values.add(value);
        }
        result.add("recipes", values);
        return result;
    }

    private static JsonObject sourceCluesJson(List<Recipe<?>> recipes) {
        JsonObject clues = new JsonObject();
        clues.addProperty("provenanceStrength", "namespace-and-serializer-clue-only");
        clues.addProperty("note",
                "RecipeManager does not expose the physical datapack file; IDs and serializers are clues, while static KubeJS search remains separate auxiliary evidence");
        JsonArray kubeJs = new JsonArray();
        recipes.stream()
                .filter(recipe -> "kubejs".equals(recipe.getId().getNamespace()))
                .forEach(recipe -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("recipeId", recipe.getId().toString());
                    value.addProperty("recipeType", recipeType(recipe));
                    value.addProperty("serializer", recipeSerializer(recipe));
                    kubeJs.add(value);
                });
        clues.add("kubejsNamespaceRecipes", kubeJs);
        JsonArray kubeJsCreateGenerated = new JsonArray();
        recipes.stream()
                .filter(recipe -> "create".equals(recipe.getId().getNamespace())
                        && recipe.getId().getPath().startsWith("kjs/"))
                .forEach(recipe -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("recipeId", recipe.getId().toString());
                    value.addProperty("recipeType", recipeType(recipe));
                    value.addProperty("serializer", recipeSerializer(recipe));
                    kubeJsCreateGenerated.add(value);
                });
        clues.add("kubejsCreateGeneratedRecipes", kubeJsCreateGenerated);
        return clues;
    }

    private static JsonArray loadedModsJson() {
        JsonArray values = new JsonArray();
        ModList.get().getMods().stream()
                .sorted(Comparator.comparing(info -> info.getModId()))
                .forEach(info -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("modId", info.getModId());
                    value.addProperty("version", info.getVersion().toString());
                    value.addProperty("displayName", info.getDisplayName());
                    values.add(value);
                });
        return values;
    }

    private static JsonArray ingredientsJson(List<RecipeIngredient> ingredients) {
        JsonArray values = new JsonArray();
        for (RecipeIngredient ingredient : ingredients) {
            JsonObject value = new JsonObject();
            value.addProperty("kind", ingredient.kind().name());
            value.addProperty("canonicalIdentity", ingredient.canonicalIdentity());
            value.addProperty("amount", ingredient.amount());
            JsonArray candidates = new JsonArray();
            ingredient.runtimeCandidates().forEach(candidate -> candidates.add(candidate.toString()));
            value.add("runtimeCandidates", candidates);
            values.add(value);
        }
        return values;
    }

    private static JsonArray resourcesJson(List<ProcessResource> resources) {
        JsonArray values = new JsonArray();
        for (ProcessResource resource : resources) {
            JsonObject value = new JsonObject();
            value.addProperty("resourceId", resource.resourceId().toString());
            value.addProperty("resourceType", resource.resourceType().serializedName());
            value.addProperty("amount", resource.amount());
            values.add(value);
        }
        return values;
    }

    private static JsonObject processingOutputJson(ProcessingOutput output) {
        JsonObject value = itemStackJson(output.getStack());
        value.addProperty("chance", output.getChance());
        return value;
    }

    private static JsonObject itemStackJson(ItemStack stack) {
        JsonObject value = new JsonObject();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        value.addProperty("item", Objects.toString(id, "minecraft:air"));
        value.addProperty("count", stack.getCount());
        value.addProperty("hasNbt", stack.hasTag());
        return value;
    }

    private static Map<String, Long> countBy(
            List<Recipe<?>> recipes,
            java.util.function.Function<Recipe<?>, String> classifier) {
        Map<String, Long> counts = new TreeMap<>();
        recipes.forEach(recipe -> counts.merge(classifier.apply(recipe), 1L, Long::sum));
        return counts;
    }

    private static JsonObject mapToJson(Map<String, Long> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static String recipeType(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return Objects.toString(id, "steve_industrial:unregistered_recipe_type");
    }

    private static String recipeSerializer(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return Objects.toString(id, "steve_industrial:unregistered_recipe_serializer");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    public record ExportSummary(
            Path evidencePath,
            int recipeManagerTotal,
            long millingCount,
            long pressingCount,
            int sequencedAssemblyCount,
            long sequencedPressingStepCount,
            int mappedCount,
            int rejectedCount,
            int warningCount,
            String runtimeFingerprint) {
        public ExportSummary {
            Objects.requireNonNull(evidencePath, "evidencePath");
            Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        }
    }
}
