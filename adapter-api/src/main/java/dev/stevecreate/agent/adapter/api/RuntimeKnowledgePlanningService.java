package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidateLogicalGraphMapper;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.planning.CandidatePlanGenerator;
import dev.stevecreate.agent.core.planning.DeterministicPlanScorer;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingFailure;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingFailureResult;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingResult;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingSuccess;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.planning.PlanScoringContext;
import dev.stevecreate.agent.core.planning.PlanScoringWeights;
import dev.stevecreate.agent.core.planning.PlanningContext;
import dev.stevecreate.agent.core.planning.PlanningFailure;
import dev.stevecreate.agent.core.planning.PlanningFailureResult;
import dev.stevecreate.agent.core.planning.PlanningResult;
import dev.stevecreate.agent.core.planning.PlanningSuccess;
import dev.stevecreate.agent.core.planning.PlanningVerificationFailure;
import dev.stevecreate.agent.core.planning.PlanningVerificationFailureResult;
import dev.stevecreate.agent.core.planning.PlanningVerificationResult;
import dev.stevecreate.agent.core.planning.PlanningVerificationSuccess;
import dev.stevecreate.agent.core.planning.PlanningVerifier;
import dev.stevecreate.agent.core.planning.ProcessDependencyGraphBuilder;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.ScoredCandidate;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Connects immutable runtime snapshots to the complete deterministic logical-planning chain.
 * This service has no world, command, execution, layout or implementation-binding authority.
 */
public final class RuntimeKnowledgePlanningService {
    private static final PlanScoringWeights DEFAULT_WEIGHTS = new PlanScoringWeights(
            100, 20, 10, 1_000_000, 5, 1, 1, 10_000, 2);

    private final RuntimeRecipeCatalogResolver resolver = new RuntimeRecipeCatalogResolver();
    private final ProcessDependencyGraphBuilder dependencyBuilder =
            new ProcessDependencyGraphBuilder();
    private final CandidatePlanGenerator candidateGenerator = new CandidatePlanGenerator();
    private final DeterministicPlanScorer scorer = new DeterministicPlanScorer();
    private final CandidateLogicalGraphMapper graphMapper = new CandidateLogicalGraphMapper();
    private final PlanningVerifier verifier = new PlanningVerifier();

