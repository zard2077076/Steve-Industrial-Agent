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

/** One bounded deterministic loader-neutral recipe description. */
public record CatalogRecipe(
        ResourceId recipeId,
        ResourceId recipeType,
        List<ProcessResource> inputs,
        List<ProcessResource> outputs,
        List<ProcessResource> optionalByproducts,
        Set<ResourceId> requiredMachineCapabilities,
        Set<GenericResourceType> requiredResourceTypes,
        OptionalLong processingTicks,
        RecipeSource source) {
    public static final int MAX_INPUTS = 32;
    public static final int MAX_OUTPUTS = 32;
    public static final int MAX_BYPRODUCTS = 32;
    public static final int MAX_CAPABILITIES = 64;
    public static final long MAX_PROCESSING_TICKS = 10_000_000L;

    private static final Comparator<ProcessResource> RESOURCE_ORDER = Comparator
            .comparing((ProcessResource value) -> value.resourceType().ordinal())
            .thenComparing(value -> value.resourceId().toString());
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public CatalogRecipe {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        inputs = copyResources(inputs, "inputs", MAX_INPUTS, true);
        outputs = copyResources(outputs, "outputs", MAX_OUTPUTS, true);
        optionalByproducts = copyResources(
                optionalByproducts, "optionalByproducts", MAX_BYPRODUCTS, false);
        rejectOverlap(outputs, optionalByproducts);
        requiredMachineCapabilities = copyCapabilities(requiredMachineCapabilities);
        requiredResourceTypes = copyResourceTypes(requiredResourceTypes);
        EnumSet<GenericResourceType> usedTypes = EnumSet.noneOf(GenericResourceType.class);
        inputs.forEach(value -> usedTypes.add(value.resourceType()));
        outputs.forEach(value -> usedTypes.add(value.resourceType()));
        optionalByproducts.forEach(value -> usedTypes.add(value.resourceType()));
        if (!requiredResourceTypes.containsAll(usedTypes)) {
            throw new IllegalArgumentException(
                    "requiredResourceTypes must include every input/output/byproduct type: " + usedTypes);
        }
        processingTicks = Objects.requireNonNull(processingTicks, "processingTicks");
        if (processingTicks.isPresent()
                && (processingTicks.getAsLong() <= 0
                || processingTicks.getAsLong() > MAX_PROCESSING_TICKS)) {
            throw new IllegalArgumentException(
                    "processingTicks must be positive and bounded when present");
        }
        source = Objects.requireNonNull(source, "source");
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
                throw new IllegalArgumentException(
                        name + " contains duplicate resource: " + resource.resourceId());
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
            throw new IllegalArgumentException(
                    "requiredMachineCapabilities count violates recipe bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(ID_ORDER);
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, "requiredMachineCapabilities element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<GenericResourceType> copyResourceTypes(Set<GenericResourceType> values) {
        Objects.requireNonNull(values, "requiredResourceTypes");
        if (values.isEmpty() || values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException("requiredResourceTypes count is invalid");
        }
        EnumSet<GenericResourceType> copy = EnumSet.noneOf(GenericResourceType.class);
        for (GenericResourceType value : values) {
            copy.add(Objects.requireNonNull(value, "requiredResourceTypes element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(copy));
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }
}
