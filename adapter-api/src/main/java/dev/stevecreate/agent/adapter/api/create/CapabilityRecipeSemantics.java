package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Complete bounded semantics for one target recipe discovered from the live RecipeManager. */
public record CapabilityRecipeSemantics(
        ResourceId recipeId,
        ResourceId recipeType,
        CreateCapabilityId capability,
        List<RecipeIngredient> itemInputs,
        List<FluidIngredientSemantics> fluidInputs,
        List<ProcessResource> fluidOutputs,
        MultiOutputSemantics itemOutputs,
        ProbabilisticOutputSemantics probabilisticOutputs,
        ProcessingEnvironmentRequirement environment,
        OptionalLong processingTicks,
        CapabilityRecipeSupport support,
        List<CapabilityRecipeLimitation> limitations,
        String runtimeFingerprint) {
    public CapabilityRecipeSemantics {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(capability, "capability");
        if (!recipeType.equals(capability.recipeType())) {
            throw new IllegalArgumentException("Capability and recipe type disagree");
        }
        itemInputs = CapabilityContracts.list(itemInputs, "itemInputs");
        fluidInputs = CapabilityContracts.list(fluidInputs, "fluidInputs");
        fluidOutputs = CapabilityContracts.list(fluidOutputs, "fluidOutputs");
        for (ProcessResource output : fluidOutputs) {
            if (output.resourceType() != GenericResourceType.FLUID) {
                throw new IllegalArgumentException("fluidOutputs contains a non-fluid resource");
            }
        }
        Objects.requireNonNull(itemOutputs, "itemOutputs");
        Objects.requireNonNull(probabilisticOutputs, "probabilisticOutputs");
        if (!itemOutputs.outputs().stream()
                .map(CapabilityOutput::probabilityPerMillion)
                .toList()
                .equals(probabilisticOutputs.probabilityNumerators())) {
            throw new IllegalArgumentException("Output and probability semantics disagree");
        }
        Objects.requireNonNull(environment, "environment");
        processingTicks = Objects.requireNonNull(processingTicks, "processingTicks");
        if (processingTicks.isPresent()
                && (processingTicks.getAsLong() < 1 || processingTicks.getAsLong() > 72_000)) {
            throw new IllegalArgumentException("processingTicks is outside its bound");
        }
        Objects.requireNonNull(support, "support");
        limitations = CapabilityContracts.list(limitations, "limitations").stream()
                .sorted(Comparator.comparing((CapabilityRecipeLimitation value) -> value.code().name())
                        .thenComparing(CapabilityRecipeLimitation::field)
                        .thenComparing(CapabilityRecipeLimitation::detail))
                .toList();
        boolean blocked = limitations.stream().anyMatch(CapabilityRecipeLimitation::blocksPhaseI);
        if ((support == CapabilityRecipeSupport.SUPPORTED_PHASE_I) == blocked) {
            throw new IllegalArgumentException(
                    "Supported recipes cannot have blocking limitations and blocked recipes must have one");
        }
        if (support == CapabilityRecipeSupport.SUPPORTED_PHASE_I
                && (itemOutputs.outputs().isEmpty()
                || !probabilisticOutputs.primaryIsDeterministic())) {
            throw new IllegalArgumentException(
                    "Phase-I support requires a mapped deterministic primary item output");
        }
        runtimeFingerprint = CapabilityContracts.text(runtimeFingerprint, "runtimeFingerprint");
    }
}
