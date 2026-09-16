package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomic cross-project reservation layer over one immutable global inventory graph snapshot. */
public final class WarehouseReservationSystem {
    private final GlobalInventoryGraph graph;
    private final Map<ResourceId, WarehouseReservationRequest> requests = new LinkedHashMap<>();
    private final Map<ResourceId, WarehouseAllocation> allocations = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private long generation;
    private long lastEpochMillis;

    public WarehouseReservationSystem(GlobalInventoryGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    public synchronized ReservationResult reserveAtomically(
            List<WarehouseReservationRequest> requested,
            long now) {
        requireTime(now);
        Objects.requireNonNull(requested, "requested");
        if (requested.isEmpty() || requested.size() > 512) {
            throw new IllegalArgumentException("warehouse reservation batch is empty or unbounded");
        }
        List<WarehouseReservationRequest> ordered = requested.stream()
                .sorted(Comparator.comparingInt(WarehouseReservationRequest::priority).reversed()
                        .thenComparing(value -> value.requestId().toString()))
                .toList();
        if (ordered.stream().map(WarehouseReservationRequest::requestId).distinct().count()
                != ordered.size()) {
            throw new IllegalArgumentException("duplicate warehouse reservation request");
        }
        if (ordered.stream().anyMatch(value -> !value.ownerId().equals(graph.ownerId())
                || value.expiresAtEpochMillis() <= now
                || requests.containsKey(value.requestId()))) {
            return ReservationResult.failure("WAREHOUSE_RESERVATION_AUTHORITY_INVALID");
        }
        Map<ResourceId, WarehouseAllocation> staged = new LinkedHashMap<>();
        for (WarehouseReservationRequest request : ordered) {
            long remaining = request.quantity();
            for (ResourceId endpointId : request.allowedEndpointIds()) {
                WarehouseEndpointSnapshot endpoint = graph.endpoints().get(endpointId);
                if (endpoint == null || endpoint.expiresAtEpochMillis() <= now) continue;
                long physical = endpoint.contents().getOrDefault(request.resource(), 0L);
                long committed = activeCommitted(endpointId, request.resource(), allocations)
                        + activeCommitted(endpointId, request.resource(), staged);
                long available = Math.max(0, physical - committed);
                long take = Math.min(remaining, available);
                if (take == 0) continue;
                ResourceId allocationId = ResourceId.parse("warehouse:allocation_"
                        + String.format("%06d", generation + staged.size() + 1));
                staged.put(allocationId, new WarehouseAllocation(
                        allocationId, request.requestId(), request.projectId(), endpointId,
                        request.resource(), take, WarehouseReservationStatus.RESERVED,
                        generation + staged.size() + 1, now));
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining != 0) {
                return ReservationResult.failure("WAREHOUSE_MATERIALS_INSUFFICIENT");
            }
        }
        ordered.forEach(value -> requests.put(value.requestId(), value));
        staged.forEach((id, allocation) -> {
            generation = Math.max(generation, allocation.generation());
            allocations.put(id, allocation);
            journal("RESERVED", id, now);
        });
        lastEpochMillis = now;
        return ReservationResult.success(List.copyOf(staged.values()));
    }

    public synchronized WarehouseAllocation advance(
            ResourceId allocationId,
            WarehouseReservationStatus next,
            long now) {
        requireTime(now);
        WarehouseAllocation current = allocations.get(Objects.requireNonNull(
                allocationId, "allocationId"));
        if (current == null) throw new IllegalArgumentException("unknown warehouse allocation");
        if (current.status() == next) return current;
        WarehouseAllocation updated = current.advance(next, ++generation, now);
        allocations.put(allocationId, updated);
        journal(next.name(), allocationId, now);
        lastEpochMillis = now;
        return updated;
    }

    public synchronized int expire(long now) {
        requireTime(now);
        int expired = 0;
        for (WarehouseAllocation allocation : List.copyOf(allocations.values())) {
            WarehouseReservationRequest request = requests.get(allocation.requestId());
            if (allocation.status() == WarehouseReservationStatus.RESERVED
                    && request.expiresAtEpochMillis() <= now) {
                advance(allocation.allocationId(), WarehouseReservationStatus.EXPIRED, now);
                expired++;
            }
        }
        lastEpochMillis = now;
        return expired;
    }

    public synchronized WarehouseBalanceReport balance(ResourceId projectId) {
        List<WarehouseAllocation> rows = allocations.values().stream()
                .filter(value -> value.projectId().equals(projectId)).toList();
        long reserved = rows.stream().filter(value -> value.status()
                        != WarehouseReservationStatus.EXPIRED
                        && value.status() != WarehouseReservationStatus.RELEASED)
                .mapToLong(WarehouseAllocation::quantity).sum();
        long withdrawn = rows.stream().filter(value -> switch (value.status()) {
            case WITHDRAWN, DELIVERED, CONSUMED, RETURN_PENDING, RETURNED -> true;
            default -> false;
        }).mapToLong(WarehouseAllocation::quantity).sum();
        long consumed = rows.stream().filter(value -> value.status()
                        == WarehouseReservationStatus.CONSUMED)
                .mapToLong(WarehouseAllocation::quantity).sum();
        long returned = rows.stream().filter(value -> value.status()
                        == WarehouseReservationStatus.RETURNED)
                .mapToLong(WarehouseAllocation::quantity).sum();
        long outstanding = rows.stream().filter(value -> value.status()
                        == WarehouseReservationStatus.WITHDRAWN
                        || value.status() == WarehouseReservationStatus.DELIVERED
                        || value.status() == WarehouseReservationStatus.RETURN_PENDING)
                .mapToLong(WarehouseAllocation::quantity).sum();
        long unaccounted = Math.max(0, withdrawn - consumed - returned - outstanding);
        int duplicateWithdrawals = duplicates(rows, WarehouseReservationStatus.WITHDRAWN);
        int duplicateReturns = duplicates(rows, WarehouseReservationStatus.RETURNED);
        return new WarehouseBalanceReport(reserved, withdrawn, consumed, returned,
                outstanding, unaccounted,
                unaccounted == 0 && duplicateWithdrawals == 0 && duplicateReturns == 0,
                duplicateWithdrawals, duplicateReturns);
    }

    public synchronized Map<ResourceId, WarehouseAllocation> allocations() {
        return Map.copyOf(allocations);
    }
    public synchronized List<String> journal() { return List.copyOf(journal); }

    public synchronized WarehouseReservationSnapshot snapshot() {
        return new WarehouseReservationSnapshot(graph.topologyFingerprint(), requests, allocations,
                journal, generation, lastEpochMillis);
    }

    public static WarehouseReservationSystem restore(
            GlobalInventoryGraph observedGraph, WarehouseReservationSnapshot snapshot) {
        Objects.requireNonNull(observedGraph, "observedGraph");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!observedGraph.topologyFingerprint().equals(snapshot.graphFingerprint())) {
            throw new IllegalArgumentException("warehouse endpoint snapshot drifted during reload");
        }
        WarehouseReservationSystem system = new WarehouseReservationSystem(observedGraph);
        system.requests.putAll(snapshot.requests());
        system.allocations.putAll(snapshot.allocations());
        system.journal.addAll(snapshot.journal());
        system.generation = snapshot.generation();
        system.lastEpochMillis = snapshot.lastEpochMillis();
        for (WarehouseAllocation allocation : system.allocations.values()) {
            WarehouseReservationRequest request = system.requests.get(allocation.requestId());
            if (request == null || !request.projectId().equals(allocation.projectId())
                    || !request.resource().equals(allocation.resource())
                    || !request.allowedEndpointIds().contains(allocation.endpointId())
                    || !observedGraph.endpoints().containsKey(allocation.endpointId())) {
                throw new IllegalArgumentException("warehouse reload allocation lost its authority");
            }
            if (!validEvents(allocation.status(), system.events(allocation.allocationId()))) {
                throw new IllegalArgumentException("warehouse reload journal differs from allocation state");
            }
        }
        return system;
    }

