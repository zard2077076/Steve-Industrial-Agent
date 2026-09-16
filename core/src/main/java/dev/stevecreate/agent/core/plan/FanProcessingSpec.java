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

/** Loader-neutral deterministic Phase-I contract shared by all four C-06 media. */
public record FanProcessingSpec(
        GenericProcessSpec genericSpec,
        FanProcessingMode mode,
        int powerTimeoutTicks,
        int minimumAirflowReachBlocks) {
    public static final int MAX_ITEM_COUNT = 64;

    private static final ResourceId ROTATIONAL_POWER =
            id("steve_industrial:capability/rotational_power");
    private static final ResourceId FAN_AIRFLOW =
            id("create:capability/fan_airflow_processing");
    private static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    private static final ResourceId AIRFLOW_OBSERVED =
            id("create:evidence/airflow_observed");
    private static final ResourceId MEDIUM_OBSERVED =
            id("create:evidence/fan_medium_observed");
    private static final ResourceId DWELL_OBSERVED =
            id("create:evidence/fan_dwell_observed");
    private static final ResourceId CHEST_OUTPUT_OBSERVED =
            id("create:evidence/chest_output_observed");
    private static final ResourceId MEDIUM = id("create:fan_medium");
    private static final ResourceId BOT_DEPLOYMENT = id("create:bot_medium_deployment");
    private static final ResourceId AIRFLOW_REACH = id("create:airflow_reach_blocks");

    public FanProcessingSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        Objects.requireNonNull(mode, "mode");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        if (minimumAirflowReachBlocks < 1 || minimumAirflowReachBlocks > 64) {
            throw new IllegalArgumentException("minimumAirflowReachBlocks must be 1..64");
        }
        validate(genericSpec, mode, minimumAirflowReachBlocks);
    }

    public FanProcessingSpec(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId inputItem,
            int inputCount,
            ResourceId outputItem,
            int outputCount,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(
                createGeneric(
                        recipeId, recipeType, inputItem, inputCount, outputItem,
                        outputCount, processingTimeoutTicks),
                FanProcessingMode.forRecipeType(recipeType),
                powerTimeoutTicks,
                3);
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
        FanProcessingMode mode = FanProcessingMode.forRecipeType(recipeType);
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        requireCount(inputCount, "inputCount");
        requireCount(outputCount, "outputCount");
        requireTimeout(processingTimeoutTicks, "processingTimeoutTicks");
        if (inputItem.equals(outputItem)) {
            throw new IllegalArgumentException("C-06 input and output must be distinct");
        }
        return new GenericProcessSpec(
                recipeId,
                recipeType,
                List.of(item(inputItem, inputCount)),
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, FAN_AIRFLOW),
                Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        AIRFLOW_OBSERVED, MEDIUM_OBSERVED, DWELL_OBSERVED,
                        CHEST_OUTPUT_OBSERVED),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(
                        MEDIUM, mode.name().toLowerCase(),
                        BOT_DEPLOYMENT,
                        mode.dangerousToBots() ? "hybrid_direct_or_unsupported" : "safe_standoff",
                        AIRFLOW_REACH, "3"));
    }

    private static void validate(
            GenericProcessSpec spec,
            FanProcessingMode mode,
            int reach) {
        if (!spec.recipeType().equals(mode.recipeType())
                || spec.inputs().size() != 1
                || spec.outputs().size() != 1
                || !spec.optionalByproducts().isEmpty()
                || spec.inputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.outputs().get(0).resourceType() != GenericResourceType.ITEM
                || spec.inputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || spec.inputs().get(0).resourceId().equals(spec.outputs().get(0).resourceId())
                || !spec.requiredMachineCapabilities().containsAll(
                        Set.of(ROTATIONAL_POWER, FAN_AIRFLOW))
                || !spec.requiredCompletionEvidence().containsAll(Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        AIRFLOW_OBSERVED, MEDIUM_OBSERVED, DWELL_OBSERVED,
                        CHEST_OUTPUT_OBSERVED))
                || !mode.name().toLowerCase().equals(spec.extensionData().get(MEDIUM))
                || !Integer.toString(reach).equals(spec.extensionData().get(AIRFLOW_REACH))) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported C-06 fan contract");
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
            throw new IllegalArgumentException(name + " is outside the bounded timeout");
        }
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
