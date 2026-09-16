package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One purpose- and settlement-aware line in a verified project material plan. */
public record ProjectMaterialLine(
        ResourceId lineId,
        ResourceId resourceId,
        List<ResourceId> acceptedResources,
        long quantity,
        ProjectMaterialPurpose purpose,
        ProjectMaterialDisposition disposition,
        List<BlockPos3i> boundPositions,
        String provenance) {
    public static final int MAX_ACCEPTED_RESOURCES = 4_096;
    public static final int MAX_BOUND_POSITIONS = 262_144;

    public ProjectMaterialLine {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(acceptedResources, "acceptedResources");
        if (acceptedResources.isEmpty() || acceptedResources.size() > MAX_ACCEPTED_RESOURCES) {
            throw new IllegalArgumentException("acceptedResources is empty or unbounded");
        }
        acceptedResources = acceptedResources.stream().distinct()
                .sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (!acceptedResources.contains(resourceId)) {
            throw new IllegalArgumentException("selected resource is absent from accepted resources");
        }
        if (quantity < 1 || quantity > 1_000_000_000L) {
            throw new IllegalArgumentException("project material quantity is outside its bound");
        }
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(boundPositions, "boundPositions");
        if (boundPositions.size() > MAX_BOUND_POSITIONS) {
            throw new IllegalArgumentException("project material positions exceed their bound");
        }
        boundPositions = boundPositions.stream().distinct()
                .sorted(Comparator.comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z))
                .toList();
        if ((purpose == ProjectMaterialPurpose.MACHINE_COMPONENT
                || purpose == ProjectMaterialPurpose.ITEM_ROUTE_COMPONENT
                || purpose == ProjectMaterialPurpose.POWER_COMPONENT)
                && boundPositions.isEmpty()) {
            throw new IllegalArgumentException("installed material requires plan-bound positions");
        }
        if (purpose == ProjectMaterialPurpose.MACHINE_COMPONENT
                && disposition == ProjectMaterialDisposition.INSTALL
                && quantity < boundPositions.size()) {
            throw new IllegalArgumentException("installed material cannot cover its bound positions");
        }
        Objects.requireNonNull(provenance, "provenance");
        if (provenance.isBlank() || provenance.length() > 2_048) {
            throw new IllegalArgumentException("material provenance is blank or unbounded");
        }
    }
}
