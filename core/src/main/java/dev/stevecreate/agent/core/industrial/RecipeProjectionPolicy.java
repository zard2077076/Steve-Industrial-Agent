package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which live recipes may become orderable production steps.
 *
 * <p>Expansion is only as trustworthy as what it is allowed to expand. A registry holds
 * thousands of recipes, and admitting one this project has no handler for produces a
 * plan that reads correctly and cannot be built — the failure mode that has cost this
 * project the most. So admission is a whitelist of capabilities with real executors,
 * and every property the downstream pipeline relies on is checked here rather than
 * discovered later by a wrapper refusing.</p>
 *
 * <p>The decision lives in core, away from any Minecraft type, so the rules are unit
 * testable. The adapter's only job is to describe a recipe faithfully.</p>
 */
public final class RecipeProjectionPolicy {
    /** Recipe types with a verified handler. Anything absent is refused, not guessed. */
    private static final Map<String, String> CAPABILITY_BY_RECIPE_TYPE = Map.ofEntries(
            Map.entry("create:cutting", "create:cutting"),
            Map.entry("create:pressing", "create:pressing"),
            Map.entry("create:milling", "create:milling"),
            Map.entry("create:crushing", "create:crushing"),
            Map.entry("create:mixing", "create:mixing"),
            Map.entry("create:compacting", "create:compacting"),
            Map.entry("create:deploying", "create:deploying"),
            Map.entry("create:splashing", "create:splashing"),
            Map.entry("create:haunting", "create:haunting"),
            Map.entry("minecraft:smoking", "minecraft:smoking"),
            Map.entry("minecraft:blasting", "minecraft:blasting"));

    /**
     * Fuel is deliberately not predicted here.
     *
     * <p>Heat is a per-recipe property, not a per-capability one — Create's mixing and
     * compacting both have heated variants — so a capability whitelist cannot decide it.
     * An earlier attempt listed smoking and blasting, which looked right and still let
     * a quartz chain through to fail at planning. Whether a chain's fuel can be
     * reserved is settled by the planner, and the survey now asks the planner rather
     * than guessing.</p>
     */

    private RecipeProjectionPolicy() {}

    public static Optional<ResourceId> capabilityFor(ResourceId recipeType) {
        Objects.requireNonNull(recipeType, "recipeType");
        return Optional.ofNullable(CAPABILITY_BY_RECIPE_TYPE.get(recipeType.toString()))
                .map(ResourceId::parse);
    }

    /**
     * Projects one described recipe, or explains why it cannot be used.
     *
     * <p>Refusals are typed rather than silent so a survey can report how much of a
     * registry is reachable and why the rest is not — "we support 40 recipes" is not
     * actionable, "310 refused for tag inputs" is.</p>
     */
    public static Projection project(RecipeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Optional<ResourceId> capability = capabilityFor(candidate.recipeType());
        if (capability.isEmpty()) {
            return Projection.refused("RECIPE_TYPE_HAS_NO_HANDLER:" + candidate.recipeType());
        }
        if (candidate.outputVariants() != 1) {
            // A recipe with byproducts needs somewhere for them to go; the composite
            // route model carries exactly one resource per edge.
            return Projection.refused("RECIPE_HAS_MULTIPLE_OUTPUTS:" + candidate.recipeId());
        }
        if (candidate.hasChanceOutputs()) {
            return Projection.refused("RECIPE_OUTPUT_IS_PROBABILISTIC:" + candidate.recipeId());
        }
        if (candidate.hasTagInputs()) {
            // The material ledger reserves exact item identities; a tag cannot be
            // reserved without choosing for the player.
            return Projection.refused("RECIPE_INPUT_IS_A_TAG:" + candidate.recipeId());
        }
        if (candidate.inputs().isEmpty()) {
            return Projection.refused("RECIPE_HAS_NO_ITEM_INPUTS:" + candidate.recipeId());
        }
        if (candidate.outputCount() < 1 || candidate.outputCount() > 64) {
            return Projection.refused("RECIPE_OUTPUT_COUNT_OUT_OF_BOUNDS:" + candidate.recipeId());
        }
        try {
            // One module and the current deployment flow are the right defaults: the
            // module count is how many copies of a machine run side by side, which is a
            // throughput decision, not a property of the recipe.
            //
            // executionVerified is false, and that is the honest value — nothing has run
            // this recipe. It was true, which made the field unfalsifiable: the eleven
            // reviewed entries hard-code true and the projection did too, so
            // EXECUTION_NOT_VERIFIED could never appear and a projected goal would be
            // shown to a player as READY on the strength of having been projected.
            return Projection.admitted(new GoalCatalogEntry(
                    candidate.output(), candidate.recipeId(), capability.orElseThrow(),
                    candidate.inputs(), candidate.outputCount(), 1, true, false,
                    candidate.processingTicks(), candidate.retainedTools(),
                    candidate.fluidInputs()));
        } catch (IllegalArgumentException failure) {
            return Projection.refused("RECIPE_OUTSIDE_GOAL_BOUNDS:" + failure.getMessage());
        }
    }

