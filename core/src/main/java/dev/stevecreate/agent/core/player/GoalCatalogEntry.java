package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** One explicitly reviewed player goal; catalog membership does not itself grant execution. */
public record GoalCatalogEntry(
        ResourceId target,
        ResourceId recipe,
        ResourceId capability,
        Map<ResourceId, Long> inputsPerBatch,
        long outputPerBatch,
        int physicalModuleCount,
        boolean preparedSiteRequired,
        boolean executionVerified,
        java.util.OptionalLong processingTicks,
        Map<ResourceId, Long> retainedToolsPerBatch,
        Map<ResourceId, Long> fluidInputsPerBatch) {

    /**
     * An entry whose recipe duration is unknown.
     *
     * <p>The reviewed entries are written by hand and have no duration to state; what
     * quantity they support is a measured fact recorded beside them. Only a projected
     * entry carries a duration, because only the registry knows one.</p>
     */
    public GoalCatalogEntry(
            ResourceId target,
            ResourceId recipe,
            ResourceId capability,
            Map<ResourceId, Long> inputsPerBatch,
            long outputPerBatch,
            int physicalModuleCount,
            boolean preparedSiteRequired,
            boolean executionVerified) {
        this(target, recipe, capability, inputsPerBatch, outputPerBatch, physicalModuleCount,
                preparedSiteRequired, executionVerified, java.util.OptionalLong.empty(),
                Map.of(), Map.of());
    }

    /** A projected entry that borrows nothing, which is every capability but the deployer's. */
    public GoalCatalogEntry(
            ResourceId target,
            ResourceId recipe,
            ResourceId capability,
            Map<ResourceId, Long> inputsPerBatch,
            long outputPerBatch,
            int physicalModuleCount,
            boolean preparedSiteRequired,
            boolean executionVerified,
            java.util.OptionalLong processingTicks) {
        this(target, recipe, capability, inputsPerBatch, outputPerBatch, physicalModuleCount,
                preparedSiteRequired, executionVerified, processingTicks, Map.of(), Map.of());
    }

    /** A projected entry that takes no fluid, which is all but eight in this registry. */
    public GoalCatalogEntry(
            ResourceId target,
            ResourceId recipe,
            ResourceId capability,
            Map<ResourceId, Long> inputsPerBatch,
            long outputPerBatch,
            int physicalModuleCount,
            boolean preparedSiteRequired,
            boolean executionVerified,
            java.util.OptionalLong processingTicks,
            Map<ResourceId, Long> retainedToolsPerBatch) {
        this(target, recipe, capability, inputsPerBatch, outputPerBatch, physicalModuleCount,
                preparedSiteRequired, executionVerified, processingTicks, retainedToolsPerBatch,
                Map.of());
    }
    public static final int MAX_INPUT_TYPES = 9;
    public static final long MAX_STACK_BUDGET = 64;
    public static final int MAX_FLUID_INPUT_TYPES = 4;
    public static final long MAX_FLUID_BUDGET_MILLIBUCKETS = 64_000;

    public GoalCatalogEntry {
        // Borrowed for the batch, not spent on it. A deployer holding a saw still holds
        // it when the batch is done, so this must never be merged into inputsPerBatch:
        // the chain arithmetic multiplies inputs by batch count, and one saw does not
        // become six because the order asks for six planks.
        retainedToolsPerBatch = Map.copyOf(
                Objects.requireNonNull(retainedToolsPerBatch, "retainedToolsPerBatch"));
        // In millibuckets, and kept out of inputsPerBatch for the same reason tools are:
        // the chain arithmetic and the reservation ledger both count item stacks, and a
        // thousand millibuckets of water is not a thousand of anything the player holds.
        Objects.requireNonNull(fluidInputsPerBatch, "fluidInputsPerBatch");
        LinkedHashMap<ResourceId, Long> orderedFluids = new LinkedHashMap<>();
        fluidInputsPerBatch.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> orderedFluids.put(
                        Objects.requireNonNull(entry.getKey(), "fluid input resource"),
                        Objects.requireNonNull(entry.getValue(), "fluid input amount")));
        fluidInputsPerBatch = Map.copyOf(orderedFluids);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(inputsPerBatch, "inputsPerBatch");
        LinkedHashMap<ResourceId, Long> ordered = new LinkedHashMap<>();
        inputsPerBatch.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> ordered.put(
                        Objects.requireNonNull(entry.getKey(), "input resource"),
                        Objects.requireNonNull(entry.getValue(), "input amount")));
        inputsPerBatch = Map.copyOf(ordered);
        if (inputsPerBatch.isEmpty() || inputsPerBatch.size() > MAX_INPUT_TYPES
                || inputsPerBatch.values().stream().anyMatch(value -> value < 1 || value > MAX_STACK_BUDGET)
                || fluidInputsPerBatch.size() > MAX_FLUID_INPUT_TYPES
                || fluidInputsPerBatch.values().stream().anyMatch(value ->
                        value < 1 || value > MAX_FLUID_BUDGET_MILLIBUCKETS)
                || outputPerBatch < 1 || outputPerBatch > MAX_STACK_BUDGET
                || physicalModuleCount < 1 || physicalModuleCount > 9) {
            throw new IllegalArgumentException("goal exceeds the bounded player project contract");
        }
    }

    public MaterialEstimate estimate(long requestedOutput) {
        if (requestedOutput < 1 || requestedOutput > MAX_STACK_BUDGET) {
            throw new IllegalArgumentException("requested output must be between 1 and 64");
        }
        long batches = Math.addExact(requestedOutput, outputPerBatch - 1) / outputPerBatch;
        LinkedHashMap<ResourceId, Long> required = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, Long> entry : inputsPerBatch.entrySet()) {
            long amount = Math.multiplyExact(entry.getValue(), batches);
            if (amount > MAX_STACK_BUDGET) {
                throw new IllegalArgumentException("material estimate exceeds one-stack project budget");
            }
            required.put(entry.getKey(), amount);
        }
        return new MaterialEstimate(requestedOutput, batches,
                Math.multiplyExact(outputPerBatch, batches), required);
    }

    public record MaterialEstimate(
            long requestedOutput,
            long batches,
            long plannedOutput,
            Map<ResourceId, Long> requiredInputs) {
        public MaterialEstimate {
            requiredInputs = Map.copyOf(Objects.requireNonNull(requiredInputs, "requiredInputs"));
        }
    }
}
