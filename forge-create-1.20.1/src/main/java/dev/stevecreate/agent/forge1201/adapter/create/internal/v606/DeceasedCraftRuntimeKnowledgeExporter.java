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
import dev.stevecreate.agent.adapter.api.create.CapabilityCensusSummary;
import dev.stevecreate.agent.adapter.api.create.CapabilityOutput;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeLimitation;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import dev.stevecreate.agent.adapter.api.create.FluidIngredientSemantics;
import dev.stevecreate.agent.adapter.api.create.RuntimeCapabilityCensusSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
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
        RuntimeCapabilityCensusSnapshot capabilityCensus =
                CreateRuntimeCapabilityCensus.capture(level, snapshot);

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
        summary.addProperty("mappedCrushingMillingPressing", snapshot.mappedRecipeCount());
        summary.addProperty("rejectedCrushingMillingPressing", snapshot.limitations().size());
        // Retained for consumers of the R-09 v1 schema; Phase IV uses the correctly named fields.
        summary.addProperty("mappedMillingPressing", snapshot.mappedRecipeCount());
        summary.addProperty("rejectedMillingPressing", snapshot.limitations().size());
        summary.addProperty("mappingWarnings", snapshot.warnings().size());
        summary.addProperty("sequencedAssemblyRecipes", sequenced.size());
        summary.addProperty("sequencedPressingSteps", sequencedPressingSteps);
        summary.addProperty("loadedModContainers", ModList.get().getMods().size());
        summary.addProperty("capabilityExpansionRecipes", capabilityCensus.recipes().size());
        summary.addProperty("capabilityExpansionSupportedPhaseI", capabilityCensus.recipes().stream()
                .filter(recipe -> recipe.support()
                        == dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSupport.SUPPORTED_PHASE_I)
                .count());
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
        root.add("c05CrushingAcceptance", c05CrushingAcceptanceJson(snapshot));
        root.add("capabilityExpansion", capabilityCensusJson(capabilityCensus));
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
                typeCounts.getOrDefault("create:crushing", 0L),
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

    private static JsonObject c05CrushingAcceptanceJson(
            RuntimeRecipeCatalogSnapshot snapshot) {
        ResourceId preferred = ResourceId.parse(
                "deceasedcraft:crushing_wheel/crushing/raw_materials/copper");
        List<RuntimeRecipeCatalogEntry> packCrushing = snapshot.catalog().recipes().stream()
                .filter(entry -> entry.recipeType().equals(
                        ResourceId.parse("create:crushing")))
                .filter(entry -> entry.recipeId().namespace().equals("deceasedcraft"))
                .sorted(Comparator
                        .comparing((RuntimeRecipeCatalogEntry entry) ->
                                !entry.recipeId().equals(preferred))
                        .thenComparing(entry -> entry.recipeId().toString()))
                .toList();
        JsonObject value = new JsonObject();
        value.addProperty("selectionPolicy",
                "item-only deterministic primary output; prefer bounded raw-copper tag recipe");
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        if (packCrushing.isEmpty()) {
            value.addProperty("status", "NOT_PRESENT");
            value.addProperty("reason",
                    "No mapped deceasedcraft-namespace Create crushing recipe has a deterministic primary output");
            return value;
        }
        RuntimeRecipeCatalogEntry selected = packCrushing.get(0);
        value.addProperty("status", "PRESENT");
        value.addProperty("recipeId", selected.recipeId().toString());
        value.addProperty("recipeType", selected.recipeType().toString());
        value.addProperty("runtimeVerified", selected.source().runtimeVerified());
        value.addProperty("runtimeFingerprint", selected.source().runtimeFingerprint());
        value.add("inputs", ingredientsJson(selected.inputs()));
        value.add("outputs", resourcesJson(selected.outputs()));
        value.add("optionalByproducts", resourcesJson(selected.optionalByproducts()));
        value.addProperty("processingTicks",
                selected.processingTicks().isPresent()
                        ? selected.processingTicks().getAsLong()
                        : null);
        value.addProperty("safeForPhaseIv", true);
        value.addProperty("safetyBoundary",
                "item entity input and chest output only; no block mining, entity interaction, container opening, fluid, radiation, or arbitrary world use");
        return value;
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

    private static JsonObject capabilityCensusJson(RuntimeCapabilityCensusSnapshot snapshot) {
        JsonObject census = new JsonObject();
        census.addProperty("schema", "steve-industrial:create-capability-census/v1");
        census.addProperty("evidenceSource", "authoritative-server-RecipeManager");
        census.addProperty("serverAuthoritative", snapshot.serverAuthoritative());
        census.addProperty("worldMutation", snapshot.worldMutation());
        census.addProperty("executorCreated", false);
        census.addProperty("runtimeFingerprint", snapshot.runtimeFingerprint());
        census.addProperty("worldIdentity", snapshot.worldIdentity());
        census.addProperty("reloadGeneration", snapshot.reloadGeneration());
        census.addProperty("recipeManagerTotal", snapshot.recipeManagerCount());
        census.addProperty("targetRecipeTotal", snapshot.recipes().size());

        Map<String, Long> counts = new TreeMap<>();
        snapshot.targetRecipeTypeCounts().forEach((key, value) ->
                counts.put(key.toString(), value));
        census.add("targetRecipeTypeCounts", mapToJson(counts));

        JsonObject supportMatrix = new JsonObject();
        for (CreateCapabilityId capability : CreateCapabilityId.canonicalValues()) {
            CapabilityCensusSummary summary = snapshot.summaries().get(capability);
            JsonObject value = new JsonObject();
            value.addProperty("phaseCode", capability.phaseCode());
            value.addProperty("recipeType", capability.recipeType().toString());
            value.addProperty("implementationCapabilityId",
                    capability.implementationCapabilityId().toString());
            value.addProperty("discovered", summary.discovered());
            value.addProperty("supportedPhaseI", summary.supportedPhaseI());
            value.addProperty("semanticsOnly", summary.semanticsOnly());
            value.addProperty("unsupported", summary.unsupported());
            value.addProperty("notPresent", summary.notPresent());
            JsonArray candidates = new JsonArray();
            summary.acceptanceCandidates().forEach(candidate -> candidates.add(candidate.toString()));
            value.add("acceptanceCandidates", candidates);
            supportMatrix.add(capability.name(), value);
        }
        census.add("supportMatrix", supportMatrix);

        JsonArray recipes = new JsonArray();
        for (CapabilityRecipeSemantics recipe : snapshot.recipes()) {
            JsonObject value = new JsonObject();
            value.addProperty("recipeId", recipe.recipeId().toString());
            value.addProperty("recipeType", recipe.recipeType().toString());
            value.addProperty("capability", recipe.capability().name());
            value.addProperty("phaseCode", recipe.capability().phaseCode());
            value.addProperty("support", recipe.support().name());
            value.addProperty("processingTicks",
                    recipe.processingTicks().isPresent()
                            ? recipe.processingTicks().getAsLong()
                            : null);
            value.add("itemInputs", ingredientsJson(recipe.itemInputs()));
            value.add("fluidInputs", fluidIngredientsJson(recipe.fluidInputs()));
            value.add("fluidOutputs", resourcesJson(recipe.fluidOutputs()));
            value.add("itemOutputs", capabilityOutputsJson(recipe.itemOutputs().outputs()));
            JsonObject probability = new JsonObject();
            probability.addProperty("denominator", recipe.probabilisticOutputs().denominator());
            probability.addProperty("hasProbabilisticOutput",
                    recipe.probabilisticOutputs().hasProbabilisticOutput());
            probability.addProperty("deterministicPrimary",
                    recipe.probabilisticOutputs().primaryIsDeterministic());
            probability.addProperty("independentRolls", recipe.itemOutputs().independentRolls());
            probability.addProperty("outputOrderPreserved",
                    recipe.itemOutputs().outputOrderPreserved());
            value.add("probability", probability);
            value.add("requirements", requirementsJson(recipe));
            value.add("limitations", limitationsJson(recipe.limitations()));
            recipes.add(value);
        }
        census.add("recipes", recipes);
        return census;
    }

    private static JsonArray fluidIngredientsJson(List<FluidIngredientSemantics> ingredients) {
        JsonArray values = new JsonArray();
        for (FluidIngredientSemantics ingredient : ingredients) {
            JsonObject value = new JsonObject();
            value.addProperty("kind", ingredient.kind().name());
            value.addProperty("identity", ingredient.identity().map(Object::toString).orElse(""));
            value.addProperty("amountMilliBuckets", ingredient.amountMilliBuckets());
            value.addProperty("runtimeFingerprint", ingredient.runtimeFingerprint().orElse(""));
            value.addProperty("unsupportedDetail", ingredient.unsupportedDetail().orElse(""));
            JsonArray candidates = new JsonArray();
            ingredient.runtimeCandidates().forEach(candidate -> candidates.add(candidate.toString()));
            value.add("runtimeCandidates", candidates);
            values.add(value);
        }
        return values;
    }

    private static JsonArray capabilityOutputsJson(List<CapabilityOutput> outputs) {
        JsonArray values = new JsonArray();
        for (CapabilityOutput output : outputs) {
            JsonObject value = new JsonObject();
            value.addProperty("outputIndex", output.outputIndex());
            value.addProperty("resourceId", output.resource().resourceId().toString());
            value.addProperty("resourceType", output.resource().resourceType().serializedName());
            value.addProperty("amount", output.resource().amount());
            value.addProperty("probabilityPerMillion", output.probabilityPerMillion());
            value.addProperty("primary", output.primary());
            value.addProperty("guaranteed", output.guaranteed());
            values.add(value);
        }
        return values;
    }

    private static JsonObject requirementsJson(CapabilityRecipeSemantics recipe) {
        var environment = recipe.environment();
        JsonObject value = new JsonObject();
        value.addProperty("heat", environment.heat().name());
        value.addProperty("medium", environment.medium().name());
        value.addProperty("itemProcessingOnly", environment.itemProcessingOnly());
        value.addProperty("arbitraryWorldInteractionForbidden",
                environment.arbitraryWorldInteractionForbidden());
        value.addProperty("playerInventoryForbidden",
                environment.heldItem().playerInventoryForbidden());
        JsonObject direction = new JsonObject();
        direction.addProperty("required", environment.directionalFlow().required());
        direction.addProperty("rotationDirection",
                environment.directionalFlow().rotationDirection().name());
        direction.addProperty("inputOutputOpposed",
                environment.directionalFlow().inputOutputOpposed());
        direction.addProperty("minimumClearanceBlocks",
                environment.directionalFlow().minimumClearanceBlocks());
        value.add("directionalFlow", direction);
        JsonObject airflow = new JsonObject();
        airflow.addProperty("required", environment.airflow().required());
        airflow.addProperty("medium", environment.airflow().medium().name());
        airflow.addProperty("minimumReachBlocks", environment.airflow().minimumReachBlocks());
        airflow.addProperty("obstructionFreePath", environment.airflow().obstructionFreePath());
        airflow.addProperty("liveFlowObservationRequired",
                environment.airflow().liveFlowObservationRequired());
        airflow.addProperty("dwellCompletionRequired",
                environment.airflow().dwellCompletionRequired());
        value.add("airflow", airflow);
        JsonObject basin = new JsonObject();
        basin.addProperty("required", environment.basin().required());
        basin.addProperty("maximumItemInputs", environment.basin().maximumItemInputs());
        basin.addProperty("maximumFluidInputs", environment.basin().maximumFluidInputs());
        basin.addProperty("heatSourceObservationRequired",
                environment.basin().heatSourceObservationRequired());
        basin.addProperty("outputCapacityObservationRequired",
                environment.basin().outputCapacityObservationRequired());
        value.add("basin", basin);
        JsonObject held = new JsonObject();
        held.addProperty("present", environment.heldItem().heldItem().isPresent());
        held.addProperty("identity", environment.heldItem().heldItem()
                .map(RecipeIngredient::canonicalIdentity).orElse(""));
        held.addProperty("consumed", environment.heldItem().consumed());
        held.addProperty("stateBeforeAfterRequired",
                environment.heldItem().stateBeforeAfterRequired());
        value.add("heldItem", held);
        JsonObject tool = new JsonObject();
        tool.addProperty("present", environment.tool().tool().isPresent());
        tool.addProperty("identity", environment.tool().tool()
                .map(RecipeIngredient::canonicalIdentity).orElse(""));
        tool.addProperty("consumption", environment.tool().consumption().name());
        tool.addProperty("stateReadbackRequired", environment.tool().stateReadbackRequired());
        value.add("tool", tool);
        JsonObject catalyst = new JsonObject();
        catalyst.addProperty("present", environment.catalyst().catalyst().isPresent());
        catalyst.addProperty("identity", environment.catalyst().catalyst()
                .map(RecipeIngredient::canonicalIdentity).orElse(""));
        catalyst.addProperty("returnedAfterProcessing",
                environment.catalyst().returnedAfterProcessing());
        catalyst.addProperty("stateReadbackRequired",
                environment.catalyst().stateReadbackRequired());
        value.add("catalyst", catalyst);
        JsonObject speed = new JsonObject();
        speed.addProperty("minimumAbsoluteRpm",
                environment.minimumSpeed().minimumAbsoluteRpm().isPresent()
                        ? environment.minimumSpeed().minimumAbsoluteRpm().getAsLong()
                        : null);
        speed.addProperty("exactRuntimeThresholdRequired",
                environment.minimumSpeed().exactRuntimeThresholdRequired());
        value.add("minimumSpeed", speed);
        JsonObject observation = new JsonObject();
        JsonArray signals = new JsonArray();
        environment.runtimeObservation().signals().forEach(signal -> signals.add(signal.name()));
        observation.add("signals", signals);
        observation.addProperty("maximumObservationTicks",
                environment.runtimeObservation().maximumObservationTicks());
        observation.addProperty("fixedSleepForbidden",
                environment.runtimeObservation().fixedSleepForbidden());
        value.add("runtimeObservation", observation);
        return value;
    }

    private static JsonArray limitationsJson(List<CapabilityRecipeLimitation> limitations) {
        JsonArray values = new JsonArray();
        for (CapabilityRecipeLimitation limitation : limitations) {
            JsonObject value = new JsonObject();
            value.addProperty("code", limitation.code().name());
            value.addProperty("field", limitation.field());
            value.addProperty("detail", limitation.detail());
            value.addProperty("blocksPhaseI", limitation.blocksPhaseI());
            values.add(value);
        }
        return values;
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
            long crushingCount,
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
