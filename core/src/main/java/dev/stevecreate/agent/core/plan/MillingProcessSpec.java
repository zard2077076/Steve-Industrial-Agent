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

/** Create milling differences wrapped around the shared runtime-verifiable process model. */
public record MillingProcessSpec(GenericProcessSpec genericSpec, int powerTimeoutTicks) {
    public static final int MAX_ITEM_COUNT = 64;
    public static final int MAX_TIMEOUT_TICKS = GenericProcessSpec.MAX_WAIT_TICKS;

    private static final ResourceId ROTATIONAL_POWER = id("steve_industrial:capability/rotational_power");
    private static final ResourceId MILLSTONE_MILLING = id("create:capability/millstone_milling");
    private static final ResourceId INPUT_CONSUMED = id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED = id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED = id("steve_industrial:evidence/output_produced");
    private static final ResourceId MILLSTONE_OUTPUT = id("create:evidence/millstone_inventory_output");
    private static final ResourceId PROCESSING_PATH = id("create:processing_path");

    public MillingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validateMillingShape(genericSpec);
    }

    public MillingProcessSpec(
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
        requireItemCount(inputCount, "inputCount");
        requireItemCount(outputCount, "minimumOutputCount");
        return new GenericProcessSpec(
                recipeId,
                recipeType,
                List.of(item(inputItem, inputCount)),
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, MILLSTONE_MILLING),
                Set.of(INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED, MILLSTONE_OUTPUT),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(PROCESSING_PATH, "millstone_inventory"));
    }

    private static void validateMillingShape(GenericProcessSpec spec) {
        if (spec.inputs().size() != 1
                || spec.outputs().size() != 1
                || !spec.optionalByproducts().isEmpty()
                || spec.inputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.outputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.inputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.inputConsumptionRequirement() != InputConsumptionRequirement.EXACT_DECLARED
                || spec.outputVerificationRequirement() != OutputVerificationRequirement.AT_LEAST_DECLARED
                || !spec.requiredMachineCapabilities().containsAll(Set.of(ROTATIONAL_POWER, MILLSTONE_MILLING))
                || !spec.requiredCompletionEvidence().containsAll(
                        Set.of(INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED, MILLSTONE_OUTPUT))
                || !"millstone_inventory".equals(spec.extensionData().get(PROCESSING_PATH))) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported Create millstone processing contract");
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
