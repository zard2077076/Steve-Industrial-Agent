package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.binding.MachineImplementationCatalog;
import dev.stevecreate.agent.core.binding.MachineImplementationDescriptor;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Immutable implementation snapshot for one exact runtime and reload generation. */
public record RuntimeMachineImplementationCatalogSnapshot(
        MachineImplementationCatalog catalog,
        RuntimeFingerprint runtime,
        String runtimeFingerprint,
        long reloadGeneration) {
    public RuntimeMachineImplementationCatalogSnapshot {
        Objects.requireNonNull(catalog, "catalog");
        runtime = Objects.requireNonNull(runtime, "runtime");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        if (!catalog.runtimeFingerprint().equals(runtimeFingerprint)) {
            throw new IllegalArgumentException(
                    "Implementation catalog fingerprint does not match its runtime snapshot");
        }
        if (catalog.reloadGeneration() != reloadGeneration) {
            throw new IllegalArgumentException(
                    "Implementation catalog reload generation does not match its runtime snapshot");
        }
        ResourceId expectedAdapter = ResourceId.parse(runtime.adapterId());
        for (MachineImplementationDescriptor descriptor : catalog.implementations()) {
            if (!descriptor.adapterId().equals(expectedAdapter)
                    || !descriptor.minecraftVersion().equals(runtime.minecraftVersion())) {
                throw new IllegalArgumentException(
                        "Implementation descriptor attribution does not match its runtime snapshot");
            }
            String runtimeModVersion = runtime.industrialModVersions().get(descriptor.modId());
            if (!descriptor.modVersion().equals(runtimeModVersion)) {
                throw new IllegalArgumentException(
                        "Implementation mod version does not match its runtime snapshot");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 2_048) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
