package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reference-counted verified infrastructure lease for later multi-line construction. */
public record SharedInfrastructureReservation(
        ResourceId reservationId,
        ResourceId verifiedPhysicalPlanId,
        ResourceId infrastructureId,
        Set<ResourceId> owningSessionIds,
        List<BlockPos3i> positions,
        ReservationStatus status,
        long generation,
        long updatedTick) {
    public SharedInfrastructureReservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(infrastructureId, "infrastructureId");
        owningSessionIds = ConstructionContractValues.sortedIds(
                owningSessionIds, "owningSessionIds", status == ReservationStatus.ACTIVE);
        Objects.requireNonNull(positions, "positions");
        if (positions.isEmpty() || positions.size() > ConstructionContractValues.MAX_IDS) {
            throw new IllegalArgumentException("positions count must be between 1 and "
                    + ConstructionContractValues.MAX_IDS);
        }
        LinkedHashSet<BlockPos3i> unique = new LinkedHashSet<>();
        for (BlockPos3i position : positions) {
            if (!unique.add(Objects.requireNonNull(position, "positions element"))) {
                throw new IllegalArgumentException("duplicate shared infrastructure position " + position);
            }
        }
        List<BlockPos3i> ordered = new ArrayList<>(unique);
        ordered.sort(Comparator.comparingInt(BlockPos3i::x)
                .thenComparingInt(BlockPos3i::y)
                .thenComparingInt(BlockPos3i::z));
        positions = Collections.unmodifiableList(ordered);
        Objects.requireNonNull(status, "status");
        if ((status == ReservationStatus.RELEASED || status == ReservationStatus.EXPIRED)
                && !owningSessionIds.isEmpty()) {
            throw new IllegalArgumentException("Released shared infrastructure cannot retain owners");
        }
        if (generation < 0 || updatedTick < 0) {
            throw new IllegalArgumentException("Shared reservation generation/tick must be non-negative");
        }
    }

    public int referenceCount() {
        return owningSessionIds.size();
    }
}
