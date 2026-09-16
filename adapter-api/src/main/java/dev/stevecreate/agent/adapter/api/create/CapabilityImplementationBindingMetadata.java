package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, executor-independent component/footprint contract for one capability.
 * The metadata carries no absolute position and grants no placement or execution authority.
 */
public record CapabilityImplementationBindingMetadata(
        ResourceId metadataId,
        CreateCapabilityId capability,
        String minecraftVersion,
        String createVersion,
        Set<Direction6> supportedFacings,
        List<CapabilityComponentRequirement> components,
        List<CapabilityZoneRequirement> zones,
        List<String> limitations,
        boolean serverAuthoritativeReadbackRequired,
        boolean executorProvided,
        boolean placementAuthority) {
    public CapabilityImplementationBindingMetadata {
        Objects.requireNonNull(metadataId, "metadataId");
        Objects.requireNonNull(capability, "capability");
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        createVersion = requireText(createVersion, "createVersion");
        supportedFacings = Set.copyOf(Objects.requireNonNull(supportedFacings, "supportedFacings"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (supportedFacings.isEmpty() || components.isEmpty() || zones.isEmpty()) {
            throw new IllegalArgumentException("Capability binding metadata needs facings, components and zones");
        }
        if (components.stream().noneMatch(value ->
                value.role() == CapabilityComponentRole.PRIMARY_MACHINE && value.required())) {
            throw new IllegalArgumentException("Capability binding metadata needs a primary machine");
        }
        if (!limitations.equals(limitations.stream().distinct().sorted().toList())) {
            throw new IllegalArgumentException("limitations must be unique and canonically sorted");
        }
        if (!serverAuthoritativeReadbackRequired || executorProvided || placementAuthority) {
            throw new IllegalArgumentException(
                    "C-05 through C-10 metadata is read-only and cannot provide an executor or placement authority");
        }
    }

    public ResourceId recipeType() {
        return capability.recipeType();
    }

    public ResourceId implementationCapabilityId() {
        return capability.implementationCapabilityId();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 96) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
