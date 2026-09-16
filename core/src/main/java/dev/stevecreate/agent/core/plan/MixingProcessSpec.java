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

/** Counted, deterministic C-08 mixing contract with exact optional fluid inputs. */
public record MixingProcessSpec(
        GenericProcessSpec genericSpec,
        BasinHeatMode heatMode,
        int powerTimeoutTicks) {
    public static final int MAX_ITEM_INPUTS = 9;
    public static final int MAX_ITEM_COUNT = 64;
    public static final int MAX_FLUID_INPUTS = 4;
    public static final long MAX_FLUID_MILLIBUCKETS = 64_000L;
    private static final ResourceId MIXING = id("create:mixing");
    private static final ResourceId ROTATIONAL_POWER =
            id("steve_industrial:capability/rotational_power");
    private static final ResourceId BASIN_MIXER =
            id("create:capability/basin_mechanical_mixer");
    private static final ResourceId INPUT_CONSUMED =
            id("steve_industrial:evidence/input_consumed");
    private static final ResourceId PROCESS_COMPLETED =
            id("steve_industrial:evidence/process_completed");
    private static final ResourceId OUTPUT_PRODUCED =
            id("steve_industrial:evidence/output_produced");
    private static final ResourceId BASIN_CONTENTS =
            id("create:evidence/mixing_basin_contents_observed");
    private static final ResourceId MIXER_CYCLE =
            id("create:evidence/mixer_cycle_observed");
    private static final ResourceId HEAT_OBSERVED =
            id("create:evidence/mixing_heat_observed");
    private static final ResourceId OUTPUT_STORED =
            id("create:evidence/mixing_output_stored");
    private static final ResourceId HEAT = id("create:basin_heat");
    private static final ResourceId FLUIDS = id("create:basin_fluids");
    private static final ResourceId INGREDIENT_MATCH =
            id("create:ingredient_resolution");
    private static final ResourceId PROCESSING_PATH =
            id("create:processing_path");

    public MixingProcessSpec {
        Objects.requireNonNull(genericSpec, "genericSpec");
        Objects.requireNonNull(heatMode, "heatMode");
        requireTimeout(powerTimeoutTicks, "powerTimeoutTicks");
        validate(genericSpec, heatMode);
    }

    public MixingProcessSpec(
            ResourceId recipeId,
            List<ProcessResource> inputs,
            ResourceId outputItem,
            int outputCount,
            BasinHeatMode heatMode,
            int powerTimeoutTicks,
            int processingTimeoutTicks) {
        this(createGeneric(
                recipeId, inputs, outputItem, outputCount, heatMode,
                processingTimeoutTicks), heatMode, powerTimeoutTicks);
    }

    public ResourceId recipeId() { return genericSpec.recipeId(); }
    public ResourceId recipeType() { return genericSpec.recipeType(); }
    public List<ProcessResource> itemInputs() {
        return genericSpec.inputs().stream()
                .filter(value -> value.resourceType() == GenericResourceType.ITEM)
                .toList();
    }
    public List<ProcessResource> fluidInputs() {
        return genericSpec.inputs().stream()
                .filter(value -> value.resourceType() == GenericResourceType.FLUID)
                .toList();
    }
    public ResourceId expectedOutputItem() {
        return genericSpec.outputs().get(0).resourceId();
    }
    public int expectedOutputCount() {
        return Math.toIntExact(genericSpec.outputs().get(0).amount());
    }
    public int processingTimeoutTicks() { return genericSpec.maximumWaitTicks(); }
    public int totalInputCount() {
        return itemInputs().stream()
                .mapToInt(value -> Math.toIntExact(value.amount()))
                .reduce(0, Math::addExact);
    }
    public long totalFluidInputMillibuckets() {
        return fluidInputs().stream()
                .mapToLong(ProcessResource::amount)
                .reduce(0L, Math::addExact);
    }
    public boolean requiresFuel() { return heatMode == BasinHeatMode.HEATED; }

    private static GenericProcessSpec createGeneric(
            ResourceId recipeId,
            List<ProcessResource> processInputs,
            ResourceId outputItem,
            int outputCount,
            BasinHeatMode heatMode,
            int processingTimeoutTicks) {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(heatMode, "heatMode");
        List<ProcessResource> inputs =
                List.copyOf(Objects.requireNonNull(processInputs, "processInputs"));
        requireTimeout(processingTimeoutTicks, "processingTimeoutTicks");
        requireCount(outputCount, "outputCount");
        List<ProcessResource> items = inputs.stream()
                .filter(value -> value.resourceType() == GenericResourceType.ITEM)
                .toList();
        List<ProcessResource> fluids = inputs.stream()
                .filter(value -> value.resourceType() == GenericResourceType.FLUID)
                .toList();
        if (items.isEmpty() || items.size() > MAX_ITEM_INPUTS) {
            throw new IllegalArgumentException(
                    "C-08 requires 1..9 counted item inputs");
        }
        if (fluids.size() > MAX_FLUID_INPUTS
                || inputs.size() != items.size() + fluids.size()) {
            throw new IllegalArgumentException(
                    "C-08 accepts only bounded ITEM and FLUID inputs");
        }
        for (ProcessResource input : items) {
            if (input.amount() > MAX_ITEM_COUNT) {
                throw new IllegalArgumentException(
                        "C-08 inputs must be bounded ITEM resources");
            }
        }
        for (ProcessResource input : fluids) {
            if (input.amount() > MAX_FLUID_MILLIBUCKETS) {
                throw new IllegalArgumentException(
                        "C-08 fluid inputs must be bounded millibucket resources");
            }
        }
        return new GenericProcessSpec(
                recipeId,
                MIXING,
                inputs,
                List.of(item(outputItem, outputCount)),
                List.of(),
                Set.of(ROTATIONAL_POWER, BASIN_MIXER),
                Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        BASIN_CONTENTS, MIXER_CYCLE, HEAT_OBSERVED,
                        OUTPUT_STORED),
                processingTimeoutTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.EXACT_DECLARED,
                Map.of(
                        HEAT, heatMode.name().toLowerCase(),
                        FLUIDS, fluids.isEmpty() ? "none" : fluidDeclaration(fluids),
                        INGREDIENT_MATCH, "live_tag_or_any_of",
                        PROCESSING_PATH, "basin_mechanical_mixer"));
    }

    private static void validate(
            GenericProcessSpec spec, BasinHeatMode heatMode) {
        if (!spec.recipeType().equals(MIXING)
                || spec.inputs().isEmpty()
                || spec.inputs().stream().filter(value ->
                        value.resourceType() == GenericResourceType.ITEM).count() < 1
                || spec.inputs().stream().filter(value ->
                        value.resourceType() == GenericResourceType.ITEM).count()
                        > MAX_ITEM_INPUTS
                || spec.inputs().stream().filter(value ->
                        value.resourceType() == GenericResourceType.FLUID).count()
                        > MAX_FLUID_INPUTS
                || spec.inputs().stream().anyMatch(value -> switch (value.resourceType()) {
                    case ITEM -> value.amount() > MAX_ITEM_COUNT;
                    case FLUID -> value.amount() > MAX_FLUID_MILLIBUCKETS;
                    default -> true;
                })
                || spec.outputs().size() != 1
                || spec.outputs().get(0).resourceType()
                        != GenericResourceType.ITEM
                || spec.outputs().get(0).amount() > MAX_ITEM_COUNT
                || !spec.optionalByproducts().isEmpty()
                || !heatMode.name().toLowerCase().equals(
                        spec.extensionData().get(HEAT))
                || !expectedFluidDeclaration(spec.inputs()).equals(
                        spec.extensionData().get(FLUIDS))
                || !"live_tag_or_any_of".equals(
                        spec.extensionData().get(INGREDIENT_MATCH))
                || !"basin_mechanical_mixer".equals(
                        spec.extensionData().get(PROCESSING_PATH))
                || !spec.requiredMachineCapabilities().containsAll(
                        Set.of(ROTATIONAL_POWER, BASIN_MIXER))
                || !spec.requiredCompletionEvidence().containsAll(Set.of(
                        INPUT_CONSUMED, PROCESS_COMPLETED, OUTPUT_PRODUCED,
                        BASIN_CONTENTS, MIXER_CYCLE, HEAT_OBSERVED,
                        OUTPUT_STORED))
                || spec.outputVerificationRequirement()
                        != OutputVerificationRequirement.EXACT_DECLARED) {
            throw new IllegalArgumentException(
                    "genericSpec does not preserve the supported C-08 contract");
        }
    }

    private static ProcessResource item(ResourceId id, int count) {
        return new ProcessResource(id, GenericResourceType.ITEM, count);
    }

    private static String expectedFluidDeclaration(List<ProcessResource> inputs) {
        List<ProcessResource> fluids = inputs.stream()
                .filter(value -> value.resourceType() == GenericResourceType.FLUID)
                .toList();
        return fluids.isEmpty() ? "none" : fluidDeclaration(fluids);
    }

    private static String fluidDeclaration(List<ProcessResource> fluids) {
        return fluids.stream()
                .sorted(java.util.Comparator.comparing(value -> value.resourceId().toString()))
                .map(value -> value.resourceId() + "@" + value.amount() + "mB")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static void requireCount(int value, String name) {
        if (value < 1 || value > MAX_ITEM_COUNT) {
            throw new IllegalArgumentException(name + " must be 1..64");
        }
    }

    private static void requireTimeout(int value, String name) {
        if (value < 1 || value > GenericProcessSpec.MAX_WAIT_TICKS) {
            throw new IllegalArgumentException(
                    name + " is outside the bounded timeout");
        }
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
