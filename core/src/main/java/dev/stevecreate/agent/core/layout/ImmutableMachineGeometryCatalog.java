package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Exact runtime-fingerprinted geometry catalog. */
public final class ImmutableMachineGeometryCatalog {
    private final Map<ResourceId, MachineGeometryDescriptor> descriptors;
    private final String runtimeFingerprint;

    public ImmutableMachineGeometryCatalog(
            List<MachineGeometryDescriptor> descriptors,
            String runtimeFingerprint) {
        Objects.requireNonNull(descriptors, "descriptors");
        TreeMap<ResourceId, MachineGeometryDescriptor> sorted = new TreeMap<>(
                java.util.Comparator.comparing(ResourceId::toString));
        for (MachineGeometryDescriptor descriptor : descriptors) {
            MachineGeometryDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
            if (sorted.putIfAbsent(value.implementationId(), value) != null) {
                throw new IllegalArgumentException("duplicate geometry " + value.implementationId());
            }
        }
        this.descriptors = Map.copyOf(new LinkedHashMap<>(sorted));
        this.runtimeFingerprint = Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank()) throw new IllegalArgumentException("runtimeFingerprint is blank");
    }

    public Optional<MachineGeometryDescriptor> find(ResourceId implementationId) {
        return Optional.ofNullable(descriptors.get(Objects.requireNonNull(implementationId, "implementationId")));
    }

    public Map<ResourceId, MachineGeometryDescriptor> descriptors() { return descriptors; }
    public String runtimeFingerprint() { return runtimeFingerprint; }
}
