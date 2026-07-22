package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.RuntimeIngredientSelection;
import dev.stevecreate.agent.adapter.api.RuntimeImplementationBindingService;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeVerifiedPlanningResult;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingFailure;
import dev.stevecreate.agent.core.binding.BindingFailureCode;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.layout.ImmutableMachineGeometryCatalog;
import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.LayoutFailure;
import dev.stevecreate.agent.core.layout.LayoutFailureCode;
import dev.stevecreate.agent.core.layout.PhysicalizationFailure;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/** R-09E read-only planning evidence over the live DeceasedCraft runtime snapshots. */
public final class DeceasedCraftRuntimePlanningExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DeceasedCraftRuntimePlanningExporter() {
    }

    public static ExportSummary export(ServerLevel level, Path actualGameDir) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(actualGameDir, "actualGameDir");
        check(level.getServer().isSameThread(), "Runtime planning export requires the server thread");

        RuntimeRecipeCatalogSnapshot recipes = requireRecipes(
                ForgeCreateRuntimeRecipeCatalogs.forLevel(level).snapshot());
        RuntimeMachineCapabilityCatalogSnapshot capabilities = requireCapabilities(
                new CreateRuntimeMachineCapabilityCatalog().snapshot(recipes));
        RuntimeMachineImplementationCatalogSnapshot implementations = requireImplementations(
                new CreateRuntimeMachineImplementationCatalog().snapshot(
                        recipes, capabilities, false));
        RuntimeKnowledgePlanningService planning = new RuntimeKnowledgePlanningService();

        RuntimeVerifiedPlanningResult gravel = requireSuccess(planning.plan(
                recipes, capabilities, goal("minecraft:gravel", 3)));
        RuntimeVerifiedPlanningResult ironSheet = requireSuccess(planning.plan(
                recipes, capabilities, goal("create:iron_sheet", 2)));
        RuntimeVerifiedPlanningResult cokeDust = requireSuccess(planning.plan(
                recipes, capabilities, goal("immersiveengineering:dust_coke", 4)));
        RuntimeVerifiedPlanningResult can = requireSuccess(planning.plan(
                recipes, capabilities, goal("apocalypsenow:can", 3)));
        BindingConstraints bindingConstraints = BindingConstraints.forRuntime(
                ResourceId.parse(recipes.runtime().adapterId()),
                recipes.runtime().industrialModVersions(),
                recipes.runtimeFingerprint());
        RuntimeImplementationBindingService binding = new RuntimeImplementationBindingService();
        VerifiedImplementationBoundPlan gravelBinding = requireBindingSuccess(binding.bind(
                gravel, implementations, bindingConstraints));
        VerifiedImplementationBoundPlan ironSheetBinding = requireBindingSuccess(binding.bind(
                ironSheet, implementations, bindingConstraints));
        VerifiedImplementationBoundPlan cokeDustBinding = requireBindingSuccess(binding.bind(
                cokeDust, implementations, bindingConstraints));
        VerifiedImplementationBoundPlan canBinding = requireBindingSuccess(binding.bind(
                can, implementations, bindingConstraints));

        check(containsRecipe(cokeDust, "create:kjs/"),
                "Custom coke-dust plan did not retain a KubeJS-generated recipe ID");
        check(containsRecipe(can, "create:kjs/"),
                "Custom can plan did not retain a KubeJS-generated recipe ID");
        check(hasTagSelection(ironSheet, "tag:forge:ingots/iron="),
                "Standard pressing plan lost its iron tag identity");
        check(hasTagSelection(can, "tag:forge:plates/aluminum="),
                "Custom pressing plan lost its aluminum-plate tag identity");
        check(allImplementations(cokeDustBinding,
                        CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID),
                "Custom KubeJS milling plan did not bind only to the real millstone");
        check(allImplementations(canBinding,
                        CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID),
                "Custom KubeJS pressing plan did not bind only to the real press");
        check(canBinding.graph().boundProcessNodes().values().stream()
                        .flatMap(node -> node.recipeInputs().stream())
                        .anyMatch(input -> input.ingredientIdentity().startsWith(
                                "tag:forge:plates/aluminum=")),
                "Custom pressing binding lost its original tag Ingredient identity");

        RuntimeKnowledgeFailure missing = requireFailure(planning.plan(
                recipes, capabilities, goal("steve_industrial:r09_missing_target", 1)),
                RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND);
        RejectedTarget rejectedTarget = rejectedTarget(level, recipes);
        RuntimeKnowledgeFailure rejected = requireFailure(planning.plan(
                recipes, capabilities, goal(rejectedTarget.target().toString(), 1)),
                RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND);
        RuntimeKnowledgeFailure adapterDisabled = requireFailure(planning.plan(
                recipes, null, goal("minecraft:gravel", 1)),
                RuntimeKnowledgeFailureCode.CAPABILITY_CATALOG_MISSING);

        RuntimeVerifiedPlanningResult repeatedCokeDust = requireSuccess(planning.plan(
                recipes, capabilities, goal("immersiveengineering:dust_coke", 4)));
        VerifiedImplementationBoundPlan repeatedCokeDustBinding = requireBindingSuccess(
                binding.bind(repeatedCokeDust, implementations, bindingConstraints));
        check(cokeDust.rankedCandidates().equals(repeatedCokeDust.rankedCandidates())
                        && cokeDust.verifiedPlan().id().equals(
                                repeatedCokeDust.verifiedPlan().id()),
                "Repeated pack-backed planning changed candidate order or verified identity");
        check(cokeDustBinding.graph().bindingTrace().equals(
                        repeatedCokeDustBinding.graph().bindingTrace()),
                "Repeated pack-backed binding changed selection or trace ordering");
        BindingConstraints staleConstraints = new BindingConstraints(
                bindingConstraints.allowedAdapterIds(), bindingConstraints.preferredAdapterId(),
                Set.of(), bindingConstraints.availableModVersions(),
                bindingConstraints.requiredEvidence(), true, true, true,
                recipes.runtimeFingerprint() + ":stale");
        BindingFailure staleBinding = requireBindingFailure(
                binding.bind(cokeDust, implementations, staleConstraints),
                BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH);
        BindingConstraints forbiddenConstraints = new BindingConstraints(
                bindingConstraints.allowedAdapterIds(), bindingConstraints.preferredAdapterId(),
                Set.of(CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID),
                bindingConstraints.availableModVersions(), bindingConstraints.requiredEvidence(),
                true, true, true, recipes.runtimeFingerprint());
        BindingFailure forbiddenBinding = requireBindingFailure(
                binding.bind(cokeDust, implementations, forbiddenConstraints),
                BindingFailureCode.IMPLEMENTATION_FORBIDDEN);

        check(implementations.catalog().implementationsForRecipeType(
                        ResourceId.parse("create:sequenced_assembly")).isEmpty(),
                "Sequenced assembly must not acquire millstone or press physicalization authority");
        ImmutableMachineGeometryCatalog geometryCatalog =
                CreateV606MachineGeometryCatalog.create(recipes.runtimeFingerprint());
        var spawn = level.getSharedSpawnPos();
        BlockPos3i layoutAnchor = new BlockPos3i(
                spawn.getX(), Math.min(level.getMaxBuildHeight() - 24, 240), spawn.getZ());
        PlacementSnapshot placementSnapshot = ForgeReadOnlyPlacementSnapshots.captureWithBoundedChunkReads(
                level, recipes.runtimeFingerprint(), layoutAnchor, 24, 2, 10,
                recipes.reloadGeneration());
        PhysicalizationService physicalization = new PhysicalizationService();
        List<PhysicalScenario> physicalScenarios = new java.util.ArrayList<>();
        addPhysicalOrientations(physicalization, geometryCatalog, placementSnapshot, layoutAnchor,
                "standard-milling-quantity", gravelBinding, physicalScenarios);
        addPhysicalOrientations(physicalization, geometryCatalog, placementSnapshot, layoutAnchor,
                "standard-pressing-tag", ironSheetBinding, physicalScenarios);
        addPhysicalOrientations(physicalization, geometryCatalog, placementSnapshot, layoutAnchor,
                "custom-kubejs-milling-repeatable", cokeDustBinding, physicalScenarios);
        addPhysicalOrientations(physicalization, geometryCatalog, placementSnapshot, layoutAnchor,
                "custom-kubejs-pressing-tag", canBinding, physicalScenarios);
        check(physicalScenarios.size() == 12
                        && physicalScenarios.stream().allMatch(value -> value.plan().evidence().size() == 13),
                "All four pack-backed bindings must physicalize in all three accepted orientations");
        VerifiedPhysicalPlan repeatedPhysical = requirePhysicalSuccess(physicalization.physicalize(
                cokeDustBinding, geometryCatalog,
                layoutConstraints(layoutAnchor, placementSnapshot, QuarterTurn.ZERO, 64, 64, 128)));
        VerifiedPhysicalPlan firstCokePhysical = physicalScenarios.stream()
                .filter(value -> value.scenario().equals("custom-kubejs-milling-repeatable")
                        && value.orientation() == QuarterTurn.ZERO)
                .findFirst().orElseThrow().plan();
        check(repeatedPhysical.candidate().trace().equals(firstCokePhysical.candidate().trace())
                        && repeatedPhysical.placements().equals(firstCokePhysical.placements())
                        && repeatedPhysical.routes().equals(firstCokePhysical.routes()),
                "Repeated pack-backed physicalization changed placement, route or trace ordering");

        List<PhysicalFailureScenario> physicalFailures = new java.util.ArrayList<>();
        physicalFailures.add(new PhysicalFailureScenario("stale-world-snapshot", requirePhysicalFailure(
                physicalization.physicalize(cokeDustBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor,
                                new PlacementSnapshot(recipes.runtimeFingerprint() + ":stale", 0,
                                        placementSnapshot.cells()),
                                QuarterTurn.ZERO, 64, 64, 128)),
                LayoutFailureCode.WORLD_SNAPSHOT_STALE)));
        physicalFailures.add(new PhysicalFailureScenario("placement-collision", requirePhysicalFailure(
                physicalization.physicalize(gravelBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, override(placementSnapshot, Map.of(
                                layoutAnchor.translate(0, 1, -1), LayoutCellState.OCCUPIED)),
                                QuarterTurn.ZERO, 64, 64, 128)),
                LayoutFailureCode.PLACEMENT_COLLISION)));
        physicalFailures.add(new PhysicalFailureScenario("clearance-blocked", requirePhysicalFailure(
                physicalization.physicalize(gravelBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, override(placementSnapshot, Map.of(
                                layoutAnchor.translate(-2, 0, -3), LayoutCellState.PROTECTED)),
                                QuarterTurn.ZERO, 64, 64, 128)),
                LayoutFailureCode.CLEARANCE_BLOCKED)));
        physicalFailures.add(new PhysicalFailureScenario("item-capacity", requirePhysicalFailure(
                physicalization.physicalize(gravelBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, placementSnapshot,
                                QuarterTurn.ZERO, 1, 64, 128)),
                LayoutFailureCode.ROUTE_CAPACITY_INSUFFICIENT)));
        physicalFailures.add(new PhysicalFailureScenario("rotational-power-capacity", requirePhysicalFailure(
                physicalization.physicalize(cokeDustBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, placementSnapshot,
                                QuarterTurn.ZERO, 64, 1, 128)),
                LayoutFailureCode.ROTATIONAL_POWER_ROUTE_NOT_FOUND)));
        physicalFailures.add(new PhysicalFailureScenario("stress-capacity", requirePhysicalFailure(
                physicalization.physicalize(canBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, placementSnapshot,
                                QuarterTurn.ZERO, 64, 64, 1)),
                LayoutFailureCode.STRESS_CAPACITY_INSUFFICIENT)));
        Map<BlockPos3i, LayoutCellState> barrier = new LinkedHashMap<>();
        int barrierX = layoutAnchor.x() + 8;
        placementSnapshot.cells().keySet().stream()
                .filter(position -> position.x() == barrierX)
                .forEach(position -> barrier.put(position, LayoutCellState.OCCUPIED));
        physicalFailures.add(new PhysicalFailureScenario("item-route-not-found", requirePhysicalFailure(
                physicalization.physicalize(canBinding, geometryCatalog,
                        layoutConstraints(layoutAnchor, override(placementSnapshot, barrier),
                                QuarterTurn.ZERO, 64, 64, 128)),
                LayoutFailureCode.ITEM_ROUTE_NOT_FOUND)));

        JsonObject root = new JsonObject();
        root.addProperty("schema", "steve-industrial:r09-runtime-planning-binding-physicalization/v3");
        root.addProperty("evidenceSource", "isolated-pack-runtime-snapshots");
        root.addProperty("runtimeFingerprint", recipes.runtimeFingerprint());
        root.addProperty("readOnly", true);
        root.addProperty("worldMutation", false);
        root.addProperty("executionSessionCreated", false);
        root.addProperty("implementationBinding", true);
        root.addProperty("physicalization", true);
        root.addProperty("physicalAuthority", true);
        root.addProperty("executionAuthority", false);
        JsonArray scenarios = new JsonArray();
        scenarios.add(successJson("standard-milling-quantity", gravel));
        scenarios.add(successJson("standard-pressing-tag", ironSheet));
        scenarios.add(successJson("custom-kubejs-milling-repeatable", cokeDust));
        scenarios.add(successJson("custom-kubejs-pressing-tag", can));
        scenarios.add(failureJson("missing-target", missing, Optional.empty()));
        scenarios.add(failureJson(
                "rejected-complex-or-probabilistic-recipe",
                rejected,
                Optional.of(rejectedTarget.limitation())));
        scenarios.add(failureJson("create-adapter-disabled", adapterDisabled, Optional.empty()));
        root.add("scenarios", scenarios);
        JsonArray bindings = new JsonArray();
        bindings.add(bindingJson("standard-milling-quantity", gravelBinding));
        bindings.add(bindingJson("standard-pressing-tag", ironSheetBinding));
        bindings.add(bindingJson("custom-kubejs-milling-repeatable", cokeDustBinding));
        bindings.add(bindingJson("custom-kubejs-pressing-tag", canBinding));
        root.add("bindings", bindings);
        JsonArray bindingFailures = new JsonArray();
        bindingFailures.add(bindingFailureJson("stale-runtime-fingerprint", staleBinding));
        bindingFailures.add(bindingFailureJson("millstone-forbidden", forbiddenBinding));
        root.add("bindingFailures", bindingFailures);
        JsonArray physicalizations = new JsonArray();
        physicalScenarios.forEach(value -> physicalizations.add(physicalizationJson(value)));
        root.add("physicalizations", physicalizations);
        JsonArray physicalizationFailures = new JsonArray();
        physicalFailures.forEach(value -> physicalizationFailures.add(physicalizationFailureJson(value)));
        root.add("physicalizationFailures", physicalizationFailures);
        root.addProperty("successCount", 4);
        root.addProperty("failureCount", 3);
        root.addProperty("repeatabilityChecks", 1);
        root.addProperty("allSuccessesVerifiedChecks", 8);
        root.addProperty("outputType", "VerifiedLogicalPlan");
        root.addProperty("bindingSuccessCount", 4);
        root.addProperty("bindingFailureCount", 2);
        root.addProperty("bindingVerifierChecks", 15);
        root.addProperty("bindingOutputType", "VerifiedImplementationBoundPlan");
        root.addProperty("physicalizationSuccessCount", physicalScenarios.size());
        root.addProperty("physicalizationFailureCount", physicalFailures.size());
        root.addProperty("physicalizationVerifierChecks", 13);
        root.addProperty("physicalizationOutputType", "VerifiedPhysicalPlan");
        root.addProperty("sequencedAssemblyPhysicalizationRejected", true);
        root.addProperty("snapshotSource", "bounded-isolated-world-block-states");
        root.addProperty("snapshotChunkReadBound", 49);

        Path outputDirectory = actualGameDir.resolve("r09-evidence");
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("runtime-planning.json");
        Path temporary = outputDirectory.resolve("runtime-planning.json.tmp");
        Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return new ExportSummary(
                output,
                recipes.runtimeFingerprint(),
                customRecipeId(cokeDust),
                customRecipeId(can),
                rejectedTarget.limitation().recipeId().orElseThrow(),
                rejectedTarget.target());
    }

    private static JsonObject successJson(String scenario, RuntimeVerifiedPlanningResult result) {
        check(result.verifiedPlan().evidence().size() == 8,
                "Every successful runtime plan must pass all eight verifier checks");
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario);
        value.addProperty("status", "SUCCESS");
        value.addProperty("outputType", "VerifiedLogicalPlan");
        value.addProperty("target", result.goal().target().toString());
        value.addProperty("quantity", result.goal().quantity());
        value.addProperty("verifiedPlanId", result.verifiedPlan().id().toString());
        value.addProperty("candidateId", result.verifiedPlan().candidate().candidateId().toString());
        value.addProperty("runtimeFingerprint", result.runtimeFingerprint());
        JsonArray recipes = new JsonArray();
        result.verifiedPlan().candidate().selectedRecipes().forEach(
                recipe -> recipes.add(recipe.recipeId().toString()));
        value.add("recipeIds", recipes);
        value.add("rawMaterials", resourcesJson(
                result.verifiedPlan().candidate().rawMaterials()));
        JsonArray selections = new JsonArray();
        result.resolvedRecipes().resolutions().stream()
                .flatMap(resolution -> resolution.selections().stream())
                .filter(selection -> result.verifiedPlan().candidate().selectedRecipes().stream()
                        .anyMatch(recipe -> recipe.recipeId().equals(selection.recipeId())))
                .forEach(selection -> selections.add(selectionJson(selection)));
        value.add("ingredientSelections", selections);
        JsonArray checks = new JsonArray();
        result.verifiedPlan().evidence().keySet().forEach(check -> checks.add(check.name()));
        value.add("verificationChecks", checks);
        value.addProperty("verificationCheckCount", result.verifiedPlan().evidence().size());
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        value.addProperty("physicalAuthority", false);
        return value;
    }

    private static JsonObject failureJson(
            String scenario,
            RuntimeKnowledgeFailure failure,
            Optional<RuntimeKnowledgeFailure> catalogLimitation) {
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario);
        value.addProperty("status", "FAILURE");
        value.addProperty("code", failure.code().name());
        value.addProperty("stage", failure.stage().name());
        failure.targetResource().ifPresent(target -> value.addProperty("target", target.toString()));
        value.addProperty("detail", failure.detail());
        JsonArray trace = new JsonArray();
        failure.trace().forEach(trace::add);
        value.add("trace", trace);
        catalogLimitation.ifPresent(limitation -> {
            value.addProperty("rejectedRecipeId", limitation.recipeId().orElseThrow().toString());
            value.addProperty("catalogRejectionCode", limitation.code().name());
            value.addProperty("catalogRejectionDetail", limitation.detail());
        });
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        return value;
    }

    private static JsonObject bindingJson(
            String scenario,
            VerifiedImplementationBoundPlan plan) {
        check(plan.evidence().size() == 15,
                "Every successful implementation binding must pass all fifteen checks");
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario);
        value.addProperty("status", "SUCCESS");
        value.addProperty("outputType", "VerifiedImplementationBoundPlan");
        value.addProperty("verifiedBindingId", plan.id().toString());
        value.addProperty("logicalPlanId", plan.graph().logicalPlan().id().toString());
        value.addProperty("runtimeFingerprint", plan.graph().runtimeFingerprint());
        JsonArray nodes = new JsonArray();
        plan.graph().boundProcessNodes().values().forEach(node -> {
            JsonObject item = new JsonObject();
            item.addProperty("logicalNodeId", node.logicalNodeId().toString());
            item.addProperty("recipeId", node.recipeId().toString());
            item.addProperty("implementationId", node.implementationId().toString());
            item.addProperty("adapterId", node.adapterId().toString());
            item.addProperty("executions", node.quantityConversion().executions());
            JsonArray ingredients = new JsonArray();
            node.recipeInputs().forEach(input -> {
                JsonObject ingredient = new JsonObject();
                ingredient.addProperty("kind", input.ingredientKind().name());
                ingredient.addProperty("identity", input.ingredientIdentity());
                ingredient.addProperty("selectedResource", input.selectedResource().toString());
                ingredient.addProperty("amount", input.amount());
                ingredients.add(ingredient);
            });
            item.add("ingredientSelections", ingredients);
            nodes.add(item);
        });
        value.add("nodes", nodes);
        JsonArray checks = new JsonArray();
        plan.evidence().keySet().forEach(check -> checks.add(check.name()));
        value.add("verificationChecks", checks);
        value.addProperty("verificationCheckCount", plan.evidence().size());
        value.addProperty("layoutAuthority", false);
        value.addProperty("executionAuthority", false);
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        return value;
    }

    private static JsonObject bindingFailureJson(String scenario, BindingFailure failure) {
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario);
        value.addProperty("status", "FAILURE");
        value.addProperty("code", failure.code().name());
        value.addProperty("stage", failure.stage().name());
        value.addProperty("runtimeFingerprint", failure.runtimeFingerprint());
        value.addProperty("constraint", failure.constraint());
        value.addProperty("detail", failure.detail());
        value.addProperty("safeNextStep", failure.safeNextStep());
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        return value;
    }

    private static void addPhysicalOrientations(
            PhysicalizationService service,
            ImmutableMachineGeometryCatalog geometries,
            PlacementSnapshot snapshot,
            BlockPos3i anchor,
            String scenario,
            VerifiedImplementationBoundPlan boundPlan,
            List<PhysicalScenario> output) {
        for (QuarterTurn orientation : List.of(
                QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270)) {
            output.add(new PhysicalScenario(
                    scenario, orientation,
                    requirePhysicalSuccess(service.physicalize(
                            boundPlan, geometries,
                            layoutConstraints(anchor, snapshot, orientation, 64, 64, 128)))));
        }
    }

    private static LayoutConstraints layoutConstraints(
            BlockPos3i anchor,
            PlacementSnapshot snapshot,
            QuarterTurn orientation,
            long itemCapacity,
            long powerCapacity,
            long stressCapacity) {
        return new LayoutConstraints(
                anchor, List.of(orientation), 1, 64, 128, 200_000,
                itemCapacity, powerCapacity, stressCapacity, snapshot);
    }

    private static PlacementSnapshot override(
            PlacementSnapshot source,
            Map<BlockPos3i, LayoutCellState> overrides) {
        Map<BlockPos3i, LayoutCellState> cells = new LinkedHashMap<>(source.cells());
        cells.putAll(overrides);
        return new PlacementSnapshot(source.runtimeFingerprint(), source.snapshotGeneration(), cells);
    }

    private static VerifiedPhysicalPlan requirePhysicalSuccess(PhysicalizationResult result) {
        if (result instanceof PhysicalizationSuccess success) return success.plan();
        LayoutFailure failure = ((PhysicalizationFailure) result).failure();
        throw new IllegalStateException("Expected physicalization success but received "
                + failure.code() + ": " + failure.message());
    }

    private static LayoutFailure requirePhysicalFailure(
            PhysicalizationResult result,
            LayoutFailureCode expected) {
        if (!(result instanceof PhysicalizationFailure failed)) {
            throw new IllegalStateException("Expected physicalization failure " + expected);
        }
        check(failed.failure().code() == expected,
                "Expected physicalization failure " + expected + " but received "
                        + failed.failure().code());
        return failed.failure();
    }

    private static JsonObject physicalizationJson(PhysicalScenario scenario) {
        VerifiedPhysicalPlan plan = scenario.plan();
        check(plan.evidence().size() == 13,
                "Every successful physicalization must pass all thirteen checks");
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario.scenario());
        value.addProperty("orientation", scenario.orientation().name());
        value.addProperty("status", "SUCCESS");
        value.addProperty("outputType", "VerifiedPhysicalPlan");
        value.addProperty("verifiedPhysicalPlanId", plan.id().toString());
        value.addProperty("bindingId", plan.candidate().boundPlan().id().toString());
        value.addProperty("runtimeFingerprint", plan.candidate().snapshotFingerprint());
        value.addProperty("placements", plan.placements().size());
        value.addProperty("itemRoutes", plan.routes().size());
        value.addProperty("unifiedNodes", plan.unifiedGraph().nodes().size());
        value.addProperty("unifiedEdges", plan.unifiedGraph().edges().size());
        value.addProperty("verificationCheckCount", plan.evidence().size());
        value.addProperty("readOnlySnapshot", true);
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        value.addProperty("executionAuthority", false);
        return value;
    }

    private static JsonObject physicalizationFailureJson(PhysicalFailureScenario scenario) {
        JsonObject value = new JsonObject();
        value.addProperty("scenario", scenario.scenario());
        value.addProperty("status", "FAILURE");
        value.addProperty("code", scenario.failure().code().name());
        value.addProperty("stage", scenario.failure().stage().name());
        value.addProperty("detail", scenario.failure().message());
        value.addProperty("worldMutation", false);
        value.addProperty("executionSessionCreated", false);
        return value;
    }

    private static JsonObject selectionJson(RuntimeIngredientSelection selection) {
        JsonObject value = new JsonObject();
        value.addProperty("recipeId", selection.recipeId().toString());
        value.addProperty("inputIndex", selection.inputIndex());
        value.addProperty("ingredientKind", selection.ingredientKind().name());
        value.addProperty("ingredientIdentity", selection.ingredientIdentity());
        value.addProperty("selectedResource", selection.selectedResource().toString());
        value.addProperty("amount", selection.amount());
        value.addProperty("selectionReason", selection.reason().name());
        return value;
    }

    private static JsonArray resourcesJson(List<ProcessResource> resources) {
        JsonArray values = new JsonArray();
        resources.forEach(resource -> {
            JsonObject value = new JsonObject();
            value.addProperty("resourceId", resource.resourceId().toString());
            value.addProperty("resourceType", resource.resourceType().serializedName());
            value.addProperty("amount", resource.amount());
            values.add(value);
        });
        return values;
    }

    private static RejectedTarget rejectedTarget(
            ServerLevel level, RuntimeRecipeCatalogSnapshot snapshot) {
        for (RuntimeKnowledgeFailure limitation : snapshot.limitations()) {
            if (limitation.code() != RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED
                    || limitation.recipeId().isEmpty()) {
                continue;
            }
            ResourceId recipeId = limitation.recipeId().orElseThrow();
            Recipe<?> recipe = level.getRecipeManager().byKey(
                    Objects.requireNonNull(ResourceLocation.tryParse(recipeId.toString())))
                    .orElse(null);
            if (!(recipe instanceof ProcessingRecipe<?> processing)) {
                continue;
            }
            for (var output : processing.getRollableResults()) {
                ItemStack stack = output.getStack();
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (stack.isEmpty() || itemId == null) {
                    continue;
                }
                ResourceId target = ResourceId.parse(itemId.toString());
                if (snapshot.catalog().recipesProducing(target, GenericResourceType.ITEM).isEmpty()) {
                    return new RejectedTarget(target, limitation);
                }
            }
        }
        throw new IllegalStateException(
                "No rejected complex/probabilistic recipe exposes a uniquely unavailable target");
    }

    private static RuntimeRecipeCatalogSnapshot requireRecipes(RuntimeRecipeCatalogResult result) {
        if (result instanceof RuntimeRecipeCatalogResult.Success success) {
            return success.snapshot();
        }
        RuntimeKnowledgeFailure failure = ((RuntimeRecipeCatalogResult.Failure) result).failure();
        throw new IllegalStateException("Recipe catalog unavailable: " + failure.code()
                + " " + failure.detail());
    }

    private static RuntimeMachineCapabilityCatalogSnapshot requireCapabilities(
            RuntimeMachineCapabilityCatalogResult result) {
        if (result instanceof RuntimeMachineCapabilityCatalogResult.Success success) {
            return success.snapshot();
        }
        RuntimeKnowledgeFailure failure =
                ((RuntimeMachineCapabilityCatalogResult.Failure) result).failure();
        throw new IllegalStateException("Capability catalog unavailable: " + failure.code()
                + " " + failure.detail());
    }

    private static RuntimeMachineImplementationCatalogSnapshot requireImplementations(
            RuntimeMachineImplementationCatalogResult result) {
        if (result instanceof RuntimeMachineImplementationCatalogResult.Success success) {
            return success.snapshot();
        }
        BindingFailure failure =
                ((RuntimeMachineImplementationCatalogResult.Failure) result).failure();
        throw new IllegalStateException("Implementation catalog unavailable: " + failure.code()
                + " " + failure.detail());
    }

    private static RuntimeVerifiedPlanningResult requireSuccess(RuntimePlanningResult result) {
        if (result instanceof RuntimePlanningResult.Success success) {
            return success.result();
        }
        RuntimeKnowledgeFailure failure = ((RuntimePlanningResult.Failure) result).failure();
        throw new IllegalStateException("Expected planning success but received " + failure.code()
                + ": " + failure.detail());
    }

    private static RuntimeKnowledgeFailure requireFailure(
            RuntimePlanningResult result, RuntimeKnowledgeFailureCode expected) {
        if (!(result instanceof RuntimePlanningResult.Failure failed)) {
            throw new IllegalStateException("Expected planning failure " + expected);
        }
        check(failed.failure().code() == expected,
                "Expected planning failure " + expected + " but received "
                        + failed.failure().code());
        return failed.failure();
    }

    private static VerifiedImplementationBoundPlan requireBindingSuccess(BindingResult result) {
        if (result instanceof BindingResult.Success success) {
            return success.plan();
        }
        BindingFailure failure = ((BindingResult.Failure) result).failure();
        throw new IllegalStateException("Expected binding success but received " + failure.code()
                + ": " + failure.detail());
    }

    private static BindingFailure requireBindingFailure(
            BindingResult result,
            BindingFailureCode expected) {
        if (!(result instanceof BindingResult.Failure failed)) {
            throw new IllegalStateException("Expected binding failure " + expected);
        }
        check(failed.failure().code() == expected,
                "Expected binding failure " + expected + " but received "
                        + failed.failure().code());
        return failed.failure();
    }

    private static ProductionGoal goal(String target, long quantity) {
        return new ProductionGoal(
                ResourceId.parse(target),
                GenericResourceType.ITEM,
                quantity,
                Set.of(),
                Set.of(),
                Optional.of(8),
                MaterialConstraints.none(),
                List.of(
                        PlanningStrategyPreference.MINIMIZE_STEPS,
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES),
                Map.of());
    }

    private static boolean containsRecipe(
            RuntimeVerifiedPlanningResult result, String prefix) {
        return result.verifiedPlan().candidate().selectedRecipes().stream()
                .anyMatch(recipe -> recipe.recipeId().toString().startsWith(prefix));
    }

    private static boolean hasTagSelection(
            RuntimeVerifiedPlanningResult result, String prefix) {
        return result.resolvedRecipes().resolutions().stream()
                .flatMap(resolution -> resolution.selections().stream())
                .anyMatch(selection -> selection.ingredientIdentity().startsWith(prefix)
                        && result.verifiedPlan().candidate().selectedRecipes().stream()
                                .anyMatch(recipe -> recipe.recipeId().equals(selection.recipeId())));
    }

    private static ResourceId customRecipeId(RuntimeVerifiedPlanningResult result) {
        return result.verifiedPlan().candidate().selectedRecipes().stream()
                .map(recipe -> recipe.recipeId())
                .filter(recipeId -> recipeId.toString().startsWith("create:kjs/"))
                .findFirst()
                .orElseThrow();
    }

    private record PhysicalScenario(
            String scenario,
            QuarterTurn orientation,
            VerifiedPhysicalPlan plan) {}

    private record PhysicalFailureScenario(String scenario, LayoutFailure failure) {}

    private static boolean allImplementations(
            VerifiedImplementationBoundPlan plan,
            ResourceId implementationId) {
        return !plan.graph().boundProcessNodes().isEmpty()
                && plan.graph().boundProcessNodes().values().stream()
                        .allMatch(node -> node.implementationId().equals(implementationId));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private record RejectedTarget(ResourceId target, RuntimeKnowledgeFailure limitation) {
        private RejectedTarget {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(limitation, "limitation");
        }
    }

    public record ExportSummary(
            Path evidencePath,
            String runtimeFingerprint,
            ResourceId customMillingRecipeId,
            ResourceId customPressingRecipeId,
            ResourceId rejectedRecipeId,
            ResourceId rejectedTarget) {
        public ExportSummary {
            Objects.requireNonNull(evidencePath, "evidencePath");
            Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
            Objects.requireNonNull(customMillingRecipeId, "customMillingRecipeId");
            Objects.requireNonNull(customPressingRecipeId, "customPressingRecipeId");
            Objects.requireNonNull(rejectedRecipeId, "rejectedRecipeId");
            Objects.requireNonNull(rejectedTarget, "rejectedTarget");
        }
    }
}