    /**
     * A loader-neutral description of one live recipe.
     *
     * @param hasTagInputs whether any ingredient is a tag rather than one exact item
     * @param hasChanceOutputs whether any result is probabilistic
     * @param outputVariants how many distinct results the recipe produces
     */
    public record RecipeCandidate(
            ResourceId recipeId,
            ResourceId recipeType,
            ResourceId output,
            long outputCount,
            Map<ResourceId, Long> inputs,
            boolean hasTagInputs,
            boolean hasChanceOutputs,
            int outputVariants,
            java.util.OptionalLong processingTicks,
            Map<ResourceId, Long> retainedTools,
            Map<ResourceId, Long> fluidInputs) {

        /** A candidate that borrows nothing, which is every capability but the deployer's. */
        public RecipeCandidate(
                ResourceId recipeId,
                ResourceId recipeType,
                ResourceId output,
                long outputCount,
                Map<ResourceId, Long> inputs,
                boolean hasTagInputs,
                boolean hasChanceOutputs,
                int outputVariants,
                java.util.OptionalLong processingTicks) {
            this(recipeId, recipeType, output, outputCount, inputs, hasTagInputs,
                    hasChanceOutputs, outputVariants, processingTicks, Map.of(), Map.of());
        }

        /** A candidate that takes no fluid, which is all but eight in this registry. */
        public RecipeCandidate(
                ResourceId recipeId,
                ResourceId recipeType,
                ResourceId output,
                long outputCount,
                Map<ResourceId, Long> inputs,
                boolean hasTagInputs,
                boolean hasChanceOutputs,
                int outputVariants,
                java.util.OptionalLong processingTicks,
                Map<ResourceId, Long> retainedTools) {
            this(recipeId, recipeType, output, outputCount, inputs, hasTagInputs,
                    hasChanceOutputs, outputVariants, processingTicks, retainedTools, Map.of());
        }

        /** A candidate described without its duration; the projection carries none. */
        public RecipeCandidate(
                ResourceId recipeId,
                ResourceId recipeType,
                ResourceId output,
                long outputCount,
                Map<ResourceId, Long> inputs,
                boolean hasTagInputs,
                boolean hasChanceOutputs,
                int outputVariants) {
            this(recipeId, recipeType, output, outputCount, inputs, hasTagInputs,
                    hasChanceOutputs, outputVariants, java.util.OptionalLong.empty());
        }

        public RecipeCandidate {
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(recipeType, "recipeType");
            Objects.requireNonNull(output, "output");
            inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
            if (outputVariants < 0) {
                throw new IllegalArgumentException("output variant count is negative");
            }
        }
    }

    public record Projection(Optional<GoalCatalogEntry> entry, String code) {
        public Projection {
            entry = Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(code, "code");
        }

        public boolean admitted() {
            return entry.isPresent();
        }

        static Projection admitted(GoalCatalogEntry entry) {
            return new Projection(Optional.of(entry), "OK");
        }

        static Projection refused(String code) {
            return new Projection(Optional.empty(), code);
        }
    }
}
