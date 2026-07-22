package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MachineCapability;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Runtime-attributed capability metadata around the unchanged loader-neutral core declaration. */
public record RuntimeMachineCapabilityDeclaration(
        MachineCapability capability,
        boolean physicalExecutionAvailable,
        Set<ResourceId> physicallyVerifiedRecipeIds,
        RuntimeFingerprint runtime,
        String runtimeFingerprint,
        RuntimeMachineCapabilitySource source) {
    public static final int MAX_VERIFIED_RECIPES = 64;

    public RuntimeMachineCapabilityDeclaration {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(physicallyVerifiedRecipeIds, "physicallyVerifiedRecipeIds");
        if (physicallyVerifiedRecipeIds.size() > MAX_VERIFIED_RECIPES) {
            throw new IllegalArgumentException("physicallyVerifiedRecipeIds exceeds its bound");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        physicallyVerifiedRecipeIds.forEach(value -> sorted.add(
                Objects.requireNonNull(value, "physicallyVerifiedRecipeIds element")));
        physicallyVerifiedRecipeIds = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
        if (physicalExecutionAvailable != !physicallyVerifiedRecipeIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Physical execution availability must match nonempty proven recipe evidence");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (!capability.adapterId().equals(ResourceId.parse(runtime.adapterId()))) {
            throw new IllegalArgumentException(
                    "Capability Adapter ID does not match runtime attribution");
        }
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint", 2_048);
        if (!capability.versionLimits().acceptedRuntimeFingerprints().contains(runtimeFingerprint)) {
            throw new IllegalArgumentException(
                    "Capability version limits do not accept the declared runtime fingerprint");
        }
        source = Objects.requireNonNull(source, "source");
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
