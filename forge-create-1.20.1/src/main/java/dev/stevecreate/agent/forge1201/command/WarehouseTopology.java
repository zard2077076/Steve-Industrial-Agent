package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.warehouse.WarehouseLogisticsEdge;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A warehouse graph that knows what can reach what, not merely what exists.
 *
 * <p>{@code GlobalInventoryGraph} has always been built with an empty edge list, and its
 * {@code reachable} check walks edges — so nothing was reachable from anything, and a
 * warehouse spread across two rooms was indistinguishable from one in a single room.
 *
 * <p>An edge is claimed only where an unobstructed straight run of air joins two
 * containers. That is deliberately conservative and deliberately not a transport
 * mechanism: what physically moves goods between endpoints is the open question C5 is
 * blocked on, and inventing an answer here would bake a guess into the topology. A clear
 * corridor is something that can be checked against the world and that no courier could
 * be stopped by; anything less clear is refused rather than assumed passable.
 *
 * <p>Read-only, like the rest of the family. Knowing two chests are joined is not
 * permission to move anything between them.
 */
public final class WarehouseTopology {
    /** Beyond this two containers are not one warehouse. */
    public static final int MAX_EDGE_LENGTH = 32;

    private WarehouseTopology() {}

    /**
     * Builds the graph for the containers near {@code centre}.
     *
     * @param generation advances on every rebuild, so a caller can tell a refreshed graph
     *     from the one it already had
     */
    public static GlobalInventoryGraph capture(
            ServerLevel level,
            BlockPos centre,
            int radius,
            ResourceId warehouseId,
            ResourceId ownerId,
            String worldIdentity,
            long generation) {
        Objects.requireNonNull(warehouseId, "warehouseId");
        List<WarehouseDiscovery.Candidate> found =
                WarehouseDiscovery.candidates(level, centre, radius, List.of());
        return graph(level, found, warehouseId, ownerId, worldIdentity, generation);
    }

    /**
     * Rebuilds only the endpoints frozen by an earlier binding.
     *
     * <p>An empty endpoint remains present, while a removed/replaced container fails
     * closed. This is deliberately separate from discovery so reload cannot silently
     * acquire a nearby container the player never selected.</p>
     */
    public static GlobalInventoryGraph recaptureBound(
            ServerLevel level, GlobalInventoryGraph binding, long generation) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(binding, "binding");
        List<WarehouseDiscovery.Candidate> found = new ArrayList<>();
        for (WarehouseEndpointSnapshot expected : binding.endpoints().values()) {
            BlockPos position = new BlockPos(expected.position().x(), expected.position().y(),
                    expected.position().z());
            WarehouseDiscovery.Candidate actual = WarehouseDiscovery.boundCandidate(level, position);
            if (actual == null || !actual.blockEntityType().equals(expected.blockEntityType())) {
                throw new IllegalStateException("bound warehouse endpoint changed at " + position);
            }
            found.add(actual);
        }
        return graph(level, found, binding.warehouseId(), binding.ownerId(),
                binding.worldIdentity(), generation);
    }

    private static GlobalInventoryGraph graph(
            ServerLevel level,
            List<WarehouseDiscovery.Candidate> found,
            ResourceId warehouseId,
            ResourceId ownerId,
            String worldIdentity,
            long generation) {
        ResourceId dimension = ResourceId.parse(level.dimension().location().toString());

        List<WarehouseEndpointSnapshot> endpoints = new ArrayList<>();
        Map<BlockPos3i, ResourceId> endpointIds = new LinkedHashMap<>();
        for (WarehouseDiscovery.Candidate candidate : found) {
            ResourceId endpointId = ResourceId.parse(
                    "steve_industrial:warehouse_endpoint/" + candidate.position().x() + "_"
                            + candidate.position().y() + "_" + candidate.position().z());
            endpointIds.put(candidate.position(), endpointId);
            Map<WarehouseResourceKey, Long> contents = new LinkedHashMap<>();
            candidate.contents().forEach((resource, quantity) -> contents.put(
                    new WarehouseResourceKey(GenericResourceType.ITEM, resource,
                            WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                    quantity));
            endpoints.add(new WarehouseEndpointSnapshot(
                    endpointId, warehouseId, ownerId, worldIdentity, dimension,
                    candidate.position(), Optional.of(Direction6.UP), candidate.blockEntityType(),
                    WarehouseEndpointType.ITEM_CONTAINER, contents,
                    candidate.totalCapacity(), stateHash(candidate), 0,
                    Long.MAX_VALUE, true));
        }
        if (endpoints.isEmpty()) {
            return new GlobalInventoryGraph(warehouseId, ownerId, worldIdentity, dimension,
                    List.of(), List.of(), generation);
        }

        List<WarehouseLogisticsEdge> edges = new ArrayList<>();
        for (WarehouseDiscovery.Candidate from : found) {
            for (WarehouseDiscovery.Candidate to : found) {
                if (from.position().equals(to.position())) continue;
                List<BlockPos3i> path = clearRun(level, from.position(), to.position());
                if (path.isEmpty()) continue;
                edges.add(new WarehouseLogisticsEdge(
                        ResourceId.parse("steve_industrial:warehouse_edge/"
                                + endpointIds.get(from.position()).path().replace('/', '_') + "__"
                                + endpointIds.get(to.position()).path().replace('/', '_')),
                        endpointIds.get(from.position()),
                        endpointIds.get(to.position()),
                        Set.of(GenericResourceType.ITEM),
                        path,
                        1,
                        sha256(path.toString()),
                        generation,
                        true,
                        true));
            }
        }
        return new GlobalInventoryGraph(warehouseId, ownerId, worldIdentity, dimension,
                endpoints, edges, generation);
    }

    /**
     * The cells between two containers when they share an axis and nothing blocks the run,
     * or empty when they do not.
     *
     * <p>Empty means "no edge claimed", never "no edge possible". A route round a corner
     * may well exist; this cannot verify one, so it does not assert one.</p>
     */
    private static List<BlockPos3i> clearRun(ServerLevel level, BlockPos3i from, BlockPos3i to) {
        int dx = Integer.signum(to.x() - from.x());
        int dy = Integer.signum(to.y() - from.y());
        int dz = Integer.signum(to.z() - from.z());
        // Straight runs only: exactly one axis may vary.
        int varying = (from.x() != to.x() ? 1 : 0) + (from.y() != to.y() ? 1 : 0)
                + (from.z() != to.z() ? 1 : 0);
        if (varying != 1) return List.of();
        int length = Math.abs(to.x() - from.x()) + Math.abs(to.y() - from.y())
                + Math.abs(to.z() - from.z());
        if (length > MAX_EDGE_LENGTH) return List.of();

        List<BlockPos3i> path = new ArrayList<>();
        for (int step = 1; step < length; step++) {
            BlockPos3i cell = new BlockPos3i(
                    from.x() + dx * step, from.y() + dy * step, from.z() + dz * step);
            BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!level.hasChunkAt(position)) return List.of();
            // Anything a courier would have to break is a blockage, and a blocked run is
            // reported as no edge rather than as an edge with a caveat.
            if (!level.getBlockState(position).isAir()) return List.of();
            path.add(cell);
        }
        return List.copyOf(path);
    }

    private static String stateHash(WarehouseDiscovery.Candidate candidate) {
        return sha256(candidate.position() + "|" + candidate.contents());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