    private static long activeCommitted(
            ResourceId endpoint,
            WarehouseResourceKey resource,
            Map<ResourceId, WarehouseAllocation> rows) {
        return rows.values().stream().filter(value -> value.endpointId().equals(endpoint)
                        && value.resource().equals(resource)
                        && value.status() != WarehouseReservationStatus.RELEASED
                        && value.status() != WarehouseReservationStatus.EXPIRED
                        && value.status() != WarehouseReservationStatus.RETURNED)
                .mapToLong(WarehouseAllocation::quantity).sum();
    }

    private void journal(String event, ResourceId allocation, long now) {
        if (journal.size() >= 16_384) throw new IllegalStateException("warehouse journal is full");
        journal.add(generation + "|" + now + "|" + event + "|" + allocation);
    }

    private int eventCount(ResourceId allocation, String event) {
        int count = 0;
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length == 4 && parts[2].equals(event)
                    && parts[3].equals(allocation.toString())) count++;
        }
        return count;
    }

    private List<String> events(ResourceId allocation) {
        ArrayList<String> events = new ArrayList<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length == 4 && parts[3].equals(allocation.toString())) events.add(parts[2]);
        }
        return List.copyOf(events);
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

    private int duplicates(
            List<WarehouseAllocation> rows, WarehouseReservationStatus status) {
        return rows.stream().mapToInt(row -> Math.max(0,
                eventCount(row.allocationId(), status.name()) - 1)).sum();
    }

    private void requireTime(long now) {
        if (now < lastEpochMillis) throw new IllegalArgumentException("warehouse time moved backwards");
    }

    public record ReservationResult(
            boolean success,
            String failureCode,
            List<WarehouseAllocation> allocations) {
        public ReservationResult {
            Objects.requireNonNull(failureCode, "failureCode");
            allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));
            if (success == !failureCode.isEmpty() || success == allocations.isEmpty()) {
                throw new IllegalArgumentException("warehouse reservation result is inconsistent");
            }
        }
        static ReservationResult success(List<WarehouseAllocation> values) {
            return new ReservationResult(true, "", values);
        }
        static ReservationResult failure(String code) {
            return new ReservationResult(false, code, List.of());
        }
    }

    public record WarehouseBalanceReport(
            long reserved,
            long withdrawn,
            long consumed,
            long returned,
            long outstanding,
            long unaccounted,
            boolean balanced,
            int duplicateWithdrawals,
            int duplicateReturns) {}
}
