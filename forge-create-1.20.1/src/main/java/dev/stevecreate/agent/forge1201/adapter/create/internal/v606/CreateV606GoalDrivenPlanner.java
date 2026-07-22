package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeIngredientSelection;
import dev.stevecreate.agent.adapter.api.RuntimeImplementationBindingService;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeVerifiedPlanningResult;
import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessContext;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessRefusal;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessResult;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessSuccess;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessVerifier;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.LayoutConstraints;
import dev.stevecreate.agent.core.layout.PhysicalizationFailure;
import dev.stevecreate.agent.core.layout.PhysicalizationResult;
import dev.stevecreate.agent.core.layout.PhysicalizationService;
import dev.stevecreate.agent.core.layout.PhysicalizationSuccess;
import dev.stevecreate.agent.core.layout.PlacementSnapshot;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;

/** Server-authoritative typed ProductionGoal to ExecutionReadyPlan orchestration. */
public final class CreateV606GoalDrivenPlanner {
    private CreateV606GoalDrivenPlanner() {}

    public static PlanningResult plan(
            ServerLevel level,
            ResourceId target,
            long quantity,
            Map<ResourceId, Long> ownedResources,
            BlockPos3i physicalAnchor,
            QuarterTurn orientation,
            ResourceId sessionId,
            Map<ResourceId, Long> availableInputs,
            ExecutionWorldClassification worldClassification) {
        return planInternal(level, target, quantity, ownedResources, physicalAnchor,
                orientation, sessionId, availableInputs, worldClassification, Set.of());
    }

    public static PlanningResult planForRecovery(
            ServerLevel level,
            ResourceId target,
            long quantity,
            Map<ResourceId, Long> ownedResources,
            BlockPos3i physicalAnchor,
            QuarterTurn orientation,
            ResourceId sessionId,
            Map<ResourceId, Long> availableInputs,
            ExecutionWorldClassification worldClassification,
            Set<BlockPos3i> journalOwnedPositions) {
        Objects.requireNonNull(journalOwnedPositions, "journalOwnedPositions");
        return planInternal(level, target, quantity, ownedResources, physicalAnchor,
                orientation, sessionId, availableInputs, worldClassification,
                Set.copyOf(journalOwnedPositions));
    }

