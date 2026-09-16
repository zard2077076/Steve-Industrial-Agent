package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public record FluidNetworkGraph(
        ResourceId graphId,
        String worldIdentity,
        ResourceId dimension,
        Map<ResourceId, FluidNetworkNode> nodes,
        Map<ResourceId, FluidRoute> routes,
        long generation) {
    public static final int MAX_NODES = 1_024;
    public static final int MAX_ROUTES = 4_096;

    public FluidNetworkGraph(
            ResourceId graphId,
            String worldIdentity,
            ResourceId dimension,
            List<FluidNetworkNode> nodes,
            List<FluidRoute> routes,
            long generation) {
        this(graphId, worldIdentity, dimension, indexNodes(nodes), indexRoutes(routes), generation);
    }

    public FluidNetworkGraph {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes"));
        routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
        if (nodes.isEmpty() || nodes.size() > MAX_NODES || routes.isEmpty()
                || routes.size() > MAX_ROUTES || generation < 0) {
            throw new IllegalArgumentException("fluid graph is empty or unbounded");
        }
        for (FluidRoute route : routes.values()) {
            if (!nodes.containsKey(route.fromNodeId()) || !nodes.containsKey(route.toNodeId())) {
                throw new IllegalArgumentException("fluid route references an unknown node");
            }
        }
    }

    private static Map<ResourceId, FluidNetworkNode> indexNodes(List<FluidNetworkNode> values) {
        LinkedHashMap<ResourceId, FluidNetworkNode> result = new LinkedHashMap<>();
        List.copyOf(values).stream().sorted(Comparator.comparing(value -> value.nodeId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.nodeId(), value) != null) {
                        throw new IllegalArgumentException("duplicate fluid node");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private static Map<ResourceId, FluidRoute> indexRoutes(List<FluidRoute> values) {
        LinkedHashMap<ResourceId, FluidRoute> result = new LinkedHashMap<>();
        List.copyOf(values).stream().sorted(Comparator.comparing(value -> value.routeId().toString()))
                .forEach(value -> {
                    if (result.putIfAbsent(value.routeId(), value) != null) {
                        throw new IllegalArgumentException("duplicate fluid route");
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    /** Stable identity for exact tank contents, pumps, valves and physical pipe routes. */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder().append(graphId).append('|')
                .append(worldIdentity).append('|').append(dimension).append('|')
                .append(generation).append('\n');
        nodes.values().stream().sorted(Comparator.comparing(
                        node -> node.nodeId().toString()))
                .forEach(node -> {
                    canonical.append("N|").append(node.nodeId()).append('|').append(node.kind())
                            .append('|').append(node.position()).append('|').append(node.contents())
                            .append('|').append(node.amountMb()).append('|').append(node.capacityMb())
                            .append('|').append(node.inputFaces().stream().map(Enum::name).sorted().toList())
                            .append('|').append(node.outputFaces().stream().map(Enum::name).sorted().toList())
                            .append('|').append(node.pumpRateMbPerTick()).append('|')
                            .append(node.snapshotSha256()).append('\n');
                });
        routes.values().stream().sorted(Comparator.comparing(
                        route -> route.routeId().toString()))
                .forEach(route -> {
                    canonical.append("E|").append(route.routeId()).append('|')
                            .append(route.fromNodeId()).append('|').append(route.toNodeId())
                            .append('|').append(route.fluid()).append('|').append(route.requiredMb())
                            .append('|').append(route.capacityMb()).append('|')
                            .append(route.positions()).append('|')
                            .append(route.directional()).append('|').append(route.valveOpen())
                            .append('|').append(route.collisionFree()).append('|')
                            .append(route.endpointsConnected()).append('|')
                            .append(route.routeSha256()).append('\n');
                });
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Physical tanks/pumps/routes without mutable tank amount, contents or observations. */
    public String topologyFingerprint() {
        StringBuilder canonical = new StringBuilder().append(graphId).append('|')
                .append(worldIdentity).append('|').append(dimension).append('\n');
        nodes.values().stream().sorted(Comparator.comparing(node -> node.nodeId().toString()))
                .forEach(node -> canonical.append("N|").append(node.nodeId()).append('|')
                        .append(node.kind()).append('|').append(node.position()).append('|')
                        .append(node.capacityMb()).append('|')
                        .append(node.inputFaces().stream().map(Enum::name).sorted().toList())
                        .append('|').append(node.outputFaces().stream().map(Enum::name).sorted().toList())
                        .append('|').append(node.pumpRateMbPerTick()).append('\n'));
        routes.values().stream().sorted(Comparator.comparing(route -> route.routeId().toString()))
                .forEach(route -> canonical.append("E|").append(route.routeId()).append('|')
                        .append(route.fromNodeId()).append('|').append(route.toNodeId()).append('|')
                        .append(route.fluid()).append('|').append(route.positions()).append('|')
                        .append(route.requiredMb()).append('|').append(route.capacityMb()).append('|')
                        .append(route.directional()).append('|').append(route.valveOpen()).append('|')
                        .append(route.collisionFree()).append('|').append(route.endpointsConnected())
                        .append('|').append(route.routeSha256()).append('\n'));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
