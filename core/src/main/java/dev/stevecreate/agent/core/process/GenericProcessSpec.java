package dev.stevecreate.agent.core.process;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable loader-neutral description of one bounded industrial process. */
public record GenericProcessSpec(
        ResourceId recipeId,
        ResourceId recipeType,
        List<ProcessResource> inputs,
        List<ProcessResource> outputs,
        List<ProcessResource> optionalByproducts,
        Set<ResourceId> requiredMachineCapabilities,
        Set<ResourceId> requiredCompletionEvidence,
        int maximumWaitTicks,
        InputConsumptionRequirement inputConsumptionRequirement,
        OutputVerificationRequirement outputVerificationRequirement,
        Map<ResourceId, String> extensionData) {
    public static final int MAX_INPUTS = 32;
    public static final int MAX_OUTPUTS = 32;
    public static final int MAX_OPTIONAL_BYPRODUCTS = 32;
    public static final int MAX_REQUIREMENTS = 64;
    public static final int MAX_EXTENSIONS = 32;
    public static final int MAX_EXTENSION_VALUE_LENGTH = 256;
    public static final int MAX_WAIT_TICKS = 2_400;

    public GenericProcessSpec {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        inputs = copyResources(inputs, "inputs", MAX_INPUTS, true);
        outputs = copyResources(outputs, "outputs", MAX_OUTPUTS, true);
        optionalByproducts = copyResources(
                optionalByproducts,
                "optionalByproducts",
                MAX_OPTIONAL_BYPRODUCTS,
                false);
        rejectOverlap(outputs, optionalByproducts);
        requiredMachineCapabilities = copyRequirements(
                requiredMachineCapabilities, "requiredMachineCapabilities");
        requiredCompletionEvidence = copyRequirements(
                requiredCompletionEvidence, "requiredCompletionEvidence");
        if (maximumWaitTicks < 1 || maximumWaitTicks > MAX_WAIT_TICKS) {
            throw new IllegalArgumentException(
                    "maximumWaitTicks must be between 1 and " + MAX_WAIT_TICKS);
        }
        Objects.requireNonNull(inputConsumptionRequirement, "inputConsumptionRequirement");
        Objects.requireNonNull(outputVerificationRequirement, "outputVerificationRequirement");
        extensionData = copyExtensions(extensionData);
    }

    private static List<ProcessResource> copyResources(
            List<ProcessResource> values,
            String name,
            int maximum,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > maximum) {
            String minimum = requireNonEmpty ? "1" : "0";
            throw new IllegalArgumentException(
                    name + " count must be between " + minimum + " and " + maximum);
        }

        List<ProcessResource> copy = new ArrayList<>(values.size());
        Set<ResourceKey> identities = new HashSet<>();
        for (ProcessResource value : values) {
            ProcessResource resource = Objects.requireNonNull(value, name + " element");
            ResourceKey key = ResourceKey.of(resource);
            if (!identities.add(key)) {
                throw new IllegalArgumentException(
                        name + " contains duplicate resource: " + resource.resourceId());
            }
            copy.add(resource);
        }
        return Collections.unmodifiableList(copy);
    }

    private static void rejectOverlap(
            List<ProcessResource> outputs,
            List<ProcessResource> optionalByproducts) {
        Set<ResourceKey> outputKeys = new HashSet<>();
        for (ProcessResource output : outputs) {
            outputKeys.add(ResourceKey.of(output));
        }
        for (ProcessResource byproduct : optionalByproducts) {
            if (outputKeys.contains(ResourceKey.of(byproduct))) {
                throw new IllegalArgumentException(
                        "optionalByproducts overlaps required output: " + byproduct.resourceId());
            }
        }
    }

    private static Set<ResourceId> copyRequirements(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.size() > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException(
                    name + " count must be between 1 and " + MAX_REQUIREMENTS);
        }
        LinkedHashSet<ResourceId> copy = new LinkedHashSet<>();
        for (ResourceId value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<ResourceId, String> copyExtensions(Map<ResourceId, String> values) {
        Objects.requireNonNull(values, "extensionData");
        if (values.size() > MAX_EXTENSIONS) {
            throw new IllegalArgumentException(
                    "extensionData count exceeds " + MAX_EXTENSIONS);
        }
        Map<ResourceId, String> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, String> entry : values.entrySet()) {
            ResourceId key = Objects.requireNonNull(entry.getKey(), "extensionData key");
            String value = Objects.requireNonNull(
                    entry.getValue(), "extensionData value for " + key);
            if (value.isBlank() || value.length() > MAX_EXTENSION_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "extensionData value for " + key
                                + " must contain 1 to " + MAX_EXTENSION_VALUE_LENGTH + " characters");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }
}
