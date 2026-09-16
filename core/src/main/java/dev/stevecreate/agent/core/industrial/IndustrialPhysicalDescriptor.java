package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.layout.MachineGeometryDescriptor;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Additive cross-mod physical contract. It reuses existing capability/port/geometry types and adds
 * only the multiblock and lifecycle semantics that the Phase IV Create-only model did not carry.
 */
public record IndustrialPhysicalDescriptor(
        ResourceId implementationId,
        ResourceId adapterId,
        Optional<MachineGeometryDescriptor> legacyCreateGeometry,
        List<ImplementationPortContract> ports,
        Set<GenericResourceType> energyAndProcessResources,
        Optional<MultiblockStructureContract> multiblock,
        IndustrialLifecycleContract lifecycle,
        String runtimeFingerprint,
        int schemaVersion) {
    public IndustrialPhysicalDescriptor {
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(adapterId, "adapterId");
        legacyCreateGeometry = Objects.requireNonNull(legacyCreateGeometry, "legacyCreateGeometry");
        Objects.requireNonNull(ports, "ports");
        if (ports.isEmpty() || ports.size() > 64) {
            throw new IllegalArgumentException("industrial ports are empty or unbounded");
        }
        ports = ports.stream().sorted(Comparator.comparing(value -> value.portId().toString())).toList();
        if (ports.stream().map(ImplementationPortContract::portId).distinct().count() != ports.size()) {
            throw new IllegalArgumentException("duplicate industrial port identity");
        }
        energyAndProcessResources = Set.copyOf(Objects.requireNonNull(
                energyAndProcessResources, "energyAndProcessResources"));
        if (energyAndProcessResources.isEmpty()) {
            throw new IllegalArgumentException("industrial resource contract is empty");
        }
        multiblock = Objects.requireNonNull(multiblock, "multiblock");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("runtime fingerprint is blank or unbounded");
        }
        if (schemaVersion < 1 || schemaVersion > 1_000) {
            throw new IllegalArgumentException("industrial descriptor schema is invalid");
        }
        if (legacyCreateGeometry.isEmpty() && multiblock.isEmpty()) {
            throw new IllegalArgumentException("industrial descriptor has no physical geometry");
        }
    }
}