    public RuntimePlanningResult plan(
            RuntimeRecipeCatalogSnapshot recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities,
            ProductionGoal goal) {
        Objects.requireNonNull(goal, "goal");
        if (recipes == null || capabilities == null) {
            return failure(
                    RuntimeKnowledgeFailureCode.CAPABILITY_CATALOG_MISSING,
                    Optional.empty(),
                    goal,
                    adapterId(recipes, capabilities),
                    fingerprint(recipes, capabilities),
                    List.of(recipes == null
                            ? "runtime_recipe_catalog:missing"
                            : "runtime_capability_catalog:missing"),
                    "Runtime planning requires both recipe and capability snapshots");
        }
        if (!recipes.runtime().equals(capabilities.runtime())
                || !recipes.runtimeFingerprint().equals(capabilities.runtimeFingerprint())) {
            return failure(
                    RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                    Optional.empty(),
                    goal,
                    ResourceId.parse(recipes.runtime().adapterId()),
                    recipes.runtimeFingerprint(),
                    List.of(
                            "recipe_fingerprint:" + recipes.runtimeFingerprint(),
                            "capability_fingerprint:" + capabilities.runtimeFingerprint()),
                    "Runtime recipe and capability snapshots do not describe the same state");
        }

        RuntimeRecipeCatalogResolutionResult resolution = resolver.resolve(recipes, goal);
        if (resolution instanceof RuntimeRecipeCatalogResolutionResult.Failure failed) {
            return new RuntimePlanningResult.Failure(failed.failure());
        }
        ResolvedRuntimeRecipeCatalog resolved =
                ((RuntimeRecipeCatalogResolutionResult.Success) resolution).resolved();
        PlanningContext planningContext = planningContext(resolved, capabilities);
        PlanningResult dependencyResult = dependencyBuilder.plan(
                goal, resolved.catalog(), capabilities.catalog(), planningContext);
        if (dependencyResult instanceof PlanningFailureResult failed) {
            return translate(failed.failure(), goal, recipes);
        }

        try {
            List<CandidatePlan> candidates = candidateGenerator.generate(
                    (PlanningSuccess) dependencyResult);
            List<ScoredCandidate> ranked = scorer.rank(
                    candidates,
                    DEFAULT_WEIGHTS,
                    scoringContext(capabilities, resolved));
            CandidatePlan selected = ranked.get(0).candidate();
            LogicalGraphMappingResult mapping = graphMapper.map(
                    selected, capabilities.catalog(), planningContext);
            if (mapping instanceof LogicalGraphMappingFailureResult failed) {
                return translate(failed.failure(), goal, recipes);
            }
            PlanningVerificationResult verification = verifier.verify(
                    selected,
                    ((LogicalGraphMappingSuccess) mapping).graph(),
                    capabilities.catalog(),
                    planningContext);
            if (verification instanceof PlanningVerificationFailureResult failed) {
                return translate(failed.failure(), goal, recipes);
            }
            return new RuntimePlanningResult.Success(new RuntimeVerifiedPlanningResult(
                    goal,
                    resolved,
                    capabilities,
                    ranked,
                    ((PlanningVerificationSuccess) verification).plan(),
                    recipes.runtimeFingerprint()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return failure(
                    RuntimeKnowledgeFailureCode.QUANTITY_CONVERSION_INVALID,
                    Optional.empty(),
                    goal,
                    ResourceId.parse(recipes.runtime().adapterId()),
                    recipes.runtimeFingerprint(),
                    List.of("planning:bounded_conversion", "exception:" + exception.getClass().getName()),
                    "Runtime planning could not produce a bounded deterministic quantity conversion");
        }
    }

    private static PlanningContext planningContext(
            ResolvedRuntimeRecipeCatalog recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities) {
        Set<ResourceId> adapters = new LinkedHashSet<>();
        Set<String> mods = new TreeSet<>();
        EnumSet<GenericResourceType> types = EnumSet.of(GenericResourceType.ITEM);
        recipes.resolutions().forEach(value -> {
            adapters.add(value.resolvedRecipe().source().adapterId());
            mods.add(value.resolvedRecipe().source().sourceModId());
            types.addAll(value.resolvedRecipe().requiredResourceTypes());
        });
        capabilities.catalog().capabilities().forEach(value -> {
            adapters.add(value.adapterId());
            types.addAll(value.inputPortTypes());
            types.addAll(value.outputPortTypes());
            value.requiredResources().forEach(requirement -> types.add(requirement.resourceType()));
        });
        mods.addAll(capabilities.runtime().industrialModVersions().keySet());
        return new PlanningContext(adapters, mods, types);
    }

    private static PlanScoringContext scoringContext(
            RuntimeMachineCapabilityCatalogSnapshot capabilities,
            ResolvedRuntimeRecipeCatalog recipes) {
        Set<ResourceId> available = new LinkedHashSet<>();
        capabilities.catalog().capabilities().stream()
                .map(MachineCapability::capabilityId)
                .forEach(available::add);
        TreeSet<String> preferred = new TreeSet<>();
        recipes.resolutions().forEach(value ->
                preferred.add(value.resolvedRecipe().source().sourceModId()));
        return new PlanScoringContext(available, List.copyOf(preferred));
    }

    private static RuntimePlanningResult translate(
            PlanningFailure failure,
            ProductionGoal goal,
            RuntimeRecipeCatalogSnapshot snapshot) {
        RuntimeKnowledgeFailureCode code = switch (failure.code()) {
            case TARGET_NOT_FOUND, RECIPE_NOT_FOUND -> RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND;
            case MACHINE_CAPABILITY_MISSING -> RuntimeKnowledgeFailureCode.MACHINE_CAPABILITY_MISSING;
            case REQUIRED_MOD_UNAVAILABLE -> RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE;
            case INVALID_QUANTITY -> RuntimeKnowledgeFailureCode.QUANTITY_CONVERSION_INVALID;
            case CONSTRAINT_CONFLICT, UNSATISFIABLE_INPUT ->
                    RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED;
            case RESOURCE_TYPE_UNSUPPORTED -> RuntimeKnowledgeFailureCode.OUTPUT_UNSUPPORTED;
            case DEPENDENCY_CYCLE, DEPTH_LIMIT_EXCEEDED, AMBIGUOUS_OUTPUT ->
                    RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED;
        };
        return failure(
                code,
                failure.location().recipeId(),
                goal,
                ResourceId.parse(snapshot.runtime().adapterId()),
                snapshot.runtimeFingerprint(),
                failure.tracePath().stream().map(value -> "dependency:" + value).toList(),
                failure.reason());
    }

    private static RuntimePlanningResult translate(
            LogicalGraphMappingFailure failure,
            ProductionGoal goal,
            RuntimeRecipeCatalogSnapshot snapshot) {
        RuntimeKnowledgeFailureCode code = switch (failure.code()) {
            case MACHINE_CAPABILITY_MISSING, MACHINE_CAPABILITY_INCOMPATIBLE ->
                    RuntimeKnowledgeFailureCode.MACHINE_CAPABILITY_MISSING;
            case CAPABILITY_ADAPTER_UNAVAILABLE -> RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE;
            case INPUT_ALLOCATION_UNRESOLVED ->
                    RuntimeKnowledgeFailureCode.INGREDIENT_CHOICE_UNRESOLVED;
            case TARGET_OUTPUT_MISSING, TARGET_OUTPUT_AMBIGUOUS ->
                    RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND;
            case QUANTITY_OVERFLOW -> RuntimeKnowledgeFailureCode.QUANTITY_CONVERSION_INVALID;
            case CANDIDATE_INCONSISTENT, RESOURCE_TYPE_UNSUPPORTED ->
                    RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED;
        };
        return failure(
                code,
                Optional.empty(),
                goal,
                ResourceId.parse(snapshot.runtime().adapterId()),
                snapshot.runtimeFingerprint(),
                failure.trace().stream().map(value -> "logical_mapping:" + value).toList(),
                failure.detail());
    }

    private static RuntimePlanningResult translate(
            PlanningVerificationFailure failure,
            ProductionGoal goal,
            RuntimeRecipeCatalogSnapshot snapshot) {
        RuntimeKnowledgeFailureCode code = switch (failure.code()) {
            case MACHINE_CAPABILITY_UNDECLARED ->
                    RuntimeKnowledgeFailureCode.MACHINE_CAPABILITY_MISSING;
            case ADAPTER_UNAVAILABLE -> RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE;
            case QUANTITY_INCONSISTENT, TARGET_UNSATISFIED ->
                    RuntimeKnowledgeFailureCode.QUANTITY_CONVERSION_INVALID;
            case CANDIDATE_GRAPH_MISMATCH, DEPENDENCY_UNTRACEABLE,
                    PRODUCTION_SOURCE_MISSING, EDGE_RESOURCE_MISMATCH,
                    DEPENDENCY_CYCLE, RESOURCE_TYPE_UNSUPPORTED ->
                    RuntimeKnowledgeFailureCode.RECIPE_MAPPING_FAILED;
        };
        return failure(
                code,
                Optional.empty(),
                goal,
                ResourceId.parse(snapshot.runtime().adapterId()),
                snapshot.runtimeFingerprint(),
                failure.trace().stream().map(value -> "planning_verifier:" + value).toList(),
                failure.detail());
    }

    private static RuntimePlanningResult failure(
            RuntimeKnowledgeFailureCode code,
            Optional<ResourceId> recipeId,
            ProductionGoal goal,
            ResourceId adapterId,
            String fingerprint,
            List<String> trace,
            String detail) {
        List<String> boundedTrace = trace.isEmpty()
                ? List.of("target:" + goal.target())
                : trace;
        return new RuntimePlanningResult.Failure(new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.PLANNING,
                recipeId,
                Optional.of(goal.target()),
                Optional.empty(),
                adapterId,
                fingerprint,
                boundedTrace,
                detail));
    }

    private static ResourceId adapterId(
            RuntimeRecipeCatalogSnapshot recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities) {
        if (recipes != null) {
            return ResourceId.parse(recipes.runtime().adapterId());
        }
        if (capabilities != null) {
            return ResourceId.parse(capabilities.runtime().adapterId());
        }
        return ResourceId.parse("steve_industrial:runtime_knowledge");
    }

    private static String fingerprint(
            RuntimeRecipeCatalogSnapshot recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities) {
        if (recipes != null) {
            return recipes.runtimeFingerprint();
        }
        if (capabilities != null) {
            return capabilities.runtimeFingerprint();
        }
        return "runtime:unavailable";
    }
}
