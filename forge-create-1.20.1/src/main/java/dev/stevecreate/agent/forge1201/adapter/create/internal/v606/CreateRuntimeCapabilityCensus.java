package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.create.AirflowRequirement;
import dev.stevecreate.agent.adapter.api.create.BasinRequirement;
import dev.stevecreate.agent.adapter.api.create.CapabilityOutput;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeLimitation;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeLimitationCode;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSupport;
import dev.stevecreate.agent.adapter.api.create.CatalystRequirement;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import dev.stevecreate.agent.adapter.api.create.DirectionalFlowRequirement;
import dev.stevecreate.agent.adapter.api.create.FluidIngredientSemantics;
import dev.stevecreate.agent.adapter.api.create.HeatRequirement;
import dev.stevecreate.agent.adapter.api.create.HeldItemRequirement;
import dev.stevecreate.agent.adapter.api.create.MediumRequirement;
import dev.stevecreate.agent.adapter.api.create.MinimumSpeedRequirement;
import dev.stevecreate.agent.adapter.api.create.MultiOutputSemantics;
import dev.stevecreate.agent.adapter.api.create.ProbabilisticOutputSemantics;
import dev.stevecreate.agent.adapter.api.create.ProcessingEnvironmentRequirement;
import dev.stevecreate.agent.adapter.api.create.RotationDirectionRequirement;
import dev.stevecreate.agent.adapter.api.create.RuntimeCapabilityCensusSnapshot;
import dev.stevecreate.agent.adapter.api.create.RuntimeObservationRequirement;
import dev.stevecreate.agent.adapter.api.create.ToolRequirement;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;

/**
 * Read-only v606 census and semantics mapper for C-05 through C-10.
 *
 * <p>This class extends the existing runtime-knowledge boundary. It does not create a second
 * planner, physical plan, task graph, executor, world mutation, or free-text authority.</p>
 */
public final class CreateRuntimeCapabilityCensus {
    private CreateRuntimeCapabilityCensus() {
    }

