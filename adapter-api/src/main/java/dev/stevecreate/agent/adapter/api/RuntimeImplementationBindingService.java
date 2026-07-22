package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingFailure;
import dev.stevecreate.agent.core.binding.BindingFailureCode;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.BindingStage;
import dev.stevecreate.agent.core.binding.BoundRecipeInput;
import dev.stevecreate.agent.core.binding.ImplementationBindingService;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Preserves runtime Ingredient resolution while entering the loader-neutral binding service. */
public final class RuntimeImplementationBindingService {
    private final ImplementationBindingService binding = new ImplementationBindingService();

    public BindingResult bind(
            RuntimeVerifiedPlanningResult planning,
            RuntimeMachineImplementationCatalogSnapshot implementations,
            BindingConstraints constraints) {
        if (planning == null || implementations == null || constraints == null) {
            return failure(BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING, constraints,
                    "runtime_binding_inputs=present",
                    "Supply matching runtime planning, implementation and constraint snapshots");
        }
        if (!planning.runtimeFingerprint().equals(implementations.runtimeFingerprint())
                || !planning.runtimeFingerprint().equals(constraints.runtimeFingerprint())
                || !planning.capabilities().runtime().equals(implementations.runtime())) {
            return failure(BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH, constraints,
                    "planning_implementation_constraint_runtime=equal",
                    "Re-plan and re-bind from the same current runtime snapshot");
        }
        Map<ResourceId, List<BoundRecipeInput>> inputs = new LinkedHashMap<>();
        for (ResolvedRuntimeRecipe resolution : planning.resolvedRecipes().resolutions()) {
            if (planning.verifiedPlan().candidate().selectedRecipes().stream().noneMatch(
                    value -> value.recipeId().equals(resolution.resolvedRecipe().recipeId()))) {
                continue;
            }
            inputs.put(resolution.resolvedRecipe().recipeId(), resolution.selections().stream()
                    .map(selection -> new BoundRecipeInput(
                            selection.recipeId(),
                            selection.inputIndex(),
                            selection.ingredientKind(),
                            selection.ingredientIdentity(),
                            selection.selectedResource(),
                            selection.amount(),
                            selection.reason().name()))
                    .toList());
        }
        return binding.bind(
                planning.verifiedPlan(), implementations.catalog(), constraints, inputs);
    }

    private static BindingResult.Failure failure(
            BindingFailureCode code,
            BindingConstraints constraints,
            String constraint,
            String nextStep) {
        return new BindingResult.Failure(new BindingFailure(
                code,
                BindingStage.CATALOG_VALIDATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                constraints == null ? Optional.empty() : constraints.preferredAdapterId(),
                constraints == null ? "runtime:unavailable" : constraints.runtimeFingerprint(),
                constraint,
                List.of("runtime_binding:" + code.name().toLowerCase()),
                "Runtime implementation binding refused mismatched or incomplete provenance",
                false,
                nextStep));
    }
}
