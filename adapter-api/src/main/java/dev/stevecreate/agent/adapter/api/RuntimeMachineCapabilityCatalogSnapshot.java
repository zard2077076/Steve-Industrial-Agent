package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.planning.MachineCapabilityCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable runtime capability catalog; it contains no game objects, layout or implementation IDs. */
public record RuntimeMachineCapabilityCatalogSnapshot(
        MachineCapabilityCatalog catalog,
        List<RuntimeMachineCapabilityDeclaration> declarations,
        RuntimeFingerprint runtime,
        String runtimeFingerprint) {
    public RuntimeMachineCapabilityCatalogSnapshot {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(declarations, "declarations");
        if (declarations.isEmpty() || declarations.size() != catalog.capabilities().size()) {
            throw new IllegalArgumentException(
                    "Runtime declarations must exactly cover the nonempty capability catalog");
        }
        List<RuntimeMachineCapabilityDeclaration> sorted = new ArrayList<>(declarations.size());
        declarations.forEach(value -> sorted.add(
                Objects.requireNonNull(value, "declarations element")));
        sorted.sort(Comparator.comparing(
                value -> value.capability().capabilityId().toString()));
        List<MachineCapability> capabilities = sorted.stream()
                .map(RuntimeMachineCapabilityDeclaration::capability)
                .toList();
        if (!catalog.capabilities().equals(capabilities)) {
            throw new IllegalArgumentException(
                    "Capability catalog order/content does not match runtime declarations");
        }
        declarations = List.copyOf(sorted);
        runtime = Objects.requireNonNull(runtime, "runtime");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint", 2_048);
        for (RuntimeMachineCapabilityDeclaration declaration : declarations) {
            if (!declaration.runtime().equals(runtime)
                    || !declaration.runtimeFingerprint().equals(runtimeFingerprint)) {
                throw new IllegalArgumentException(
                        "Runtime capability declaration attribution does not match its snapshot");
            }
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
