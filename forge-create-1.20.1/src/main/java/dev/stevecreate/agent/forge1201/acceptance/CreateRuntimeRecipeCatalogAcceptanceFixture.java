package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityDeclaration;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeIngredientSelection;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeImplementationBindingService;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeVerifiedPlanningResult;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.binding.ImplementationExecutionSupport;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.MachineImplementationDescriptor;
import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.layout.ImmutableMachineGeometryCatalog;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.LayoutFailureCode;
import dev.stevecreate.agent.core.layout.PhysicalizationFailure;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineCapabilityCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineImplementationCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeRecipeCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606MachineGeometryCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.DeceasedCraftRuntimeKnowledgeExporter;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.ForgeReadOnlyPlacementSnapshots;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/** Development-only read-only runtime proof for the R-01/R-02 catalog boundary and mapping. */
public final class CreateRuntimeRecipeCatalogAcceptanceFixture {
    private CreateRuntimeRecipeCatalogAcceptanceFixture() {
    }

    public static void run(MinecraftServer server, Logger logger) {
        try {
            check(!FMLLoader.isProduction(), "Runtime catalog acceptance fixture is disabled in production");
            check(server.isSameThread(), "Runtime catalog fixture must run on the authoritative server thread");
            ServerLevel level = server.overworld();
            int discovered = Math.toIntExact(level.getRecipeManager().getRecipeIds().count());
            check(discovered > 0, "RecipeManager exposed no runtime recipes");
            Map<String, Long> runtimeTypeCounts = level.getRecipeManager().getRecipes().stream()
                    .collect(Collectors.groupingBy(
                            recipe -> BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString(),
                            TreeMap::new,
                            Collectors.counting()));
            int millingDiscovered = Math.toIntExact(runtimeTypeCounts.getOrDefault("create:milling", 0L));
            int pressingDiscovered = Math.toIntExact(runtimeTypeCounts.getOrDefault("create:pressing", 0L));
            check(millingDiscovered > 1 && pressingDiscovered > 1,
                    "RecipeManager did not expose the expected runtime recipe-type populations");

            BlockPos probe = level.getSharedSpawnPos();
            BlockState before = level.getBlockState(probe);
            CreateRuntimeRecipeCatalog catalog = ForgeCreateRuntimeRecipeCatalogs.forLevel(level);

            RuntimeRecipeCatalogSnapshot initial = requireSuccess(catalog.snapshot());
            CreateRuntimeMachineCapabilityCatalog capabilityCatalog =
                    new CreateRuntimeMachineCapabilityCatalog();
            RuntimeMachineCapabilityCatalogSnapshot capabilities =
                    requireSuccess(capabilityCatalog.snapshot(initial));
            check(capabilities.declarations().size() == 2,
                    "Create runtime capability catalog must contain exactly milling and pressing");
            RuntimeMachineCapabilityDeclaration millingCapability =
                    requireCapability(capabilities, "create:milling");
            RuntimeMachineCapabilityDeclaration pressingCapability =
                    requireCapability(capabilities, "create:pressing");
            checkCapability(
                    millingCapability,
                    "create:milling",
                    "create:milling/cobblestone",
                    initial.runtimeFingerprint());
            checkCapability(
                    pressingCapability,
                    "create:pressing",
                    "create:pressing/iron_ingot",
                    initial.runtimeFingerprint());
            CreateRuntimeMachineImplementationCatalog implementationCatalog =
                    new CreateRuntimeMachineImplementationCatalog();
            RuntimeMachineImplementationCatalogSnapshot implementations = requireSuccess(
                    implementationCatalog.snapshot(initial, capabilities, false));
            check(implementations.catalog().implementations().size() == 2,
                    "Create runtime implementation catalog must contain exactly millstone and press");
            checkImplementation(
                    implementations,
                    "create:mechanical_millstone",
                    "create:milling",
                    "create:milling/cobblestone",
                    initial.runtimeFingerprint());
            checkImplementation(
                    implementations,
                    "create:mechanical_press",
                    "create:pressing",
                    "create:pressing/iron_ingot",
                    initial.runtimeFingerprint());
            RuntimeKnowledgePlanningService planning = new RuntimeKnowledgePlanningService();
            RuntimeVerifiedPlanningResult gravelPlan = requireSuccess(planning.plan(
                    initial,
                    capabilities,
                    goal("minecraft:gravel", 3, Map.of())));
            RuntimeVerifiedPlanningResult ironSheetPlan = requireSuccess(planning.plan(
                    initial,
                    capabilities,
                    goal("create:iron_sheet", 2, Map.of())));
            BindingConstraints bindingConstraints = BindingConstraints.forRuntime(
                    ResourceId.parse(initial.runtime().adapterId()),
                    initial.runtime().industrialModVersions(),
                    initial.runtimeFingerprint());
            RuntimeImplementationBindingService binding =
                    new RuntimeImplementationBindingService();
            VerifiedImplementationBoundPlan gravelBinding = requireSuccess(binding.bind(
                    gravelPlan, implementations, bindingConstraints));
            VerifiedImplementationBoundPlan ironSheetBinding = requireSuccess(binding.bind(
                    ironSheetPlan, implementations, bindingConstraints));
            check(gravelBinding.graph().boundProcessNodes().size() == 2
                            && gravelBinding.graph().boundProcessNodes().values().stream().allMatch(
                            node -> node.implementationId().equals(
                                    CreateRuntimeMachineImplementationCatalog
                                            .MILLSTONE_IMPLEMENTATION_ID)),
                    "Runtime gravel logical chain did not bind both milling nodes to the millstone");
            check(ironSheetBinding.graph().boundProcessNodes().size() == 1
                            && ironSheetBinding.graph().boundProcessNodes().values().stream().allMatch(
                            node -> node.implementationId().equals(
                                    CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID)),
                    "Runtime iron-sheet plan did not bind its pressing node to the press");
            check(gravelBinding.graph().boundProcessNodes().values().stream()
                            .flatMap(node -> node.recipeInputs().stream())
                            .allMatch(input -> input.ingredientIdentity().startsWith("exact:")),
                    "Runtime exact Ingredient identities were not preserved through binding");
            ImmutableMachineGeometryCatalog geometries =
                    CreateV606MachineGeometryCatalog.create(initial.runtimeFingerprint());
            BlockPos3i physicalAnchor = new BlockPos3i(
                    probe.getX(), Math.min(level.getMaxBuildHeight() - 24, 240), probe.getZ());
            PlacementSnapshot physicalSnapshot = ForgeReadOnlyPlacementSnapshots.capture(
                    level, initial.runtimeFingerprint(), physicalAnchor, 40, 2, 10,
                    initial.reloadGeneration());
            PhysicalizationService physicalization = new PhysicalizationService();
            List<VerifiedPhysicalPlan> physicalPlans = new ArrayList<>();
            for (VerifiedImplementationBoundPlan bound : List.of(gravelBinding, ironSheetBinding)) {
                for (QuarterTurn orientation : List.of(
                        QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90, QuarterTurn.CLOCKWISE_270)) {
                    physicalPlans.add(requireSuccess(physicalization.physicalize(
                            bound, geometries,
                            physicalConstraints(physicalAnchor, physicalSnapshot, orientation,
                                    64, 64, 128))));
                }
            }
            check(physicalPlans.size() == 6
                            && physicalPlans.stream().allMatch(plan -> plan.evidence().size() == 13)
                            && physicalPlans.stream().allMatch(plan -> !plan.unifiedGraph().edges().isEmpty()),
                    "Standard runtime physicalization did not pass its complete three-orientation matrix");
            VerifiedPhysicalPlan repeatedPhysical = requireSuccess(physicalization.physicalize(
                    gravelBinding, geometries,
                    physicalConstraints(physicalAnchor, physicalSnapshot, QuarterTurn.ZERO,
                            64, 64, 128)));
            check(repeatedPhysical.placements().equals(physicalPlans.get(0).placements())
                            && repeatedPhysical.routes().equals(physicalPlans.get(0).routes())
                            && repeatedPhysical.candidate().trace().equals(
                                    physicalPlans.get(0).candidate().trace()),
                    "Standard runtime physicalization was not deterministic");
            requireFailure(physicalization.physicalize(
                    gravelBinding, geometries,
                    physicalConstraints(physicalAnchor, physicalSnapshot, QuarterTurn.ZERO,
                            1, 64, 128)), LayoutFailureCode.ROUTE_CAPACITY_INSUFFICIENT);
            requireFailure(physicalization.physicalize(
                    gravelBinding, geometries,
                    physicalConstraints(physicalAnchor, physicalSnapshot, QuarterTurn.ZERO,
                            64, 1, 128)), LayoutFailureCode.ROTATIONAL_POWER_ROUTE_NOT_FOUND);
            requireFailure(physicalization.physicalize(
                    gravelBinding, geometries,
                    physicalConstraints(physicalAnchor, physicalSnapshot, QuarterTurn.ZERO,
                            64, 64, 1)), LayoutFailureCode.STRESS_CAPACITY_INSUFFICIENT);
            check(implementations.catalog().implementationsForRecipeType(
                            ResourceId.parse("create:sequenced_assembly")).isEmpty(),
                    "Sequenced assembly was incorrectly granted physicalization authority");
            checkPlan(
                    gravelPlan,
                    "minecraft:gravel",
                    3,
                    "create:milling/cobblestone",
                    "minecraft:andesite",
                    3,
                    3,
                    initial.runtimeFingerprint());
            check(gravelPlan.verifiedPlan().candidate().selectedRecipes().size() == 2,
                    "Runtime gravel plan did not preserve its discovered two-step milling chain");
            checkPlan(
                    ironSheetPlan,
                    "create:iron_sheet",
                    2,
                    "create:pressing/iron_ingot",
                    "minecraft:iron_ingot",
                    2,
                    2,
                    initial.runtimeFingerprint());
            RuntimeIngredientSelection ironSelection = ironSheetPlan.resolvedRecipes()
                    .resolutions().stream()
                    .filter(value -> value.runtimeEntry().recipeId()
                            .equals(ResourceId.parse("create:pressing/iron_ingot")))
                    .findFirst()
                    .orElseThrow()
                    .selections()
                    .get(0);
            check(ironSelection.ingredientKind().name().equals("TAG_REFERENCE")
                            && ironSelection.ingredientIdentity().startsWith(
                                    "tag:forge:ingots/iron=")
                            && ironSelection.selectedResource()
                                    .equals(ResourceId.parse("minecraft:iron_ingot")),
                    "Runtime pressing plan did not retain and deterministically resolve its tag");
            RuntimeVerifiedPlanningResult repeatedGravel = requireSuccess(planning.plan(
                    initial,
                    capabilities,
                    goal("minecraft:gravel", 3, Map.of())));
            check(repeatedGravel.rankedCandidates().equals(gravelPlan.rankedCandidates())
                            && repeatedGravel.verifiedPlan().id()
                                    .equals(gravelPlan.verifiedPlan().id()),
                    "Repeated runtime planning changed candidate ordering or verified identity");
            RecordingCommandSource successSource = new RecordingCommandSource();
            CommandSourceStack successStack = server.createCommandSourceStack()
                    .withSource(successSource)
                    .withLevel(level)
                    .withPosition(Vec3.atCenterOf(probe))
                    .withPermission(4);
            int commandReturn = server.getCommands().performPrefixedCommand(
                    successStack,
                    "/industrialagent plan create minecraft:gravel 3");
            check(commandReturn == 1
                            && successSource.messages().stream().anyMatch(message ->
                                    message.contains("Plan verified target=minecraft:gravel")
                                            && message.contains("quantity=3")
                                            && message.contains("verification=PASS"))
                            && successSource.messages().stream().anyMatch(message ->
                                    message.contains("recipe=create:milling/cobblestone")
                                            && message.contains("raw=[minecraft:andesite@3]"))
                            && successSource.messages().stream().anyMatch(message ->
                                    message.contains("ingredient=")
                                            && message.contains("conversions=")
                                            && message.contains("trace=")),
                    "Read-only runtime command did not emit its complete verified plan summary");
            RecordingCommandSource bindingSource = new RecordingCommandSource();
            int bindingReturn = server.getCommands().performPrefixedCommand(
                    successStack.withSource(bindingSource),
                    "/industrialagent bind create minecraft:gravel 3");
            check(bindingReturn == 1
                            && bindingSource.messages().stream().anyMatch(message ->
                                    message.contains("Binding verified plan=")
                                            && message.contains("nodes=2")
                                            && message.contains("verification=PASS"))
                            && bindingSource.messages().stream().anyMatch(message ->
                                    message.contains("implementation=create:mechanical_millstone")
                                            && message.contains("recipe=create:milling/cobblestone"))
                            && bindingSource.messages().stream().anyMatch(message ->
                                    message.contains("trace=")),
                    "Read-only binding command did not emit its verified implementation trace");
            CommandSourceStack deploymentStack = successStack.withPosition(Vec3.atCenterOf(
                    new BlockPos(physicalAnchor.x(), physicalAnchor.y(), physicalAnchor.z())));
            int deploymentCommands = 0;
            for (String orientation : List.of("zero", "clockwise_90", "clockwise_270")) {
                RecordingCommandSource deploymentSource = new RecordingCommandSource();
                int deploymentReturn = server.getCommands().performPrefixedCommand(
                        deploymentStack.withSource(deploymentSource),
                        "/industrialagent deploy preview minecraft:gravel 3 " + orientation);
                check(deploymentReturn == 1
                                && deploymentSource.messages().stream().anyMatch(message ->
                                        message.contains("target=minecraft:gravel")
                                                && message.contains("quantity=3")
                                                && message.contains("previewHash=")
                                                && message.contains("environment=ISOLATED_TEST_WORLD")
                                                && message.contains("formalWorldExecutable=false"))
                                && deploymentSource.messages().stream().anyMatch(message ->
                                        message.contains("bounds=")
                                                && message.contains("materials="))
                                && deploymentSource.messages().stream().anyMatch(message ->
                                        message.contains("worldMutation=false")
                                                && message.contains("sessionCreated=false")
                                                && message.contains("playerItemsConsumed=false")
                                                && message.contains("machineStarted=false")
                                                && message.contains("llmCalled=false")
                                                && message.contains("freeTextCoordinatesAccepted=false")),
                        "PW-12 gravel preview command did not retain complete dry-run safety output: "
                                + deploymentSource.messages());
                deploymentCommands++;
            }
            for (String section : List.of("risks", "budget", "readiness")) {
                RecordingCommandSource deploymentSource = new RecordingCommandSource();
                int deploymentReturn = server.getCommands().performPrefixedCommand(
                        deploymentStack.withSource(deploymentSource),
                        "/industrialagent deploy " + section + " minecraft:gravel 3 zero");
                check(deploymentReturn == 1
                                && deploymentSource.messages().stream().anyMatch(message ->
                                        message.contains("previewHash=")
                                                && message.contains("readiness=BLOCKED")
                                                && message.contains("formalWorldExecutable=false")),
                        "PW-12 " + section + " view did not return a blocked dry-run report: "
                                + deploymentSource.messages());
                deploymentCommands++;
            }
            for (String section : List.of("preview", "risks", "budget", "readiness")) {
                RecordingCommandSource deploymentSource = new RecordingCommandSource();
                int deploymentReturn = server.getCommands().performPrefixedCommand(
                        deploymentStack.withSource(deploymentSource),
                        "/industrialagent deploy " + section + " create:iron_sheet 2 zero");
                check(deploymentReturn == 1
                                && deploymentSource.messages().stream().anyMatch(message ->
                                        message.contains("target=create:iron_sheet")
                                                && message.contains("quantity=2")
                                                && message.contains("formalWorldExecutable=false")),
                        "PW-12 iron-sheet " + section + " view failed: "
                                + deploymentSource.messages());
                deploymentCommands++;
            }
            check(deploymentCommands == 10,
                    "PW-12 command acceptance did not execute the complete ten-command matrix");
            RecordingCommandSource invalidDeploymentSource = new RecordingCommandSource();
            int invalidDeploymentQuantityReturn = server.getCommands().performPrefixedCommand(
                    deploymentStack.withSource(invalidDeploymentSource),
                    "/industrialagent deploy preview minecraft:gravel 0 zero");
            int invalidDeploymentResourceReturn = server.getCommands().performPrefixedCommand(
                    deploymentStack.withSource(invalidDeploymentSource),
                    "/industrialagent deploy preview bad::resource 1 zero");
            check(invalidDeploymentQuantityReturn == 0
                            && invalidDeploymentResourceReturn == 0
                            && !invalidDeploymentSource.messages().isEmpty(),
                    "PW-12 parser accepted an invalid quantity or non-ResourceId input");
            RecordingCommandSource deploymentWrongThreadSource = new RecordingCommandSource();
            AtomicReference<Integer> deploymentWrongThreadReturn = new AtomicReference<>();
            AtomicReference<Throwable> deploymentWrongThreadError = new AtomicReference<>();
            Thread deploymentWrongThread = new Thread(() -> {
                try {
                    deploymentWrongThreadReturn.set(server.getCommands().performPrefixedCommand(
                            deploymentStack.withSource(deploymentWrongThreadSource),
                            "/industrialagent deploy preview minecraft:gravel 1 zero"));
                } catch (Throwable failure) {
                    deploymentWrongThreadError.set(failure);
                }
            }, "steve-industrial-deployment-command-wrong-thread-probe");
            deploymentWrongThread.start();
            try {
                deploymentWrongThread.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for deployment wrong-thread probe", interrupted);
            }
            check(!deploymentWrongThread.isAlive()
                            && deploymentWrongThreadError.get() == null
                            && Integer.valueOf(0).equals(deploymentWrongThreadReturn.get())
                            && deploymentWrongThreadSource.messages().stream().anyMatch(message ->
                                    message.contains("code=WRONG_THREAD")
                                            && message.contains("stage=PLAN")),
                    "PW-12 off-thread command did not fail closed before RecipeManager access: "
                            + deploymentWrongThreadError.get() + " "
                            + deploymentWrongThreadSource.messages());
            RecordingCommandSource failureSource = new RecordingCommandSource();
            int failureReturn = server.getCommands().performPrefixedCommand(
                    successStack.withSource(failureSource),
                    "/industrialagent plan create minecraft:diamond 1");
            check(failureReturn == 0
                            && failureSource.messages().stream().anyMatch(message ->
                                    message.contains("code=RECIPE_NOT_FOUND")
                                            && message.contains("target=minecraft:diamond")
                                            && message.contains("trace=")),
                    "Read-only runtime command did not emit its typed missing-recipe failure");
            RecordingCommandSource bindingFailureSource = new RecordingCommandSource();
            int bindingFailureReturn = server.getCommands().performPrefixedCommand(
                    successStack.withSource(bindingFailureSource),
                    "/industrialagent bind create minecraft:diamond 1");
            check(bindingFailureReturn == 0
                            && bindingFailureSource.messages().stream().anyMatch(message ->
                                    message.contains("code=IMPLEMENTATION_NOT_FOUND")
                                            && message.contains("stage=COMMAND")
                                            && message.contains("trace=")),
                    "Read-only binding command did not emit its typed failure trace");
            RecordingCommandSource invalidSource = new RecordingCommandSource();
            int invalidQuantityReturn = server.getCommands().performPrefixedCommand(
                    successStack.withSource(invalidSource),
                    "/industrialagent plan create minecraft:gravel 0");
            int invalidResourceReturn = server.getCommands().performPrefixedCommand(
                    successStack.withSource(invalidSource),
                    "/industrialagent plan create bad::resource 1");
            check(invalidQuantityReturn == 0
                            && invalidResourceReturn == 0
                            && !invalidSource.messages().isEmpty(),
                    "Command parser accepted an invalid quantity or non-ResourceId input");
            RecordingCommandSource wrongThreadSource = new RecordingCommandSource();
            AtomicReference<Integer> wrongThreadReturn = new AtomicReference<>();
            AtomicReference<Throwable> wrongThreadError = new AtomicReference<>();
            Thread wrongThread = new Thread(() -> {
                try {
                    wrongThreadReturn.set(server.getCommands().performPrefixedCommand(
                            successStack.withSource(wrongThreadSource),
                            "/industrialagent plan create minecraft:gravel 1"));
                } catch (Throwable failure) {
                    wrongThreadError.set(failure);
                }
            }, "steve-industrial-r07-command-wrong-thread-probe");
            wrongThread.start();
            try {
                wrongThread.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for wrong-thread command probe", interrupted);
            }
            check(!wrongThread.isAlive()
                            && wrongThreadError.get() == null
                            && Integer.valueOf(0).equals(wrongThreadReturn.get())
                            && wrongThreadSource.messages().stream().anyMatch(message ->
                                    message.contains("code=WRONG_THREAD")
                                            && message.contains("stage=COMMAND")),
                    "Off-thread command did not fail closed before RecipeManager access: "
                            + wrongThreadError.get() + " " + wrongThreadSource.messages());
            RecordingCommandSource bindingWrongThreadSource = new RecordingCommandSource();
            AtomicReference<Integer> bindingWrongThreadReturn = new AtomicReference<>();
            AtomicReference<Throwable> bindingWrongThreadError = new AtomicReference<>();
            Thread bindingWrongThread = new Thread(() -> {
                try {
                    bindingWrongThreadReturn.set(server.getCommands().performPrefixedCommand(
                            successStack.withSource(bindingWrongThreadSource),
                            "/industrialagent bind create minecraft:gravel 1"));
                } catch (Throwable failure) {
                    bindingWrongThreadError.set(failure);
                }
            }, "steve-industrial-binding-command-wrong-thread-probe");
            bindingWrongThread.start();
            try {
                bindingWrongThread.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for binding wrong-thread command probe",
                        interrupted);
            }
            check(!bindingWrongThread.isAlive()
                            && bindingWrongThreadError.get() == null
                            && Integer.valueOf(0).equals(bindingWrongThreadReturn.get())
                            && bindingWrongThreadSource.messages().stream().anyMatch(message ->
                                    message.contains("code=WRONG_THREAD")
                                            && message.contains("stage=COMMAND")),
                    "Off-thread binding command did not fail closed before RecipeManager access: "
                            + bindingWrongThreadError.get() + " "
                            + bindingWrongThreadSource.messages());
            check(initial.discoveredRecipeCount() == discovered,
                    "Snapshot discovery count does not match RecipeManager: "
                            + initial.discoveredRecipeCount() + " != " + discovered);
            RuntimeRecipeCatalogEntry milling = requireRecipe(initial, "create:milling/cobblestone");
            RuntimeRecipeCatalogEntry pressing = requireRecipe(initial, "create:pressing/iron_ingot");
            check(milling.recipeType().equals(ResourceId.parse("create:milling")),
                    "Cobblestone runtime recipe lost its milling type");
            check(pressing.recipeType().equals(ResourceId.parse("create:pressing")),
                    "Iron-ingot runtime recipe lost its pressing type");
            check(milling.inputs().stream().anyMatch(ingredient ->
                            ingredient.runtimeCandidates().contains(ResourceId.parse("minecraft:cobblestone"))
                                    && ingredient.amount() == 1)
                            && hasResource(milling.outputs(), "minecraft:gravel", 1),
                    "Cobblestone milling quantities were not mapped from the runtime recipe");
            check(pressing.inputs().stream().anyMatch(ingredient ->
                            ingredient.runtimeCandidates().contains(ResourceId.parse("minecraft:iron_ingot"))
                                    && ingredient.amount() == 1)
                            && hasResource(pressing.outputs(), "create:iron_sheet", 1),
                    "Iron pressing quantities were not mapped from the runtime recipe");
            check(pressing.inputs().get(0) instanceof RecipeIngredient.TagReference tag
                            && tag.tagId().equals(ResourceId.parse("forge:ingots/iron")),
                    "Iron pressing input did not preserve its forge:ingots/iron tag identity");
            int millingMapped = Math.toIntExact(initial.catalog().recipes().stream()
                    .filter(recipe -> recipe.recipeType().equals(ResourceId.parse("create:milling")))
                    .count());
            int pressingMapped = Math.toIntExact(initial.catalog().recipes().stream()
                    .filter(recipe -> recipe.recipeType().equals(ResourceId.parse("create:pressing")))
                    .count());
            check(millingMapped > 1 && pressingMapped > 1,
                    "Runtime mapping looks like a fixed two-recipe catalog");
            check(initial.mappedRecipeCount() + initial.limitations().size()
                            == millingDiscovered + pressingDiscovered,
                    "Supported recipe enumeration did not end in exactly one mapped or rejected result");
            check(initial.limitations().stream().allMatch(limitation ->
                            limitation.recipeId().isPresent()
                                    && limitation.runtimeFingerprint().equals(initial.runtimeFingerprint())
                                    && !limitation.trace().isEmpty()),
                    "Rejected runtime recipe lacks typed trace or fingerprint evidence");
            Map<RuntimeKnowledgeFailureCode, Long> rejectionReasons = initial.limitations().stream()
                    .collect(Collectors.groupingBy(
                            RuntimeKnowledgeFailure::code,
                            TreeMap::new,
                            Collectors.counting()));
            initial.limitations().forEach(limitation -> logger.info(
                    "CREATE_RUNTIME_RECIPE_LIMITATION code={} recipe={} ingredient={} detail=\"{}\" trace={}",
                    limitation.code(),
                    limitation.recipeId().map(Object::toString).orElse("none"),
                    limitation.ingredientIdentity().orElse("none"),
                    limitation.detail(),
                    limitation.trace()));
            logger.info(
                    "CREATE_RUNTIME_RECIPE_LIMITATION_SUMMARY reasons={} warnings={}",
                    rejectionReasons,
                    initial.warnings().size());
            check(initial.catalog().recipes().stream().allMatch(recipe ->
                            recipe.source().runtimeVerified()
                                    && recipe.source().runtimeFingerprint()
                                            .equals(initial.runtimeFingerprint())),
                    "Mapped recipes lost runtime-discovered fingerprint attribution");
            RuntimeRecipeCatalogSnapshot cached = requireSuccess(catalog.snapshot());
            check(cached == initial, "Unchanged catalog read did not use its loader-neutral cache");
            String initialFingerprint = initial.runtimeFingerprint();

            long nextGeneration = catalog.invalidateForReload();
            requireFailure(
                    catalog.snapshot(), RuntimeKnowledgeFailureCode.DATAPACK_RELOAD_IN_PROGRESS);
            catalog.completeReload();
            RuntimeRecipeCatalogSnapshot rebuilt = requireSuccess(catalog.snapshot());
            RuntimeMachineCapabilityCatalogSnapshot rebuiltCapabilities =
                    requireSuccess(capabilityCatalog.snapshot(rebuilt));
            RuntimeMachineImplementationCatalogSnapshot rebuiltImplementations = requireSuccess(
                    implementationCatalog.snapshot(rebuilt, rebuiltCapabilities, false));
            check(rebuilt.reloadGeneration() == nextGeneration,
                    "Rebuilt catalog did not expose the invalidated generation");
            check(!initialFingerprint.equals(rebuilt.runtimeFingerprint()),
                    "Runtime catalog fingerprint did not change after reload invalidation");
            check(initial.catalog().recipes().stream().map(RuntimeRecipeCatalogEntry::recipeId).toList()
                            .equals(rebuilt.catalog().recipes().stream()
                                    .map(RuntimeRecipeCatalogEntry::recipeId).toList()),
                    "Reload changed deterministic recipe ordering without a recipe-content change");
            check(rebuiltCapabilities.runtimeFingerprint().equals(rebuilt.runtimeFingerprint())
                            && !rebuiltCapabilities.runtimeFingerprint()
                                    .equals(capabilities.runtimeFingerprint()),
                    "Capability catalog did not follow the rebuilt recipe runtime fingerprint");
            check(rebuiltImplementations.reloadGeneration() == nextGeneration
                            && rebuiltImplementations.runtimeFingerprint()
                                    .equals(rebuilt.runtimeFingerprint())
                            && !rebuiltImplementations.runtimeFingerprint()
                                    .equals(implementations.runtimeFingerprint()),
                    "Implementation catalog did not follow the rebuilt runtime fingerprint");
            check(before.equals(level.getBlockState(probe)),
                    "Read-only runtime recipe catalog changed the probe block state");

            try {
                DeceasedCraftRuntimeKnowledgeExporter.ExportSummary exported =
                        DeceasedCraftRuntimeKnowledgeExporter.export(
                                level, Path.of(System.getProperty("user.dir")).toRealPath());
                logger.info(
                        "STANDARD_RUNTIME_KNOWLEDGE_EXPORT PASS total={} milling={} pressing={} mapped={} rejected={} warnings={} fingerprint={} evidence={} worldMutation=false sessionCreated=false",
                        exported.recipeManagerTotal(),
                        exported.millingCount(),
                        exported.pressingCount(),
                        exported.mappedCount(),
                        exported.rejectedCount(),
                        exported.warningCount(),
                        exported.runtimeFingerprint(),
                        exported.evidencePath());
            } catch (IOException failure) {
                throw new IllegalStateException("Standard runtime knowledge export failed", failure);
            }

            logger.info(
                    "CREATE_RUNTIME_RECIPE_BOUNDARY PASS minecraft={} forge={} create={} discovered={} supported={} mapped={} rejected={} millingDiscovered={} pressingDiscovered={} millingMapped={} pressingMapped={} warnings={} capabilities=2 millingCapability=true pressingCapability=true implementations=2 millstoneImplementation=true pressImplementation=true implementationFingerprintReloaded=true boundPlans=2 gravelBoundNodes=2 gravelImplementation=create:mechanical_millstone ironSheetBoundNodes=1 ironSheetImplementation=create:mechanical_press bindingIngredientIdentity=true bindingLayoutAuthority=false physicalPlans=6 physicalOrientations=3 physicalVerifierChecks=13 physicalOutputType=VerifiedPhysicalPlan physicalDeterministic=true physicalFailures=3 sequencedAssemblyPhysicalizationRejected=true physicalExecutionAuthority=false bindingCommandSuccess=true bindingCommandTypedFailure=true bindingCommandWrongThreadRejected=true bindingCommandWorldMutation=false physicalRecipeProofs=2 cobblestoneMilling=true ironPressing=true gravelPlanRecipe=create:milling/cobblestone gravelQuantity=3 gravelExecutions=3 gravelSteps=2 gravelRaw=minecraft:andesite gravelCandidates={} ironSheetPlanRecipe=create:pressing/iron_ingot ironSheetQuantity=2 ironSheetExecutions=2 ironSheetCandidates={} deterministicPlans=true verifiedLogicalPlans=true commandSuccess=true commandTypedFailure=true commandValidationRejected=true commandWrongThreadRejected=true commandWorldMutation=false deploymentCommands=10 deploymentOrientations=3 deploymentTargets=2 deploymentPreview=true deploymentRisks=true deploymentBudget=true deploymentReadiness=true deploymentValidationRejected=true deploymentWrongThreadRejected=true deploymentFormalExecutable=false deploymentWorldMutation=false deploymentSessionCreated=false deploymentPlayerItemsConsumed=false deploymentMachineStarted=false deploymentLlmCalled=false deploymentFreeTextCoordinates=false generationBefore=0 generationAfter={} fingerprintChanged=true noWorldMutation=true sessionCreated=false",
                    catalog.runtime().minecraftVersion(),
                    catalog.runtime().loaderVersion(),
                    catalog.runtime().industrialModVersions().get("create"),
                    discovered,
                    millingDiscovered + pressingDiscovered,
                    initial.mappedRecipeCount(),
                    initial.limitations().size(),
                    millingDiscovered,
                    pressingDiscovered,
                    millingMapped,
                    pressingMapped,
                    initial.warnings().size(),
                    gravelPlan.rankedCandidates().size(),
                    ironSheetPlan.rankedCandidates().size(),
                    nextGeneration);
            server.halt(false);
        } catch (RuntimeException failure) {
            logger.error("CREATE_RUNTIME_RECIPE_BOUNDARY FAIL", failure);
            server.halt(false);
            throw failure;
        }
    }

    private static RuntimeRecipeCatalogSnapshot requireSuccess(RuntimeRecipeCatalogResult result) {
        if (!(result instanceof RuntimeRecipeCatalogResult.Success success)) {
            throw new IllegalStateException("Expected runtime recipe catalog success but received " + result);
        }
        return success.snapshot();
    }

    private static LayoutConstraints physicalConstraints(
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

    private static VerifiedPhysicalPlan requireSuccess(PhysicalizationResult result) {
        if (result instanceof PhysicalizationSuccess success) return success.plan();
        var failure = ((PhysicalizationFailure) result).failure();
        throw new IllegalStateException("Expected physicalization success but received "
                + failure.code() + ": " + failure.message());
    }

    private static void requireFailure(
            PhysicalizationResult result,
            LayoutFailureCode expected) {
        if (!(result instanceof PhysicalizationFailure failure)
                || failure.failure().code() != expected) {
            throw new IllegalStateException("Expected physicalization failure " + expected
                    + " but received " + result);
        }
    }

    private static RuntimeMachineCapabilityCatalogSnapshot requireSuccess(
            RuntimeMachineCapabilityCatalogResult result) {
        if (!(result instanceof RuntimeMachineCapabilityCatalogResult.Success success)) {
            throw new IllegalStateException(
                    "Expected runtime machine capability catalog success but received " + result);
        }
        return success.snapshot();
    }

    private static RuntimeMachineImplementationCatalogSnapshot requireSuccess(
            RuntimeMachineImplementationCatalogResult result) {
        if (!(result instanceof RuntimeMachineImplementationCatalogResult.Success success)) {
            throw new IllegalStateException(
                    "Expected runtime machine implementation catalog success but received " + result);
        }
        return success.snapshot();
    }

    private static RuntimeVerifiedPlanningResult requireSuccess(RuntimePlanningResult result) {
        if (!(result instanceof RuntimePlanningResult.Success success)) {
            throw new IllegalStateException(
                    "Expected runtime planning success but received " + result);
        }
        return success.result();
    }

    private static VerifiedImplementationBoundPlan requireSuccess(BindingResult result) {
        if (!(result instanceof BindingResult.Success success)) {
            throw new IllegalStateException(
                    "Expected runtime implementation binding success but received " + result);
        }
        return success.plan();
    }

    private static RuntimeMachineCapabilityDeclaration requireCapability(
            RuntimeMachineCapabilityCatalogSnapshot snapshot,
            String capabilityId) {
        ResourceId expected = ResourceId.parse(capabilityId);
        return snapshot.declarations().stream()
                .filter(value -> value.capability().capabilityId().equals(expected))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime machine capability was not declared: " + capabilityId));
    }

    private static void checkImplementation(
            RuntimeMachineImplementationCatalogSnapshot snapshot,
            String implementationId,
            String capabilityId,
            String provenRecipeId,
            String runtimeFingerprint) {
        ResourceId expectedImplementation = ResourceId.parse(implementationId);
        ResourceId expectedCapability = ResourceId.parse(capabilityId);
        ResourceId expectedRecipe = ResourceId.parse(provenRecipeId);
        MachineImplementationDescriptor descriptor = snapshot.catalog().implementations().stream()
                .filter(value -> value.implementationId().equals(expectedImplementation))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime machine implementation was not declared: " + implementationId));
        check(descriptor.capabilityIds().equals(Set.of(expectedCapability))
                        && descriptor.supportedRecipeTypes().equals(Set.of(expectedCapability)),
                "Runtime implementation capability or recipe type mismatch: " + implementationId);
        check(descriptor.executionSupport() == ImplementationExecutionSupport.PHYSICALLY_VERIFIED
                        && descriptor.bindingAllowed()
                        && descriptor.physicallyVerifiedRecipeIds().contains(expectedRecipe),
                "Runtime implementation lost its accepted physical recipe proof");
        check(descriptor.ports().stream().anyMatch(port ->
                            port.role() == ImplementationPortRole.ITEM_INPUT)
                        && descriptor.ports().stream().anyMatch(port ->
                            port.role() == ImplementationPortRole.ITEM_OUTPUT)
                        && descriptor.ports().stream().anyMatch(port ->
                            port.role() == ImplementationPortRole.ROTATIONAL_POWER_INPUT),
                "Runtime implementation lost item or rotational-power logical ports");
        check(descriptor.runtimeFingerprint().equals(runtimeFingerprint),
                "Runtime implementation lost exact recipe snapshot attribution");
    }

    private static void checkCapability(
            RuntimeMachineCapabilityDeclaration declaration,
            String capabilityId,
            String provenRecipeId,
            String runtimeFingerprint) {
        MachineCapability capability = declaration.capability();
        ResourceId expectedCapability = ResourceId.parse(capabilityId);
        check(capability.supportedRecipeTypes().equals(Set.of(expectedCapability)),
                "Runtime capability recipe type mismatch: " + capabilityId);
        check(capability.inputPortTypes().equals(Set.of(GenericResourceType.ITEM))
                        && capability.outputPortTypes().equals(Set.of(GenericResourceType.ITEM)),
                "Runtime capability resource types must be loader-neutral ITEM ports");
        check(capability.requiredResources().stream().anyMatch(requirement ->
                        requirement.resourceType() == GenericResourceType.ROTATIONAL_POWER
                                && requirement.minimumAmount() == 1
                                && requirement.continuous()),
                "Runtime capability lost its continuous rotational-power requirement");
        check(declaration.physicalExecutionAvailable()
                        && declaration.physicallyVerifiedRecipeIds()
                                .equals(Set.of(ResourceId.parse(provenRecipeId))),
                "Runtime capability physical availability is not backed by its accepted recipe");
        check(declaration.runtimeFingerprint().equals(runtimeFingerprint)
                        && !capability.collectibleEvidence().isEmpty()
                        && !capability.supportedDiagnostics().isEmpty(),
                "Runtime capability lost fingerprint, evidence, or diagnostic attribution");
    }

    private static void checkPlan(
            RuntimeVerifiedPlanningResult result,
            String target,
            long requested,
            String recipeId,
            String rawResource,
            long rawAmount,
            long executions,
            String runtimeFingerprint) {
        check(result.goal().target().equals(ResourceId.parse(target))
                        && result.goal().quantity() == requested,
                "Runtime plan lost its typed production goal");
        check(result.runtimeFingerprint().equals(runtimeFingerprint)
                        && result.verifiedPlan().candidate().selectedRecipes().stream()
                                .anyMatch(recipe -> recipe.recipeId()
                                        .equals(ResourceId.parse(recipeId))),
                "Runtime plan lost its RecipeManager recipe or fingerprint");
        CandidateQuantityConversion conversion = result.verifiedPlan().candidate()
                .quantityConversions().stream()
                .filter(value -> value.recipeId().equals(ResourceId.parse(recipeId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime plan lacks quantity evidence for " + recipeId));
        check(conversion.executions() == executions
                        && result.verifiedPlan().candidate().rawMaterials().stream()
                                .anyMatch(resource -> resource.resourceId()
                                                .equals(ResourceId.parse(rawResource))
                                        && resource.amount() == rawAmount),
                "Runtime plan quantity conversion or raw-material amount is wrong: conversion="
                        + conversion + " raw="
                        + result.verifiedPlan().candidate().rawMaterials());
        check(result.verifiedPlan().evidence().size() == 8,
                "Runtime plan did not pass the complete planning verifier");
    }

    private static ProductionGoal goal(
            String target,
            long quantity,
            Map<ResourceId, Long> owned) {
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
                owned);
    }

    private static RuntimeRecipeCatalogEntry requireRecipe(
            RuntimeRecipeCatalogSnapshot snapshot,
            String recipeId) {
        return snapshot.catalog().find(ResourceId.parse(recipeId)).orElseThrow(() ->
                new IllegalStateException("Runtime recipe was not mapped: " + recipeId));
    }

    private static boolean hasResource(
            List<dev.stevecreate.agent.core.process.ProcessResource> resources,
            String resourceId,
            long amount) {
        ResourceId expected = ResourceId.parse(resourceId);
        return resources.stream().anyMatch(resource ->
                resource.resourceId().equals(expected) && resource.amount() == amount);
    }

    private static RuntimeKnowledgeFailure requireFailure(
            RuntimeRecipeCatalogResult result,
            RuntimeKnowledgeFailureCode expectedCode) {
        if (!(result instanceof RuntimeRecipeCatalogResult.Failure failure)) {
            throw new IllegalStateException(
                    "Expected runtime recipe failure " + expectedCode + " but received " + result);
        }
        check(failure.failure().code() == expectedCode,
                "Expected runtime recipe failure " + expectedCode + " but received "
                        + failure.failure().code());
        return failure.failure();
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private static final class RecordingCommandSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
