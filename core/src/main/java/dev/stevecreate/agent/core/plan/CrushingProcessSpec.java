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

/** Create crushing differences wrapped around the shared loader-neutral process model. */
public record CrushingProcessSpec(GenericProcessSpec genericSpec, int powerTimeoutTicks) {
    public static final int MAX_ITEM_COUNT = 64;

    private static final ResourceId ROTATIONAL_POWER =
            id("steve_industrial:capability/rotational_power");
    private static final ResourceId CRUSHING_WHEELS =
            id("create:capability/crushing_wheels");
    private static final ResourceId ENTITY_INPUT =
            id("create:capability/item_entity_input");
    private static final ResourceId HOPPER_OUTPUT =
            id("minecraft:capability/hopper_output");
    private static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    private static final ResourceId CONTROLLER_VALID =
            id("create:evidence/crushing_controller_valid");
    private static final ResourceId WHEEL_DIRECTIONS =
            id("create:evidence/crushing_wheel_directions");
    private static final ResourceId PROBABILISTIC_OUTPUTS =
            id("create:evidence/probabilistic_outputs_observed");
    private static final ResourceId CHEST_OUTPUT =
            id("create:evidence/crushing_chest_output");
    private static final ResourceId PROCESSING_PATH = id("create:processing_path");
    private static final ResourceId OUTPUT_STORAGE = id("create:output_storage");

    public CrushingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validateShape(genericSpec);
    }

    public CrushingProcessSpec(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId expectedOutputItem,
            int minimumOutputCount,
            List<ProcessResource> optionalByproducts,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(createGenericSpec(
                recipeId,
                recipeType,
                inputItem,
                inputCount,
                expectedOutputItem,
                minimumOutputCount,
                optionalByproducts,
                processingTimeoutTicks), powerTimeoutTicks);
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

    public List<ProcessResource> optionalByproducts() {
        return genericSpec.optionalByproducts();
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
            List<ProcessResource> optionalByproducts,
            int processingTimeoutTicks) {
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "expectedOutputItem");
        requireItemCount(inputCount, "inputCount");
        requireItemCount(outputCount, "minimumOutputCount");
        List<ProcessResource> byproducts =
                List.copyOf(Objects.requireNonNull(optionalByproducts, "optionalByproducts"));
        if (byproducts.stream().anyMatch(value ->
                value.resourceType() != GenericResourceType.ITEM
                        || value.amount() > MAX_ITEM_COUNT)) {
            throw new IllegalArgumentException("C-05 byproducts must be bounded ITEM resources");
        }
        return new GenericProcessSpec(
                Objects.requireNonNull(recipeId, "recipeId"),
                Objects.requireNonNull(recipeType, "recipeType"),
                List.of(item(inputItem, inputCount)),
                List.of(item(outputItem, outputCount)),
                byproducts,
                Set.of(ROTATIONAL_POWER, CRUSHING_WHEELS, ENTITY_INPUT, HOPPER_OUTPUT),
                Set.of(
                        INPUT_CONSUMED,
                        PROCESS_COMPLETED,
                        OUTPUT_PRODUCED,
                        CONTROLLER_VALID,
                        WHEEL_DIRECTIONS,
                        PROBABILISTIC_OUTPUTS,
                        CHEST_OUTPUT),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(PROCESSING_PATH, "crushing_wheel_pair", OUTPUT_STORAGE, "hopper_chest"));
    }

    private static void validateShape(GenericProcessSpec spec) {
        if (spec.inputs().size() != 1
                || spec.outputs().size() != 1
                || spec.inputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.outputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.inputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.inputConsumptionRequirement() != InputConsumptionRequirement.EXACT_DECLARED
                || spec.outputVerificationRequirement() != OutputVerificationRequirement.AT_LEAST_DECLARED
                || !spec.requiredMachineCapabilities().containsAll(Set.of(
                        ROTATIONAL_POWER, CRUSHING_WHEELS, ENTITY_INPUT, HOPPER_OUTPUT))
                || !spec.requiredCompletionEvidence().containsAll(Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        CONTROLLER_VALID, WHEEL_DIRECTIONS,
                        PROBABILISTIC_OUTPUTS, CHEST_OUTPUT))
                || !"crushing_wheel_pair".equals(spec.extensionData().get(PROCESSING_PATH))
                || !"hopper_chest".equals(spec.extensionData().get(OUTPUT_STORAGE))) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported Create crushing contract");
        }
    }

    private static ProcessResource item(ResourceId itemId, int count) {
        return new ProcessResource(
                Objects.requireNonNull(itemId, "itemId"), GenericResourceType.ITEM, count);
    }

    private static void requireItemCount(int value, String name) {
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

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
