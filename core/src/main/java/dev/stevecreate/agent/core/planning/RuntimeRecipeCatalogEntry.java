package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** One runtime-discovered recipe before its ingredients are resolved for a planning request. */
public record RuntimeRecipeCatalogEntry(
        ResourceId recipeId,
        ResourceId recipeType,
        List<RecipeIngredient> inputs,
        List<ProcessResource> outputs,
        List<ProcessResource> optionalByproducts,
        Set<ResourceId> requiredMachineCapabilities,
        Set<GenericResourceType> requiredResourceTypes,
        OptionalLong processingTicks,
        RecipeSource source,
        RecipeHeatRequirement heatRequirement,
        List<RecipeIngredient> retainedTools,
        List<ProcessResource> fluidInputs) {
    public static final int MAX_INPUTS = 32;
    public static final int MAX_OUTPUTS = 32;
    public static final int MAX_BYPRODUCTS = 32;
    public static final int MAX_CAPABILITIES = 64;

    private static final Comparator<ProcessResource> RESOURCE_ORDER = Comparator
            .comparing((ProcessResource value) -> value.resourceType().ordinal())
            .thenComparing(value -> value.resourceId().toString());
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    /**
     * The same recipe with nothing borrowed, which is every recipe but a deployer's.
     *
     * <p>Kept so the eight existing construction sites did not have to learn about tools
     * they do not have.</p>
     */
    public RuntimeRecipeCatalogEntry(
            ResourceId recipeId,
            ResourceId recipeType,
            List<RecipeIngredient> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> optionalByproducts,
            Set<ResourceId> requiredMachineCapabilities,
            Set<GenericResourceType> requiredResourceTypes,
            OptionalLong processingTicks,
            RecipeSource source,
            RecipeHeatRequirement heatRequirement) {
        this(recipeId, recipeType, inputs, outputs, optionalByproducts,
                requiredMachineCapabilities, requiredResourceTypes, processingTicks, source,
                heatRequirement, List.of(), List.of());
    }

    /** A recipe that borrows a tool but takes no fluid, which is every deployer recipe. */
    public RuntimeRecipeCatalogEntry(
            ResourceId recipeId,
            ResourceId recipeType,
            List<RecipeIngredient> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> optionalByproducts,
            Set<ResourceId> requiredMachineCapabilities,
            Set<GenericResourceType> requiredResourceTypes,
            OptionalLong processingTicks,
            RecipeSource source,
            RecipeHeatRequirement heatRequirement,
            List<RecipeIngredient> retainedTools) {
        this(recipeId, recipeType, inputs, outputs, optionalByproducts,
                requiredMachineCapabilities, requiredResourceTypes, processingTicks, source,
                heatRequirement, retainedTools, List.of());
    }

    public RuntimeRecipeCatalogEntry {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        inputs = copyInputs(inputs);
        outputs = copyResources(outputs, "outputs", MAX_OUTPUTS, true);
        optionalByproducts = copyResources(
                optionalByproducts, "optionalByproducts", MAX_BYPRODUCTS, false);
        rejectOverlap(outputs, optionalByproducts);
        requiredMachineCapabilities = copyCapabilities(requiredMachineCapabilities);
        requiredResourceTypes = copyResourceTypes(requiredResourceTypes);
        if (!requiredResourceTypes.contains(GenericResourceType.ITEM)) {
            throw new IllegalArgumentException("Runtime item ingredients require ITEM resource support");
        }
        EnumSet<GenericResourceType> usedTypes = EnumSet.noneOf(GenericResourceType.class);
        outputs.forEach(value -> usedTypes.add(value.resourceType()));
        optionalByproducts.forEach(value -> usedTypes.add(value.resourceType()));
        if (!requiredResourceTypes.containsAll(usedTypes)) {
            throw new IllegalArgumentException(
                    "requiredResourceTypes must include every output/byproduct type: " + usedTypes);
        }
        processingTicks = Objects.requireNonNull(processingTicks, "processingTicks");
        if (processingTicks.isPresent()
                && (processingTicks.getAsLong() <= 0
                || processingTicks.getAsLong() > CatalogRecipe.MAX_PROCESSING_TICKS)) {
            throw new IllegalArgumentException("processingTicks must be positive and bounded when present");
        }
        // Borrowed, not consumed. A deployer holding a saw still holds it afterwards, and
        // a plan that charged the player for one saw per operation would be wrong about
        // the cost and wrong about what to return. Kept apart from inputs rather than
        // flagged inside them, so nothing downstream can spend one by accident.
        retainedTools = List.copyOf(Objects.requireNonNull(retainedTools, "retainedTools"));
        if (retainedTools.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("retainedTools is unbounded");
        }
        // Poured in, not fed in. A fluid ingredient never travels as an item stack, so it
        // cannot sit in inputs beside them: RecipeIngredient is item identity by
        // construction, and the reservation ledger counts stacks. What the player is
        // charged for is the bucket, which the material plan works out separately.
        fluidInputs = List.copyOf(Objects.requireNonNull(fluidInputs, "fluidInputs"));
        if (fluidInputs.size() > MAX_INPUTS
                || fluidInputs.stream().anyMatch(value ->
                        value.resourceType() != GenericResourceType.FLUID)) {
            throw new IllegalArgumentException("fluidInputs must be bounded fluid resources");
        }
        source = Objects.requireNonNull(source, "source");
        heatRequirement = Objects.requireNonNull(
                heatRequirement, "heatRequirement");
        if (!heatRequirement.sourceRecipeId().equals(recipeId)
                || !heatRequirement.sourceCapabilityId().equals(recipeType)) {
            throw new IllegalArgumentException(
                    "Heat metadata source must match its runtime recipe and capability");
        }
    }

    /** Compatibility constructor for non-heated runtime adapters and existing fixtures. */
    public RuntimeRecipeCatalogEntry(
            ResourceId recipeId,
            ResourceId recipeType,
            List<RecipeIngredient> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> optionalByproducts,
            Set<ResourceId> requiredMachineCapabilities,
            Set<GenericResourceType> requiredResourceTypes,
            OptionalLong processingTicks,
            RecipeSource source) {
        this(
                recipeId,
                recipeType,
                inputs,
                outputs,
                optionalByproducts,
                requiredMachineCapabilities,
                requiredResourceTypes,
                processingTicks,
                source,
                RecipeHeatRequirement.none(recipeId, recipeType));
    }

    private static List<RecipeIngredient> copyInputs(List<RecipeIngredient> values) {
        Objects.requireNonNull(values, "inputs");
        if (values.isEmpty() || values.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("inputs count violates recipe bounds");
        }
        // The same ingredient twice is four snow blocks, not a malformed recipe.
        //
        // This rejected it, and create:compacting/ice — two by two of one item, which is
        // what compacting is — was the one recipe in the registry that crashed mapping
        // rather than being refused for a reason. A recipe that asks for a thing n times
        // is asking for n of it.
        //
        // Merged on identity without the amount, because canonicalIdentity carries the
        // amount and would call one snow block and two snow blocks different things.
        java.util.LinkedHashMap<String, RecipeIngredient> merged = new java.util.LinkedHashMap<>();
        for (RecipeIngredient value : values) {
            RecipeIngredient ingredient = Objects.requireNonNull(value, "inputs element");
            if (ingredient.kind() == RecipeIngredientKind.UNSUPPORTED_COMPLEX_INGREDIENT) {
                throw new IllegalArgumentException(
                        "Runtime catalog entries cannot admit unsupported complex ingredients: "
                                + ingredient.canonicalIdentity());
            }
            String identity = ingredient.kind() + "|" + ingredient.runtimeCandidates();
            RecipeIngredient existing = merged.get(identity);
            merged.put(identity, existing == null ? ingredient
                    : withAmount(existing, Math.addExact(existing.amount(), ingredient.amount())));
        }
        List<RecipeIngredient> copy = new ArrayList<>(merged.values());
        if (copy.isEmpty() || copy.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("inputs count violates recipe bounds");
        }
        copy.sort(Comparator.comparing(RecipeIngredient::canonicalIdentity));
        return List.copyOf(copy);
    }

    /** The same ingredient asking for more of the same thing. */
    private static RecipeIngredient withAmount(RecipeIngredient ingredient, long amount) {
        if (ingredient instanceof RecipeIngredient.ExactResource exact) {
            return new RecipeIngredient.ExactResource(exact.resourceId(), amount);
        }
        if (ingredient instanceof RecipeIngredient.AnyOfResources any) {
            return new RecipeIngredient.AnyOfResources(any.resources(), amount);
        }
        if (ingredient instanceof RecipeIngredient.TagReference tag) {
            return new RecipeIngredient.TagReference(
                    tag.tagId(), tag.runtimeCandidates(), tag.runtimeFingerprint(), amount);
        }
        throw new IllegalArgumentException(
                "unsupported complex ingredients are rejected before merging");
    }

    private static List<ProcessResource> copyResources(
            List<ProcessResource> values,
            String name,
            int maximum,
            boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > maximum) {
            throw new IllegalArgumentException(name + " count violates recipe bounds");
        }
        List<ProcessResource> copy = new ArrayList<>(values.size());
        Set<ResourceKey> unique = new HashSet<>();
        for (ProcessResource value : values) {
            ProcessResource resource = Objects.requireNonNull(value, name + " element");
            if (!unique.add(ResourceKey.of(resource))) {
                throw new IllegalArgumentException(name + " contains duplicate resource: " + resource.resourceId());
            }
            copy.add(resource);
        }
        copy.sort(RESOURCE_ORDER);
        return List.copyOf(copy);
    }

    private static void rejectOverlap(
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts) {
        Set<ResourceKey> outputKeys = new HashSet<>();
        outputs.forEach(value -> outputKeys.add(ResourceKey.of(value)));
        for (ProcessResource byproduct : byproducts) {
            if (outputKeys.contains(ResourceKey.of(byproduct))) {
                throw new IllegalArgumentException(
                        "optionalByproducts overlaps a required output: " + byproduct.resourceId());
            }
        }
    }

    private static Set<ResourceId> copyCapabilities(Set<ResourceId> values) {
        Objects.requireNonNull(values, "requiredMachineCapabilities");
        if (values.isEmpty() || values.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException("requiredMachineCapabilities count violates recipe bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(ID_ORDER);
        values.forEach(value -> sorted.add(Objects.requireNonNull(
                value, "requiredMachineCapabilities element")));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<GenericResourceType> copyResourceTypes(Set<GenericResourceType> values) {
        Objects.requireNonNull(values, "requiredResourceTypes");
        if (values.isEmpty() || values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException("requiredResourceTypes count is invalid");
        }
        EnumSet<GenericResourceType> copy = EnumSet.noneOf(GenericResourceType.class);
        values.forEach(value -> copy.add(Objects.requireNonNull(value, "requiredResourceTypes element")));
        return Collections.unmodifiableSet(new LinkedHashSet<>(copy));
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }
}
