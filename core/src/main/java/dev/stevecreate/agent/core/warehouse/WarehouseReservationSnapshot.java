package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable warehouse commitments, reusable only with the exact observed inventory graph. */
public record WarehouseReservationSnapshot(
        String graphFingerprint,
        Map<ResourceId, WarehouseReservationRequest> requests,
        Map<ResourceId, WarehouseAllocation> allocations,
        List<String> journal,
        long generation,
        long lastEpochMillis) {
    public WarehouseReservationSnapshot {
        if (graphFingerprint == null || !graphFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("warehouse graph fingerprint is invalid");
        }
        requests = Map.copyOf(Objects.requireNonNull(requests, "requests"));
        allocations = Map.copyOf(Objects.requireNonNull(allocations, "allocations"));
        journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
        if (requests.size() > 512 || allocations.size() > 4_096 || journal.size() > 16_384
                || generation < 0 || lastEpochMillis < 0) {
            throw new IllegalArgumentException("warehouse reservation snapshot is invalid");
        }
        for (Map.Entry<ResourceId, WarehouseReservationRequest> entry : requests.entrySet()) {
            ResourceId id = entry.getKey();
            WarehouseReservationRequest request = entry.getValue();
            if (!id.equals(request.requestId())) {
                throw new IllegalArgumentException("warehouse request key differs from identity");
            }
        }
        for (Map.Entry<ResourceId, WarehouseAllocation> entry : allocations.entrySet()) {
            ResourceId id = entry.getKey();
            WarehouseAllocation allocation = entry.getValue();
            if (!id.equals(allocation.allocationId()) || allocation.generation() > generation
                    || allocation.updatedAtEpochMillis() > lastEpochMillis) {
                throw new IllegalArgumentException("warehouse allocation snapshot is invalid");
            }
            WarehouseReservationRequest request = requests.get(allocation.requestId());
            if (request == null || !request.projectId().equals(allocation.projectId())
                    || !request.resource().equals(allocation.resource())
                    || !request.allowedEndpointIds().contains(allocation.endpointId())) {
                throw new IllegalArgumentException(
                        "warehouse allocation lost its request authority");
            }
            if (!validEvents(allocation.status(), events(journal, id))) {
                throw new IllegalArgumentException(
                        "warehouse snapshot journal differs from allocation state");
            }
        }
        rejectUnknownAllocations(journal, allocations);
    }

    private static boolean validEvents(
            WarehouseReservationStatus status, List<String> observed) {
        return switch (status) {
            case RESERVED -> observed.equals(List.of("RESERVED"));
            case WITHDRAWN -> observed.equals(List.of("RESERVED", "WITHDRAWN"));
            case DELIVERED -> observed.equals(List.of("RESERVED", "WITHDRAWN", "DELIVERED"));
            case CONSUMED -> observed.equals(
                    List.of("RESERVED", "WITHDRAWN", "DELIVERED", "CONSUMED"));
            case RETURN_PENDING -> observed.equals(
                    List.of("RESERVED", "WITHDRAWN", "RETURN_PENDING"))
                    || observed.equals(List.of(
                            "RESERVED", "WITHDRAWN", "DELIVERED", "RETURN_PENDING"));
            case RETURNED -> observed.equals(
                    List.of("RESERVED", "WITHDRAWN", "RETURN_PENDING", "RETURNED"))
                    || observed.equals(List.of("RESERVED", "WITHDRAWN", "DELIVERED",
                            "RETURN_PENDING", "RETURNED"));
            case RELEASED -> observed.equals(List.of("RESERVED", "RELEASED"));
            case EXPIRED -> observed.equals(List.of("RESERVED", "EXPIRED"));
        };
    }

    private static List<String> events(List<String> journal, ResourceId id) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4) {
                throw new IllegalArgumentException("warehouse journal row is malformed");
            }
            if (parts[3].equals(id.toString())) result.add(parts[2]);
        }
        return List.copyOf(result);
    }

    private static void rejectUnknownAllocations(
            List<String> journal, Map<ResourceId, WarehouseAllocation> allocations) {
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4 || !allocations.containsKey(ResourceId.parse(parts[3]))) {
                throw new IllegalArgumentException("warehouse journal references an unknown allocation");
            }
        }
    }
}