    private static PlanningResult planInternal(
            ServerLevel level,
            ResourceId target,
            long quantity,
            Map<ResourceId, Long> ownedResources,
            BlockPos3i physicalAnchor,
            QuarterTurn orientation,
            ResourceId sessionId,
            Map<ResourceId, Long> availableInputs,
            ExecutionWorldClassification worldClassification,
            Set<BlockPos3i> journalOwnedPositions) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownedResources, "ownedResources");
        Objects.requireNonNull(physicalAnchor, "physicalAnchor");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(availableInputs, "availableInputs");
        Objects.requireNonNull(worldClassification, "worldClassification");
        if (!level.getServer().isSameThread()) {
            return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Planning must run on the authoritative server thread");
        }
        CreateExecutionWorldGuard.GuardResult guard =
                CreateExecutionWorldGuard.verify(level, worldClassification);
        if (guard instanceof CreateExecutionWorldGuard.GuardFailure failure) {
            return failure(failure.code(), failure.detail());
        }
        try {
            CreateRuntimeRecipeCatalog recipeAdapter = ForgeCreateRuntimeRecipeCatalogs.forLevel(level);
            RuntimeRecipeCatalogResult recipeResult = recipeAdapter.snapshot();
            if (!(recipeResult instanceof RuntimeRecipeCatalogResult.Success recipeSuccess)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Runtime recipe snapshot failed: "
                                + ((RuntimeRecipeCatalogResult.Failure) recipeResult).failure().code());
            }
            RuntimeRecipeCatalogSnapshot recipes = recipeSuccess.snapshot();
            CreateRuntimeMachineCapabilityCatalog capabilityAdapter =
                    new CreateRuntimeMachineCapabilityCatalog();
            RuntimeMachineCapabilityCatalogResult capabilityResult = capabilityAdapter.snapshot(recipes);
            if (!(capabilityResult instanceof RuntimeMachineCapabilityCatalogResult.Success capabilitySuccess)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Runtime capability snapshot failed");
            }
            RuntimeMachineCapabilityCatalogSnapshot capabilities = capabilitySuccess.snapshot();
            CreateRuntimeMachineImplementationCatalog implementationAdapter =
                    new CreateRuntimeMachineImplementationCatalog();
            RuntimeMachineImplementationCatalogResult implementationResult =
                    implementationAdapter.snapshot(recipes, capabilities, false);
            if (!(implementationResult
                    instanceof RuntimeMachineImplementationCatalogResult.Success implementationSuccess)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Runtime implementation snapshot failed");
            }
            RuntimeMachineImplementationCatalogSnapshot implementations =
                    implementationSuccess.snapshot();
            ProductionGoal goal = new ProductionGoal(
                    target, GenericResourceType.ITEM, quantity, Set.of(), Set.of(), Optional.of(8),
                    MaterialConstraints.none(), List.of(
                            PlanningStrategyPreference.MINIMIZE_STEPS,
                            PlanningStrategyPreference.PREFER_OWNED_RESOURCES), ownedResources);
            var planningResult = new RuntimeKnowledgePlanningService().plan(
                    recipes, capabilities, goal);
            if (!(planningResult instanceof RuntimePlanningResult.Success planningSuccessResult)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Runtime planning failed: " + planningResult);
            }
            RuntimeVerifiedPlanningResult planningSuccess = planningSuccessResult.result();
            BindingConstraints bindingConstraints = BindingConstraints.forRuntime(
                    ResourceId.parse(recipes.runtime().adapterId()),
                    recipes.runtime().industrialModVersions(), recipes.runtimeFingerprint());
            BindingResult bindingResult = new RuntimeImplementationBindingService().bind(
                    planningSuccess, implementations, bindingConstraints);
            if (!(bindingResult instanceof BindingResult.Success bindingSuccess)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Implementation binding failed: "
                                + ((BindingResult.Failure) bindingResult).failure().code());
            }
            VerifiedImplementationBoundPlan bound = bindingSuccess.plan();
            PlacementSnapshot captured = Boolean.getBoolean(
                    DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                    ? ForgeReadOnlyPlacementSnapshots.captureWithBoundedChunkReads(
                            level, recipes.runtimeFingerprint(), physicalAnchor, 40, 2, 10,
                            recipes.reloadGeneration())
                    : ForgeReadOnlyPlacementSnapshots.capture(
                            level, recipes.runtimeFingerprint(), physicalAnchor, 40, 2, 10,
                            recipes.reloadGeneration());
            PlacementSnapshot snapshot = recoverySnapshot(captured, journalOwnedPositions);
            LayoutConstraints layout = new LayoutConstraints(
                    physicalAnchor, List.of(orientation), 1, 64, 128, 200_000,
                    64, 64, 128, snapshot);
            PhysicalizationResult physicalResult = new PhysicalizationService().physicalize(
                    bound, CreateV606MachineGeometryCatalog.create(recipes.runtimeFingerprint()), layout);
            if (!(physicalResult instanceof PhysicalizationSuccess physicalSuccess)) {
                return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "Physicalization failed: "
                                + ((PhysicalizationFailure) physicalResult).failure().code());
            }
            VerifiedPhysicalPlan physical = physicalSuccess.plan();
            Set<BlockPos3i> loaded = snapshot.cells().entrySet().stream()
                    .filter(entry -> entry.getValue() != LayoutCellState.UNLOADED)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<ResourceId> adapterIds = bound.graph().boundProcessNodes().values().stream()
                    .map(value -> value.adapterId()).collect(Collectors.toSet());
            Set<ResourceId> implementationIds = bound.graph().boundProcessNodes().values().stream()
                    .map(value -> value.implementationId()).collect(Collectors.toSet());
            ExecutionReadinessContext readiness = new ExecutionReadinessContext(
                    sessionId, recipes.runtimeFingerprint(), adapterIds, implementationIds,
                    ResourceId.parse(level.dimension().location().toString()), worldClassification,
                    true, loaded, snapshot, availableInputs, 64, 128, 4_096,
                    true, true, true, Set.of());
            ExecutionReadinessResult readinessResult = new ExecutionReadinessVerifier().verify(
                    physical, readiness);
            if (readinessResult instanceof ExecutionReadinessRefusal refusal) {
                return failure(refusal.failure().code(), refusal.failure().detail());
            }
            return new Ready(
                    ((ExecutionReadinessSuccess) readinessResult).plan(), recipes.runtime(),
                    recipes.runtimeFingerprint(), recipes.reloadGeneration(),
                    planningSuccess.resolvedRecipes().resolutions().stream()
                            .flatMap(value -> value.selections().stream()).toList());
        } catch (RuntimeException exception) {
            return failure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Goal-driven planning failed closed: " + exception.getMessage());
        }
    }

    private static PlacementSnapshot recoverySnapshot(
            PlacementSnapshot captured,
            Set<BlockPos3i> journalOwnedPositions) {
        if (journalOwnedPositions.isEmpty()) return captured;
        Map<BlockPos3i, LayoutCellState> cells = new java.util.LinkedHashMap<>(captured.cells());
        for (BlockPos3i position : journalOwnedPositions) {
            LayoutCellState state = cells.get(position);
            if (state == null || state == LayoutCellState.UNLOADED) {
                throw new IllegalArgumentException(
                        "Recovery-owned position is outside the loaded placement snapshot: " + position);
            }
            cells.put(position, LayoutCellState.REPLACEABLE);
        }
        return new PlacementSnapshot(
                captured.runtimeFingerprint(), captured.snapshotGeneration(), cells);
    }

    public sealed interface PlanningResult permits Ready, Failure {}

    public record Ready(
            ExecutionReadyPlan executionReadyPlan,
            dev.stevecreate.agent.adapter.api.RuntimeFingerprint runtime,
            String runtimeRecipeFingerprint,
            long reloadGeneration,
            List<RuntimeIngredientSelection> ingredientSelections) implements PlanningResult {
        public Ready {
            Objects.requireNonNull(executionReadyPlan, "executionReadyPlan");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(runtimeRecipeFingerprint, "runtimeRecipeFingerprint");
            ingredientSelections = List.copyOf(ingredientSelections);
        }
    }

    public record Failure(
            ExecutionReadinessFailureCode code,
            String detail) implements PlanningResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail is blank");
        }
    }

    private static Failure failure(ExecutionReadinessFailureCode code, String detail) {
        return new Failure(code, detail);
    }
}