    public static RuntimeCapabilityCensusSnapshot capture(
            ServerLevel level,
            RuntimeRecipeCatalogSnapshot attribution) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(attribution, "attribution");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("CAPABILITY_CENSUS_WRONG_THREAD");
        }
        String worldIdentity = level.dimension().location().toString();
        if (!worldIdentity.equals(attribution.worldIdentity())) {
            throw new IllegalStateException("CAPABILITY_CENSUS_WORLD_IDENTITY_MISMATCH");
        }

        Map<ResourceId, Long> typeCounts = new LinkedHashMap<>();
        for (CreateCapabilityId capability : CreateCapabilityId.canonicalValues()) {
            typeCounts.put(capability.recipeType(), 0L);
        }
        List<Recipe<?>> allRecipes = level.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                .toList();
        List<CapabilityRecipeSemantics> mapped = new ArrayList<>();
        for (Recipe<?> recipe : allRecipes) {
            ResourceId type = recipeType(recipe);
            Optional<CreateCapabilityId> capability = CreateCapabilityId.fromRecipeType(type);
            if (capability.isEmpty()) {
                continue;
            }
            typeCounts.compute(type, (ignored, count) -> Math.addExact(count, 1L));
            try {
                mapped.add(mapRecipe(
                        level,
                        recipe,
                        capability.orElseThrow(),
                        attribution.runtimeFingerprint()));
            } catch (RuntimeException unexpected) {
                mapped.add(unexpectedFailure(
                        recipe,
                        capability.orElseThrow(),
                        attribution.runtimeFingerprint(),
                        unexpected));
            }
        }
        return new RuntimeCapabilityCensusSnapshot(
                attribution.runtimeFingerprint(),
                worldIdentity,
                attribution.reloadGeneration(),
                allRecipes.size(),
                typeCounts,
                mapped,
                true,
                false);
    }

    private static CapabilityRecipeSemantics mapRecipe(
            ServerLevel level,
            Recipe<?> recipe,
            CreateCapabilityId capability,
            String runtimeFingerprint) {
        List<CapabilityRecipeLimitation> limitations = new ArrayList<>();
        List<RecipeIngredient> itemInputs;
        List<FluidIngredientSemantics> fluidInputs = new ArrayList<>();
        List<ProcessResource> fluidOutputs = new ArrayList<>();
        List<CapabilityOutput> itemOutputs = new ArrayList<>();
        HeatRequirement heat = HeatRequirement.NONE;
        OptionalLong processingTicks = OptionalLong.empty();
        ToolRequirement tool = ToolRequirement.none();
        HeldItemRequirement heldItem = HeldItemRequirement.none();

        if (recipe instanceof AbstractCookingRecipe cooking) {
            itemInputs = CreateRuntimeIngredientMapper.mapAndCountItems(
                    cooking.getIngredients(), runtimeFingerprint);
            mapSingleOutput(
                    cooking.getResultItem(level.registryAccess()), itemOutputs, limitations);
            if (cooking.getCookingTime() > 0) {
                processingTicks = OptionalLong.of(cooking.getCookingTime());
            }
        } else if (recipe instanceof ProcessingRecipe<?> processing) {
            itemInputs = CreateRuntimeIngredientMapper.mapAndCountItems(
                    itemIngredients(processing, capability), runtimeFingerprint);
            for (com.simibubi.create.foundation.fluid.FluidIngredient ingredient
                    : processing.getFluidIngredients()) {
                FluidIngredientSemantics mapped = CreateRuntimeIngredientMapper.mapFluid(
                        ingredient, runtimeFingerprint);
                fluidInputs.add(mapped);
                if (mapped.kind() == FluidIngredientSemantics.Kind.UNSUPPORTED_COMPLEX) {
                    limitations.add(limit(
                            CapabilityRecipeLimitationCode.FLUID_INPUT_PHASE_I_UNSUPPORTED,
                            "fluidInputs[" + (fluidInputs.size() - 1) + "]",
                            mapped.unsupportedDetail().orElse("Unsupported fluid ingredient")));
                }
            }
            mapFluidOutputs(processing.getFluidResults(), fluidOutputs, limitations);
            mapProcessingOutputs(processing.getRollableResults(), itemOutputs, limitations);
            heat = mapHeat(processing.getRequiredHeat(), limitations);
            if (processing.getProcessingDuration() > 0) {
                processingTicks = OptionalLong.of(processing.getProcessingDuration());
            }
            if (capability == CreateCapabilityId.DEPLOYING_PHASE_I) {
                if (processing instanceof ItemApplicationRecipe deploying) {
                    RecipeIngredient held = CreateRuntimeIngredientMapper.mapItem(
                            deploying.getRequiredHeldItem(), runtimeFingerprint, 1);
                    boolean exact = held instanceof RecipeIngredient.ExactResource;
                    if (!exact) {
                        limitations.add(limit(
                                CapabilityRecipeLimitationCode.HELD_ITEM_NOT_EXACT,
                                "heldItem",
                                "Deployer Phase I requires one exact held-item identity"));
                    }
                    heldItem = new HeldItemRequirement(
                            Optional.of(held),
                            !deploying.shouldKeepHeldItem(),
                            true,
                            true);
                    if (deploying.shouldKeepHeldItem()) {
                        tool = new ToolRequirement(
                                Optional.of(held), ToolRequirement.Consumption.RETAINED, true);
                    }
                } else {
                    limitations.add(limit(
                            CapabilityRecipeLimitationCode.RECIPE_CLASS_UNSUPPORTED,
                            "recipeClass",
                            "Deploying recipe is not ItemApplicationRecipe"));
                }
            }
        } else {
            itemInputs = CreateRuntimeIngredientMapper.mapAndCountItems(
                    recipe.getIngredients(), runtimeFingerprint);
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.RECIPE_CLASS_UNSUPPORTED,
                    "recipeClass",
                    "Target recipe class is not a supported cooking or Create ProcessingRecipe"));
        }

        for (int index = 0; index < itemInputs.size(); index++) {
            if (itemInputs.get(index) instanceof RecipeIngredient.UnsupportedComplexIngredient unsupported) {
                limitations.add(limit(
                        CapabilityRecipeLimitationCode.ITEM_INGREDIENT_UNSUPPORTED,
                        "itemInputs[" + index + "]",
                        unsupported.detail()));
            }
        }
        applyCapabilityRestrictions(
                capability,
                itemInputs,
                fluidInputs,
                fluidOutputs,
                itemOutputs,
                heat,
                limitations);

        CapabilityRecipeSupport support = support(limitations);
        ProcessingEnvironmentRequirement environment = environment(
                capability,
                heat,
                itemInputs.size(),
                fluidInputs.size(),
                tool,
                heldItem,
                processingTicks);
        MultiOutputSemantics outputs = new MultiOutputSemantics(itemOutputs, true, true);
        ProbabilisticOutputSemantics probability = new ProbabilisticOutputSemantics(
                itemOutputs.stream().map(CapabilityOutput::probabilityPerMillion).toList(),
                CapabilityOutput.PROBABILITY_DENOMINATOR,
                true);
        return new CapabilityRecipeSemantics(
                resourceId(recipe.getId()),
                capability.recipeType(),
                capability,
                itemInputs,
                fluidInputs,
                fluidOutputs,
                outputs,
                probability,
                environment,
                processingTicks,
                support,
                limitations,
                runtimeFingerprint);
    }

    private static List<Ingredient> itemIngredients(
            ProcessingRecipe<?> recipe,
            CreateCapabilityId capability) {
        if (capability == CreateCapabilityId.DEPLOYING_PHASE_I
                && recipe instanceof ItemApplicationRecipe deploying) {
            return List.of(deploying.getProcessedItem());
        }
        return List.copyOf(recipe.getIngredients());
    }

    private static void mapSingleOutput(
            ItemStack stack,
            List<CapabilityOutput> outputs,
            List<CapabilityRecipeLimitation> limitations) {
        Optional<ProcessResource> mapped = mapItemOutput(stack, "itemOutputs[0]", limitations);
        mapped.ifPresent(resource -> outputs.add(new CapabilityOutput(
                0, resource, CapabilityOutput.PROBABILITY_DENOMINATOR, true)));
    }

    private static void mapProcessingOutputs(
            List<ProcessingOutput> runtimeOutputs,
            List<CapabilityOutput> outputs,
            List<CapabilityRecipeLimitation> limitations) {
        if (runtimeOutputs.isEmpty()) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.OUTPUT_MISSING,
                    "itemOutputs",
                    "Runtime recipe has no item output"));
            return;
        }
        for (int index = 0; index < runtimeOutputs.size(); index++) {
            ProcessingOutput runtimeOutput = runtimeOutputs.get(index);
            int probability = probability(runtimeOutput.getChance(), index, limitations);
            Optional<ProcessResource> mapped = mapItemOutput(
                    runtimeOutput.getStack(), "itemOutputs[" + index + "]", limitations);
            if (index == 0 && (probability == 0 || mapped.isEmpty())) {
                outputs.clear();
                return;
            }
            if (probability > 0 && mapped.isPresent()) {
                outputs.add(new CapabilityOutput(
                        index, mapped.orElseThrow(), probability, index == 0));
            }
        }
    }

    private static int probability(
            float chance,
            int index,
            List<CapabilityRecipeLimitation> limitations) {
        if (!Float.isFinite(chance) || chance <= 0.0F || chance > 1.0F) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.RUNTIME_REQUIREMENT_UNAVAILABLE,
                    "itemOutputs[" + index + "].chance",
                    "Output chance is outside (0, 1]: " + chance));
            return 0;
        }
        int perMillion = Math.round(chance * CapabilityOutput.PROBABILITY_DENOMINATOR);
        if (perMillion < 1) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.RUNTIME_REQUIREMENT_UNAVAILABLE,
                    "itemOutputs[" + index + "].chance",
                    "Output chance is below the exact bounded census precision"));
            return 0;
        }
        return perMillion;
    }

    private static Optional<ProcessResource> mapItemOutput(
            ItemStack stack,
            String field,
            List<CapabilityRecipeLimitation> limitations) {
        if (stack.isEmpty() || stack.getCount() < 1) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.OUTPUT_MISSING,
                    field,
                    "Runtime item output is empty"));
            return Optional.empty();
        }
        if (stack.hasTag()) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.OUTPUT_NBT_UNSUPPORTED,
                    field,
                    "Runtime item output carries NBT that Phase I cannot preserve"));
            return Optional.empty();
        }
        return Optional.of(new ProcessResource(
                resourceId(BuiltInRegistries.ITEM.getKey(stack.getItem())),
                GenericResourceType.ITEM,
                stack.getCount()));
    }

    private static void mapFluidOutputs(
            List<FluidStack> runtimeOutputs,
            List<ProcessResource> outputs,
            List<CapabilityRecipeLimitation> limitations) {
        for (int index = 0; index < runtimeOutputs.size(); index++) {
            FluidStack stack = runtimeOutputs.get(index);
            if (stack.isEmpty() || stack.getAmount() < 1 || stack.hasTag()) {
                limitations.add(limit(
                        CapabilityRecipeLimitationCode.FLUID_OUTPUT_PHASE_I_UNSUPPORTED,
                        "fluidOutputs[" + index + "]",
                        "Fluid output is empty, NBT-bearing or otherwise not exactly representable"));
                continue;
            }
            outputs.add(new ProcessResource(
                    resourceId(BuiltInRegistries.FLUID.getKey(stack.getFluid())),
                    GenericResourceType.FLUID,
                    stack.getAmount()));
        }
    }

    private static HeatRequirement mapHeat(
            HeatCondition runtimeHeat,
            List<CapabilityRecipeLimitation> limitations) {
        if (runtimeHeat == null) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.UNKNOWN_HEAT,
                    "heat",
                    "Runtime recipe did not expose a heat condition"));
            return HeatRequirement.UNSUPPORTED_HEAT_REQUIREMENT;
        }
        return switch (runtimeHeat) {
            case NONE -> HeatRequirement.NONE;
            case HEATED -> HeatRequirement.HEATED;
            case SUPERHEATED -> HeatRequirement.SUPERHEATED;
        };
    }

    private static void applyCapabilityRestrictions(
            CreateCapabilityId capability,
            List<RecipeIngredient> itemInputs,
            List<FluidIngredientSemantics> fluidInputs,
            List<ProcessResource> fluidOutputs,
            List<CapabilityOutput> itemOutputs,
            HeatRequirement heat,
            List<CapabilityRecipeLimitation> limitations) {
        int inputAmount = 0;
        for (RecipeIngredient input : itemInputs) {
            inputAmount = Math.addExact(inputAmount, Math.toIntExact(input.amount()));
        }
        boolean singleInput = inputAmount == 1;
        if ((capability == CreateCapabilityId.CRUSHING
                || capability == CreateCapabilityId.FAN_WASHING
                || capability == CreateCapabilityId.FAN_SMOKING
                || capability == CreateCapabilityId.FAN_HAUNTING
                || capability == CreateCapabilityId.FAN_BLASTING
                || capability == CreateCapabilityId.CUTTING
                || capability == CreateCapabilityId.DEPLOYING_PHASE_I)
                && !singleInput) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.ITEM_INGREDIENT_UNSUPPORTED,
                    "itemInputs",
                    "This Phase-I capability requires exactly one processed input item"));
        }
        if ((capability == CreateCapabilityId.MIXING_PHASE_I
                || capability == CreateCapabilityId.COMPACTING_PHASE_I)
                && inputAmount < 1) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.ITEM_INGREDIENT_UNSUPPORTED,
                    "itemInputs",
                    "Basin Phase I requires at least one counted item input"));
        }
        if (!fluidInputs.isEmpty()) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.FLUID_INPUT_PHASE_I_UNSUPPORTED,
                    "fluidInputs",
                    "C-05 through C-10 Phase I does not execute fluid inputs"));
        }
        if (!fluidOutputs.isEmpty()) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.FLUID_OUTPUT_PHASE_I_UNSUPPORTED,
                    "fluidOutputs",
                    "C-05 through C-10 Phase I does not execute fluid outputs"));
        }
        if (heat == HeatRequirement.SUPERHEATED) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.SUPERHEATED_PHASE_I_UNSUPPORTED,
                    "heat",
                    "SUPERHEATED basin processing is outside Phase I"));
        } else if (heat == HeatRequirement.UNSUPPORTED_HEAT_REQUIREMENT) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.UNKNOWN_HEAT,
                    "heat",
                    "Unknown heat requirements fail closed"));
        }
        if (itemOutputs.isEmpty()) {
            if (limitations.stream().noneMatch(value ->
                    value.code() == CapabilityRecipeLimitationCode.OUTPUT_MISSING
                            || value.code() == CapabilityRecipeLimitationCode.OUTPUT_NBT_UNSUPPORTED)) {
                limitations.add(limit(
                        CapabilityRecipeLimitationCode.OUTPUT_MISSING,
                        "itemOutputs",
                        "No exactly representable item output remains"));
            }
        } else if (!itemOutputs.get(0).guaranteed()) {
            limitations.add(limit(
                    CapabilityRecipeLimitationCode.PRIMARY_OUTPUT_PROBABILISTIC,
                    "itemOutputs[0].chance",
                    "Phase-I production goals require a deterministic primary output"));
        }
    }

    private static ProcessingEnvironmentRequirement environment(
            CreateCapabilityId capability,
            HeatRequirement heat,
            int itemInputCount,
            int fluidInputCount,
            ToolRequirement tool,
            HeldItemRequirement heldItem,
            OptionalLong processingTicks) {
        MediumRequirement medium = switch (capability) {
            case FAN_WASHING -> MediumRequirement.WATER;
            case FAN_SMOKING -> MediumRequirement.FIRE;
            case FAN_HAUNTING -> MediumRequirement.SOUL_FIRE;
            case FAN_BLASTING -> MediumRequirement.LAVA;
            default -> MediumRequirement.NONE;
        };
        boolean fan = medium != MediumRequirement.NONE;
        boolean basin = capability == CreateCapabilityId.MIXING_PHASE_I
                || capability == CreateCapabilityId.COMPACTING_PHASE_I;
        DirectionalFlowRequirement flow = switch (capability) {
            case CRUSHING -> new DirectionalFlowRequirement(
                    true, RotationDirectionRequirement.OPPOSED_INWARD_PAIR, true, 1);
            case FAN_WASHING, FAN_SMOKING, FAN_HAUNTING, FAN_BLASTING ->
                    new DirectionalFlowRequirement(
                            true, RotationDirectionRequirement.ALONG_MACHINE_FACING, true, 1);
            case CUTTING, DEPLOYING_PHASE_I -> new DirectionalFlowRequirement(
                    true, RotationDirectionRequirement.FORWARD_PROCESSING_DIRECTION, true, 1);
            default -> DirectionalFlowRequirement.none();
        };
        EnumSet<RuntimeObservationRequirement.Signal> signals = EnumSet.of(
                RuntimeObservationRequirement.Signal.LIVE_RECIPE,
                RuntimeObservationRequirement.Signal.KINETIC_SPEED,
                RuntimeObservationRequirement.Signal.STRESS_STATE,
                RuntimeObservationRequirement.Signal.MACHINE_STATE,
                RuntimeObservationRequirement.Signal.INPUT_CONSUMED,
                RuntimeObservationRequirement.Signal.OUTPUT_OBSERVED);
        if (capability == CreateCapabilityId.CRUSHING || fan) {
            signals.add(RuntimeObservationRequirement.Signal.ROTATION_DIRECTION);
        }
        if (fan) {
            signals.add(RuntimeObservationRequirement.Signal.AIRFLOW);
            signals.add(RuntimeObservationRequirement.Signal.MEDIUM);
            signals.add(RuntimeObservationRequirement.Signal.OBSTRUCTION);
        }
        if (basin) {
            signals.add(RuntimeObservationRequirement.Signal.BASIN_CONTENTS);
        }
        if (heat != HeatRequirement.NONE) {
            signals.add(RuntimeObservationRequirement.Signal.HEAT);
        }
        if (capability == CreateCapabilityId.DEPLOYING_PHASE_I) {
            signals.add(RuntimeObservationRequirement.Signal.TOOL_OR_HELD_ITEM);
        }
        long baseTicks = processingTicks.orElse(200L);
        int maximumTicks = (int) Math.min(72_000L, Math.max(200L, baseTicks * 4L + 200L));
        return new ProcessingEnvironmentRequirement(
                flow,
                heat,
                fan
                        ? new AirflowRequirement(true, medium, 1, true, true, true)
                        : AirflowRequirement.none(),
                medium,
                tool,
                CatalystRequirement.none(),
                basin
                        ? new BasinRequirement(
                                true,
                                Math.max(1, itemInputCount),
                                fluidInputCount,
                                heat != HeatRequirement.NONE,
                                true)
                        : BasinRequirement.none(),
                heldItem,
                new MinimumSpeedRequirement(OptionalLong.empty(), true),
                new RuntimeObservationRequirement(signals, maximumTicks, true),
                true,
                true);
    }

    private static CapabilityRecipeSemantics unexpectedFailure(
            Recipe<?> recipe,
            CreateCapabilityId capability,
            String runtimeFingerprint,
            RuntimeException unexpected) {
        List<CapabilityRecipeLimitation> limitations = List.of(limit(
                CapabilityRecipeLimitationCode.RUNTIME_REQUIREMENT_UNAVAILABLE,
                "recipe",
                "Unexpected census mapping failure " + unexpected.getClass().getName()
                        + ": " + safeMessage(unexpected)));
        return new CapabilityRecipeSemantics(
                resourceId(recipe.getId()),
                capability.recipeType(),
                capability,
                List.of(),
                List.of(),
                List.of(),
                new MultiOutputSemantics(List.of(), true, true),
                new ProbabilisticOutputSemantics(
                        List.of(), CapabilityOutput.PROBABILITY_DENOMINATOR, true),
                environment(
                        capability,
                        HeatRequirement.UNSUPPORTED_HEAT_REQUIREMENT,
                        0,
                        0,
                        ToolRequirement.none(),
                        HeldItemRequirement.none(),
                        OptionalLong.empty()),
                OptionalLong.empty(),
                CapabilityRecipeSupport.UNSUPPORTED,
                limitations,
                runtimeFingerprint);
    }

    private static CapabilityRecipeSupport support(List<CapabilityRecipeLimitation> limitations) {
        if (limitations.isEmpty()) {
            return CapabilityRecipeSupport.SUPPORTED_PHASE_I;
        }
        boolean hardUnsupported = limitations.stream().anyMatch(value -> switch (value.code()) {
            case RECIPE_CLASS_UNSUPPORTED,
                    ITEM_INGREDIENT_UNSUPPORTED,
                    OUTPUT_NBT_UNSUPPORTED,
                    OUTPUT_MISSING,
                    HELD_ITEM_NOT_EXACT,
                    HELD_ITEM_STATE_UNKNOWN,
                    UNKNOWN_SIDE_EFFECT,
                    RUNTIME_REQUIREMENT_UNAVAILABLE -> true;
            default -> false;
        });
        return hardUnsupported
                ? CapabilityRecipeSupport.UNSUPPORTED
                : CapabilityRecipeSupport.SEMANTICS_ONLY;
    }

    private static CapabilityRecipeLimitation limit(
            CapabilityRecipeLimitationCode code,
            String field,
            String detail) {
        return new CapabilityRecipeLimitation(code, field, bounded(detail), true);
    }

    private static ResourceId recipeType(Recipe<?> recipe) {
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return type == null
                ? ResourceId.parse("steve_industrial:unregistered_recipe_type")
                : resourceId(type);
    }

    private static ResourceId resourceId(ResourceLocation id) {
        if (id == null) {
            throw new IllegalArgumentException("Runtime registry entry has no ID");
        }
        return new ResourceId(id.getNamespace(), id.getPath());
    }

    private static String safeMessage(RuntimeException failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : bounded(value);
    }

    private static String bounded(String value) {
        return value.length() <= 2_048 ? value : value.substring(0, 2_048);
    }
}
