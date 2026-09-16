package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Reservation identities supplied by A integration for one verified capability placement. */
public record CreateCapabilityTaskGraphRequest(
        ResourceId graphId,
        VerifiedPhysicalPlan verifiedPhysicalPlan,
        ResourceId logicalNodeId,
        CapabilityRecipeSemantics semantics,
        CapabilityImplementationBindingMetadata metadata,
        Map<ResourceId, ResourceId> componentPlacementReservationIds,
        Map<ResourceId, ResourceId> componentMaterialReservationIds,
        Map<ResourceId, ResourceId> routePlacementReservationIds,
        ResourceId processMaterialReservationId,
        ResourceId machineInteractionPlacementReservationId) {
    public CreateCapabilityTaskGraphRequest {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(verifiedPhysicalPlan, "verifiedPhysicalPlan");
        Objects.requireNonNull(logicalNodeId, "logicalNodeId");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(metadata, "metadata");
        componentPlacementReservationIds = sorted(componentPlacementReservationIds,
                "componentPlacementReservationIds");
        componentMaterialReservationIds = sorted(componentMaterialReservationIds,
                "componentMaterialReservationIds");
        routePlacementReservationIds = sorted(routePlacementReservationIds,
                "routePlacementReservationIds");
        Objects.requireNonNull(processMaterialReservationId, "processMaterialReservationId");
        Objects.requireNonNull(machineInteractionPlacementReservationId,
                "machineInteractionPlacementReservationId");
    }

    private static Map<ResourceId, ResourceId> sorted(
            Map<ResourceId, ResourceId> values,
            String name) {
        Objects.requireNonNull(values, name);
        TreeMap<ResourceId, ResourceId> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((key, value) -> sorted.put(
                Objects.requireNonNull(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
