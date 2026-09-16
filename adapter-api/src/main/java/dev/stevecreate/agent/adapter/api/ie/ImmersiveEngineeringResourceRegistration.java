package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.core.industrial.IndustrialCapability;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned declaration that a reviewed IE order can be observed by the common
 * resource-recovery layer. This is registration metadata, never mutation authority.
 */
public record ImmersiveEngineeringResourceRegistration(
        ResourceId orderType,
        ResourceId machineType,
        List<ResourceId> reviewedRecipeIds,
        Set<GenericResourceType> resourceTypes,
        Set<IndustrialCapability> observedCapabilities,
        boolean exactEndpointReobservationRequired,
        boolean grantsMutationAuthority) {
    public ImmersiveEngineeringResourceRegistration {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(machineType, "machineType");
        reviewedRecipeIds = boundedIds(reviewedRecipeIds);
        resourceTypes = Set.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
        observedCapabilities = Set.copyOf(Objects.requireNonNull(
                observedCapabilities, "observedCapabilities"));
        if (resourceTypes.isEmpty() || observedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("resource registration is empty");
        }
        if (!exactEndpointReobservationRequired || grantsMutationAuthority) {
            throw new IllegalArgumentException(
                    "resource registration must fail closed and cannot grant mutation authority");
        }
        if (resourceTypes.contains(GenericResourceType.ELECTRICAL_ENERGY)
                != observedCapabilities.contains(IndustrialCapability.ELECTRICAL_POWER)) {
            throw new IllegalArgumentException("electrical resource declaration is inconsistent");
        }
    }

    public boolean supports(ResourceId order, ResourceId recipe) {
        return orderType.equals(Objects.requireNonNull(order, "order"))
                && reviewedRecipeIds.contains(Objects.requireNonNull(recipe, "recipe"));
    }

    private static List<ResourceId> boundedIds(List<ResourceId> source) {
        Objects.requireNonNull(source, "reviewedRecipeIds");
        List<ResourceId> result = source.stream().map(value -> Objects.requireNonNull(
                        value, "reviewedRecipeId"))
                .distinct().sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (result.isEmpty() || result.size() != source.size() || result.size() > 256) {
            throw new IllegalArgumentException("reviewed recipe registration is invalid");
        }
        return result;
    }
}
