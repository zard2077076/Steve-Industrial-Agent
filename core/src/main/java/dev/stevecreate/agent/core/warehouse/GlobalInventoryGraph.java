package dev.stevecreate.agent.core.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Logical warehouse formed only from player-bound, server-verified existing endpoints. */
public final class GlobalInventoryGraph {
    public static final int MAX_ENDPOINTS = 256;
    public static final int MAX_EDGES = 1_024;
    private final ResourceId warehouseId;
    private final ResourceId ownerId;
    private final String worldIdentity;
    private final ResourceId dimension;
    private final Map<ResourceId, WarehouseEndpointSnapshot> endpoints;
    private final Map<ResourceId, WarehouseLogisticsEdge> edges;
    private final long generation;

    public GlobalInventoryGraph(
            ResourceId warehouseId,
            ResourceId ownerId,
            String worldIdentity,
            ResourceId dimension,
            List<WarehouseEndpointSnapshot> endpoints,
            List<WarehouseLogisticsEdge> edges,
            long generation) {
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.endpoints = indexEndpoints(endpoints);
        this.edges = indexEdges(edges);
        if (generation < 0) throw new IllegalArgumentException("warehouse generation is negative");
        this.generation = generation;
        validate();
    }

    public Map<WarehouseResourceKey, Long> totalContents() {
        TreeMap<WarehouseResourceKey, Long> result = new TreeMap<>();
        endpoints.values().forEach(endpoint -> endpoint.contents().forEach(
                (resource, quantity) -> result.merge(resource, quantity, Math::addExact)));
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    public List<WarehouseEndpointSnapshot> endpointsFor(WarehouseResourceKey resource) {
        return endpoints.values().stream().filter(value -> value.contents().containsKey(resource))
                .sorted(Comparator.comparing(value -> value.endpointId().toString())).toList();
    }

    public boolean reachable(
            ResourceId fromEndpoint,
            ResourceId toEndpoint,
            WarehouseResourceKey resource) {
        Objects.requireNonNull(resource, "resource");
        if (fromEndpoint.equals(toEndpoint)) return endpoints.containsKey(fromEndpoint);
        java.util.ArrayDeque<ResourceId> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<ResourceId> visited = new java.util.HashSet<>();
        queue.add(fromEndpoint);
        while (!queue.isEmpty() && visited.size() <= MAX_ENDPOINTS) {
            ResourceId current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (WarehouseLogisticsEdge edge : edges.values()) {
                if (!edge.fromEndpointId().equals(current)
                        || !edge.supportedResourceTypes().contains(resource.resourceType())) continue;
                if (edge.toEndpointId().equals(toEndpoint)) return true;
                queue.addLast(edge.toEndpointId());
            }
        }
        return false;
    }

    public ResourceId warehouseId() { return warehouseId; }
    public ResourceId ownerId() { return ownerId; }
    public String worldIdentity() { return worldIdentity; }
    public ResourceId dimension() { return dimension; }
    public Map<ResourceId, WarehouseEndpointSnapshot> endpoints() { return endpoints; }
    public Map<ResourceId, WarehouseLogisticsEdge> edges() { return edges; }
    public long generation() { return generation; }

    /** Stable identity for endpoint contents, permissions and every physical logistics edge. */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder().append(warehouseId).append('|')
                .append(ownerId).append('|').append(worldIdentity).append('|')
                .append(dimension).append('|').append(generation).append('\n');
        endpoints.values().forEach(endpoint -> {
            canonical.append("N|").append(endpoint.endpointId()).append('|')
                    .append(endpoint.position()).append('|').append(endpoint.blockEntityType())
                    .append('|').append(endpoint.endpointType()).append('|').append(endpoint.accessFace())
                    .append('|').append(endpoint.totalCapacity()).append('|')
                    .append(endpoint.snapshotSha256()).append('|').append(endpoint.generation())
                    .append('|').append(endpoint.expiresAtEpochMillis()).append('\n');
            endpoint.contents().forEach((resource, quantity) -> canonical.append("R|")
                    .append(resource.resourceType()).append('|').append(resource.resourceId())
                    .append('|').append(resource.componentSha256()).append('|')
                    .append(quantity).append('\n'));
        });
        edges.values().forEach(edge -> canonical.append("E|").append(edge.edgeId()).append('|')
                .append(edge.fromEndpointId()).append('|').append(edge.toEndpointId()).append('|')
                .append(edge.supportedResourceTypes().stream().map(Enum::name).sorted().toList())
                .append('|').append(edge.path()).append('|').append(edge.pathSha256())
                .append('|').append(edge.generation()).append('|').append(edge.capacityPerTick())
                .append('|').append(edge.collisionFree()).append('|').append(edge.serverVerified())
                .append('\n'));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Physical endpoint/route identity without mutable contents, expiry or generations. */
    public String topologyFingerprint() {
        StringBuilder canonical = new StringBuilder().append(warehouseId).append('|')
                .append(ownerId).append('|').append(worldIdentity).append('|')
                .append(dimension).append('\n');
        endpoints.values().forEach(endpoint -> canonical.append("N|")
                .append(endpoint.endpointId()).append('|').append(endpoint.position()).append('|')
                .append(endpoint.accessFace()).append('|').append(endpoint.blockEntityType())
                .append('|').append(endpoint.endpointType()).append('|')
                .append(endpoint.totalCapacity()).append('|').append(endpoint.permissionVerified())
                .append('\n'));
        edges.values().forEach(edge -> canonical.append("E|").append(edge.edgeId()).append('|')
                .append(edge.fromEndpointId()).append('|').append(edge.toEndpointId()).append('|')
                .append(edge.supportedResourceTypes().stream().map(Enum::name).sorted().toList())
                .append('|').append(edge.path()).append('|').append(edge.capacityPerTick())
                .append('|').append(edge.pathSha256()).append('|').append(edge.collisionFree())
                .append('|').append(edge.serverVerified()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String sha256(String canonical) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Map<ResourceId, WarehouseEndpointSnapshot> indexEndpoints(
            List<WarehouseEndpointSnapshot> values) {
        Objects.requireNonNull(values, "endpoints");
        if (values.isEmpty() || values.size() > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("warehouse endpoints are empty or unbounded");
        }
        LinkedHashMap<ResourceId, WarehouseEndpointSnapshot> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(value -> value.endpointId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.endpointId(), value) != null) {
                        throw new IllegalArgumentException("duplicate warehouse endpoint");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private Map<ResourceId, WarehouseLogisticsEdge> indexEdges(List<WarehouseLogisticsEdge> values) {
        Objects.requireNonNull(values, "edges");
        if (values.size() > MAX_EDGES) {
            throw new IllegalArgumentException("warehouse edges exceed their bound");
        }
        LinkedHashMap<ResourceId, WarehouseLogisticsEdge> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(value -> value.edgeId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.edgeId(), value) != null) {
                        throw new IllegalArgumentException("duplicate warehouse edge");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private void validate() {
        for (WarehouseEndpointSnapshot endpoint : endpoints.values()) {
            if (!endpoint.warehouseId().equals(warehouseId)
                    || !endpoint.ownerId().equals(ownerId)
                    || !endpoint.worldIdentity().equals(worldIdentity)
                    || !endpoint.dimension().equals(dimension)) {
                throw new IllegalArgumentException("warehouse endpoint authority differs from graph");
            }
        }
        for (WarehouseLogisticsEdge edge : edges.values()) {
            if (!endpoints.containsKey(edge.fromEndpointId())
                    || !endpoints.containsKey(edge.toEndpointId())) {
                throw new IllegalArgumentException("warehouse edge references an unbound endpoint");
            }
        }
    }
}
