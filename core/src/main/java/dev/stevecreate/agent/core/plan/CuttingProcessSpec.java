package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loader-neutral C-07 item-only cutting contract. */
public record CuttingProcessSpec(GenericProcessSpec genericSpec, int powerTimeoutTicks) {
    public static final int MAX_ITEM_COUNT = 64;

    private static final ResourceId ROTATIONAL_POWER =
            id("steve_industrial:capability/rotational_power");
    private static final ResourceId DEPOT_INPUT = id("create:capability/depot_input");
    private static final ResourceId MECHANICAL_SAW = id("create:capability/mechanical_saw_item_processing");
    private static final ResourceId INPUT_CONSUMED = id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED = id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED = id("steve_industrial:evidence/output_produced");
    private static final ResourceId DEPOT_INPUT_OBSERVED = id("create:evidence/depot_input_observed");
    private static final ResourceId SAW_CYCLE_OBSERVED = id("create:evidence/saw_cycle_observed");
    private static final ResourceId CHEST_OUTPUT_OBSERVED = id("create:evidence/chest_output_observed");
    private static final ResourceId PROCESSING_PATH = id("create:processing_path");
    private static final ResourceId OUTPUT_STORAGE = id("create:output_storage");
    private static final ResourceId WORLD_CUTTING = id("create:world_cutting");

    public CuttingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validate(genericSpec);
    }

    public CuttingProcessSpec(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId outputItem,
            int outputCount,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(createGeneric(
                recipeId, recipeType, inputItem, inputCount, outputItem, outputCount,
                processingTimeoutTicks), powerTimeoutTicks);
    }

    public ResourceId recipeId() { return genericSpec.recipeId(); }
    public ResourceId recipeType() { return genericSpec.recipeType(); }
    public ResourceId inputItem() { return genericSpec.inputs().get(0).resourceId(); }
    public int inputCount() { return Math.toIntExact(genericSpec.inputs().get(0).amount()); }
    public ResourceId expectedOutputItem() { return genericSpec.outputs().get(0).resourceId(); }
    public int minimumOutputCount() { return Math.toIntExact(genericSpec.outputs().get(0).amount()); }
    public int processingTimeoutTicks() { return genericSpec.maximumWaitTicks(); }

    private static GenericProcessSpec createGeneric(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId outputItem,
            int outputCount,
            int processingTimeoutTicks) {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        if (!recipeType.equals(id("create:cutting"))) {
            throw new IllegalArgumentException("C-07 accepts only create:cutting recipes");
        }
        if (inputItem.equals(outputItem)) {
            throw new IllegalArgumentException("C-07 input and output must be distinct");
        }
        requireCount(inputCount, "inputCount");
        requireCount(outputCount, "outputCount");
        requireTimeout(processingTimeoutTicks, "processingTimeoutTicks");
        return new GenericProcessSpec(
                recipeId,
                recipeType,
                List.of(item(inputItem, inputCount)),
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, DEPOT_INPUT, MECHANICAL_SAW),
                Set.of(
                        INPUT_CONSUMED,
                        PROCESS_COMPLETED,
                        OUTPUT_PRODUCED,
                        DEPOT_INPUT_OBSERVED,
                        SAW_CYCLE_OBSERVED,
                        CHEST_OUTPUT_OBSERVED),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(
                        PROCESSING_PATH, "upward_mechanical_saw",
                        OUTPUT_STORAGE, "chest",
                        WORLD_CUTTING, "forbidden"));
    }

    private static void validate(GenericProcessSpec spec) {
        if (!spec.recipeType().equals(id("create:cutting"))
                || spec.inputs().size() != 1
                || spec.outputs().size() != 1
                || !spec.optionalByproducts().isEmpty()
                || spec.inputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.outputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.inputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.inputs().get(0).resourceId().equals(spec.outputs().get(0).resourceId())
                || spec.inputConsumptionRequirement() != InputConsumptionRequirement.EXACT_DECLARED
                || spec.outputVerificationRequirement() != OutputVerificationRequirement.AT_LEAST_DECLARED
                || !spec.requiredMachineCapabilities().containsAll(
                        Set.of(ROTATIONAL_POWER, DEPOT_INPUT, MECHANICAL_SAW))
                || !spec.requiredCompletionEvidence().containsAll(Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        DEPOT_INPUT_OBSERVED, SAW_CYCLE_OBSERVED, CHEST_OUTPUT_OBSERVED))
                || !"upward_mechanical_saw".equals(spec.extensionData().get(PROCESSING_PATH))
                || !"chest".equals(spec.extensionData().get(OUTPUT_STORAGE))
                || !"forbidden".equals(spec.extensionData().get(WORLD_CUTTING))) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported C-07 item-only contract");
        }
    }

    private static ProcessResource item(ResourceId id, int count) {
        return new ProcessResource(id, GenericResourceType.ITEM, count);
    }

    private static void requireCount(int value, String name) {
        if (value < 1 || value > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException(name + " must be between 1 and " + MAX_ITEM_COUNT);
        }
    }

    private static void requireTimeout(int value, String name) {
        if (value < 1 || value > GenericProcessSpec.MAX_WAIT_TICKS) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + GenericProcessSpec.MAX_WAIT_TICKS);
        }
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
