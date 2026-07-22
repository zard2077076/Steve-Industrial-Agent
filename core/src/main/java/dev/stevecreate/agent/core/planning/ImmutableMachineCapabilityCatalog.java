package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable catalog with canonical capability and query ordering. */
public final class ImmutableMachineCapabilityCatalog implements MachineCapabilityCatalog {
    public static final int MAX_CAPABILITIES = 1_024;

    private static final Comparator<MachineCapability> CAPABILITY_ORDER = Comparator
            .comparing(value -> value.capabilityId().toString());

    private final List<MachineCapability> capabilities;
    private final Map<ResourceId, MachineCapability> byId;

    public ImmutableMachineCapabilityCatalog(List<MachineCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty() || capabilities.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException(
                    "Capability catalog count must be between 1 and " + MAX_CAPABILITIES);
        }
        List<MachineCapability> sorted = new ArrayList<>(capabilities.size());
        Map<ResourceId, MachineCapability> unique = new LinkedHashMap<>();
        for (MachineCapability value : capabilities) {
            MachineCapability capability = Objects.requireNonNull(value, "capabilities element");
            if (unique.putIfAbsent(capability.capabilityId(), capability) != null) {
                throw new IllegalArgumentException(
                        "Duplicate capability ID: " + capability.capabilityId());
            }
            sorted.add(capability);
        }
        sorted.sort(CAPABILITY_ORDER);
        this.capabilities = List.copyOf(sorted);
        Map<ResourceId, MachineCapability> ordered = new LinkedHashMap<>();
        this.capabilities.forEach(value -> ordered.put(value.capabilityId(), value));
        this.byId = Collections.unmodifiableMap(ordered);
    }

    @Override
    public List<MachineCapability> capabilities() {
        return capabilities;
    }

    @Override
    public Optional<MachineCapability> find(ResourceId capabilityId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(capabilityId, "capabilityId")));
    }

    @Override
    public List<MachineCapability> capabilitiesForRecipeType(ResourceId recipeType) {
        Objects.requireNonNull(recipeType, "recipeType");
        return capabilities.stream()
                .filter(value -> value.supportedRecipeTypes().contains(recipeType))
                .toList();
    }

    @Override
    public List<MachineCapability> capabilitiesForAdapter(ResourceId adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        return capabilities.stream()
                .filter(value -> value.adapterId().equals(adapterId))
                .toList();
    }

    @Override
    public List<MachineCapability> compatibleCapabilities(CatalogRecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        return capabilities.stream().filter(value -> value.isCompatibleWith(recipe)).toList();
    }
}
