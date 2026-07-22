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

/** Create belt-pressing differences wrapped around the shared process model. */
public record PressingProcessSpec(GenericProcessSpec genericSpec, int powerTimeoutTicks) {
    public static final int MAX_ITEM_COUNT = 64;
    public static final int MAX_TIMEOUT_TICKS = GenericProcessSpec.MAX_WAIT_TICKS;

    private static final ResourceId ROTATIONAL_POWER = id("steve_industrial:capability/rotational_power");
    private static final ResourceId BELT_TRANSPORT = id("create:capability/belt_transport");
    private static final ResourceId MECHANICAL_PRESS = id("create:capability/mechanical_press");
    private static final ResourceId INPUT_CONSUMED = id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED = id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED = id("steve_industrial:evidence/output_produced");
    private static final ResourceId BELT_INPUT = id("create:evidence/belt_input_observed");
    private static final ResourceId PRESS_CYCLE = id("create:evidence/press_cycle_observed");
    private static final ResourceId CHEST_OUTPUT = id("create:evidence/chest_output_observed");
    private static final ResourceId PROCESSING_PATH = id("create:processing_path");
    private static final ResourceId OUTPUT_STORAGE = id("create:output_storage");

    public PressingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validatePressingShape(genericSpec);
    }

    public PressingProcessSpec(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId expectedOutputItem,
            int minimumOutputCount,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(
                createGenericSpec(
                        recipeId,
                        recipeType,
                        inputItem,
                        inputCount,
                        expectedOutputItem,
                        minimumOutputCount,
                        processingTimeoutTicks),
                powerTimeoutTicks);
    }

    public ResourceId recipeId() {
        return genericSpec.recipeId();
    }

    public ResourceId recipeType() {
        return genericSpec.recipeType();
    }

    public ResourceId inputItem() {
        return genericSpec.inputs().get(0).resourceId();
    }

    public int inputCount() {
        return Math.toIntExact(genericSpec.inputs().get(0).amount());
    }

    public ResourceId expectedOutputItem() {
        return genericSpec.outputs().get(0).resourceId();
    }

    public int minimumOutputCount() {
        return Math.toIntExact(genericSpec.outputs().get(0).amount());
    }

    public int processingTimeoutTicks() {
        return genericSpec.maximumWaitTicks();
    }

    private static GenericProcessSpec createGenericSpec(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId outputItem,
            int outputCount,
            int processingTimeoutTicks) {
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "expectedOutputItem");
        if (inputItem.equals(outputItem)) {
            throw new IllegalArgumentException("Pressing input and output must be distinct");
        }
        requireItemCount(inputCount, "inputCount");
        requireItemCount(outputCount, "minimumOutputCount");
        return new GenericProcessSpec(
                recipeId,
                recipeType,
                List.of(item(inputItem, inputCount)),
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, BELT_TRANSPORT, MECHANICAL_PRESS),
                Set.of(
                        INPUT_CONSUMED,
                        PROCESS_COMPLETED,
                        OUTPUT_PRODUCED,
                        BELT_INPUT,
                        PRESS_CYCLE,
                        CHEST_OUTPUT),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(PROCESSING_PATH, "belt_press", OUTPUT_STORAGE, "chest"));
    }

    private static void validatePressingShape(GenericProcessSpec spec) {
        if (spec.inputs().size() != 1
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
                        Set.of(ROTATIONAL_POWER, BELT_TRANSPORT, MECHANICAL_PRESS))
                || !spec.requiredCompletionEvidence().containsAll(
                        Set.of(INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED, BELT_INPUT, PRESS_CYCLE, CHEST_OUTPUT))
                || !"belt_press".equals(spec.extensionData().get(PROCESSING_PATH))
                || !"chest".equals(spec.extensionData().get(OUTPUT_STORAGE))) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported Create belt pressing contract");
        }
    }

    private static ProcessResource item(ResourceId itemId, int count) {
        return new ProcessResource(
                Objects.requireNonNull(itemId, "itemId"), GenericResourceType.ITEM, count);
    }

    private static void requireItemCount(int value, String name) {
        if (value < 1 || value > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + MAX_ITEM_COUNT);
        }
    }

    private static void requireTimeout(int value, String name) {
        if (value < 1 || value > MAX_TIMEOUT_TICKS) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + MAX_TIMEOUT_TICKS);
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
