package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidNetworkNode;
import dev.stevecreate.agent.core.fluid.FluidNodeKind;
import dev.stevecreate.agent.core.fluid.FluidRoute;
import dev.stevecreate.agent.core.industrial.EntityLogisticsBindingV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceBindingV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.warehouse.WarehouseLogisticsEdge;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** NBT codec for immutable IPO-03 selectors; live observations are never read from this codec. */
final class IndustrialResourceBindingNbt {
    private IndustrialResourceBindingNbt() {}

    static CompoundTag encode(IndustrialResourceBindingV1 value) {
        CompoundTag tag = new CompoundTag();
        putId(tag, "Project", value.projectId());
        putId(tag, "Owner", value.ownerId());
        tag.putString("World", value.worldIdentity());
        putId(tag, "Dimension", value.dimension());
        tag.put("Warehouse", warehouse(value.warehouse()));
        tag.put("Requests", compounds(value.warehouseRequests().stream()
                .map(IndustrialResourceBindingNbt::request).toList()));
        tag.put("Logistics", logistics(value.entityLogistics()));
        value.electricalNetwork().ifPresent(graph -> tag.put("Electrical", electrical(graph)));
        value.fluidNetwork().ifPresent(graph -> tag.put("Fluid", fluid(graph)));
        tag.put("PlannedEnergy", resourceAmounts(value.plannedEnergy()));
        tag.put("PlannedFluids", fluidAmounts(value.plannedFluids()));
        return tag;
    }

    static IndustrialResourceBindingV1 decode(CompoundTag tag) {
        ResourceId project = id(tag, "Project");
        ResourceId owner = id(tag, "Owner");
        String world = tag.getString("World");
        ResourceId dimension = id(tag, "Dimension");
        List<WarehouseReservationRequest> requests = new ArrayList<>();
        for (Tag raw : tag.getList("Requests", Tag.TAG_COMPOUND)) {
            requests.add(request((CompoundTag) raw));
        }
        Optional<ElectricalNetworkGraph> electrical = tag.contains("Electrical", Tag.TAG_COMPOUND)
                ? Optional.of(electrical(tag.getCompound("Electrical"))) : Optional.empty();
        Optional<FluidNetworkGraph> fluid = tag.contains("Fluid", Tag.TAG_COMPOUND)
                ? Optional.of(fluid(tag.getCompound("Fluid"))) : Optional.empty();
        return new IndustrialResourceBindingV1(project, owner, world, dimension,
                warehouse(tag.getCompound("Warehouse")), requests,
                logistics(tag.getCompound("Logistics")), electrical, fluid,
                resourceAmounts(tag.getList("PlannedEnergy", Tag.TAG_COMPOUND)),
                fluidAmounts(tag.getList("PlannedFluids", Tag.TAG_COMPOUND)));
    }

    private static CompoundTag warehouse(GlobalInventoryGraph graph) {
        CompoundTag tag = new CompoundTag();
        putId(tag, "Warehouse", graph.warehouseId()); putId(tag, "Owner", graph.ownerId());
        tag.putString("World", graph.worldIdentity()); putId(tag, "Dimension", graph.dimension());
        tag.putLong("Generation", graph.generation());
        tag.put("Endpoints", compounds(graph.endpoints().values().stream()
                .map(IndustrialResourceBindingNbt::endpoint).toList()));
        tag.put("Edges", compounds(graph.edges().values().stream()
                .map(IndustrialResourceBindingNbt::warehouseEdge).toList()));
        return tag;
    }

    private static GlobalInventoryGraph warehouse(CompoundTag tag) {
        List<WarehouseEndpointSnapshot> endpoints = new ArrayList<>();
        for (Tag raw : tag.getList("Endpoints", Tag.TAG_COMPOUND)) {
            endpoints.add(endpoint((CompoundTag) raw));
        }
        List<WarehouseLogisticsEdge> edges = new ArrayList<>();
        for (Tag raw : tag.getList("Edges", Tag.TAG_COMPOUND)) {
            edges.add(warehouseEdge((CompoundTag) raw));
        }
        return new GlobalInventoryGraph(id(tag, "Warehouse"), id(tag, "Owner"),
                tag.getString("World"), id(tag, "Dimension"), endpoints, edges,
                tag.getLong("Generation"));
    }

    private static CompoundTag endpoint(WarehouseEndpointSnapshot value) {
        CompoundTag tag = new CompoundTag();
        putId(tag, "Endpoint", value.endpointId()); putId(tag, "Warehouse", value.warehouseId());
        putId(tag, "Owner", value.ownerId()); tag.putString("World", value.worldIdentity());
        putId(tag, "Dimension", value.dimension()); tag.put("Position", position(value.position()));
        value.accessFace().ifPresent(face -> tag.putString("Face", face.name()));
        putId(tag, "BlockEntity", value.blockEntityType());
        tag.putString("Type", value.endpointType().name());
        tag.put("Contents", warehouseAmounts(value.contents()));
        tag.putLong("Capacity", value.totalCapacity()); tag.putString("Hash", value.snapshotSha256());
        tag.putLong("Generation", value.generation()); tag.putLong("Expires", value.expiresAtEpochMillis());
        tag.putBoolean("Permission", value.permissionVerified());
        return tag;
    }

    private static WarehouseEndpointSnapshot endpoint(CompoundTag tag) {
        return new WarehouseEndpointSnapshot(id(tag, "Endpoint"), id(tag, "Warehouse"),
                id(tag, "Owner"), tag.getString("World"), id(tag, "Dimension"),
                position(tag.getCompound("Position")), tag.contains("Face", Tag.TAG_STRING)
                        ? Optional.of(Direction6.valueOf(tag.getString("Face"))) : Optional.empty(),
                id(tag, "BlockEntity"), WarehouseEndpointType.valueOf(tag.getString("Type")),
                warehouseAmounts(tag.getList("Contents", Tag.TAG_COMPOUND)), tag.getLong("Capacity"),
                tag.getString("Hash"), tag.getLong("Generation"), tag.getLong("Expires"),
                tag.getBoolean("Permission"));
    }

    private static CompoundTag warehouseEdge(WarehouseLogisticsEdge value) {
        CompoundTag tag = new CompoundTag();
        putId(tag, "Edge", value.edgeId()); putId(tag, "From", value.fromEndpointId());
        putId(tag, "To", value.toEndpointId()); putEnums(tag, "Types", value.supportedResourceTypes());
        tag.put("Path", positions(value.path())); tag.putLong("Capacity", value.capacityPerTick());
        tag.putString("Hash", value.pathSha256()); tag.putLong("Generation", value.generation());
        tag.putBoolean("CollisionFree", value.collisionFree());
        tag.putBoolean("Verified", value.serverVerified());
        return tag;
    }

    private static WarehouseLogisticsEdge warehouseEdge(CompoundTag tag) {
        Set<GenericResourceType> types = new LinkedHashSet<>();
        strings(tag, "Types").forEach(value -> types.add(GenericResourceType.valueOf(value)));
        return new WarehouseLogisticsEdge(id(tag, "Edge"), id(tag, "From"), id(tag, "To"),
                types, positions(tag.getList("Path", Tag.TAG_COMPOUND)), tag.getLong("Capacity"),
                tag.getString("Hash"), tag.getLong("Generation"), tag.getBoolean("CollisionFree"),
                tag.getBoolean("Verified"));
    }

    private static CompoundTag request(WarehouseReservationRequest value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Request", value.requestId());
        putId(tag, "Project", value.projectId()); putId(tag, "Owner", value.ownerId());
        tag.put("Resource", warehouseKey(value.resource())); tag.putLong("Quantity", value.quantity());
        putIds(tag, "Endpoints", value.allowedEndpointIds()); tag.putLong("Expires", value.expiresAtEpochMillis());
        tag.putInt("Priority", value.priority()); return tag;
    }

    private static WarehouseReservationRequest request(CompoundTag tag) {
        return new WarehouseReservationRequest(id(tag, "Request"), id(tag, "Project"),
                id(tag, "Owner"), warehouseKey(tag.getCompound("Resource")), tag.getLong("Quantity"),
                ids(tag, "Endpoints"), tag.getLong("Expires"), tag.getInt("Priority"));
    }

    private static CompoundTag logistics(EntityLogisticsBindingV1 value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Session", value.sessionId());
        putId(tag, "Graph", value.taskGraphId()); tag.putString("Hash", value.taskGraphFingerprint());
        putIds(tag, "Workers", value.workerIds()); putIds(tag, "Assignments", value.assignmentIds());
        tag.putLong("Generation", value.generation()); tag.putLong("Observed", value.observedTick());
        return tag;
    }

    private static EntityLogisticsBindingV1 logistics(CompoundTag tag) {
        return new EntityLogisticsBindingV1(id(tag, "Session"), id(tag, "Graph"),
                tag.getString("Hash"), ids(tag, "Workers"), ids(tag, "Assignments"),
                tag.getLong("Generation"), tag.getLong("Observed"));
    }

    private static CompoundTag electrical(ElectricalNetworkGraph graph) {
        CompoundTag tag = graphBase(graph.graphId(), graph.worldIdentity(), graph.dimension(),
                graph.generation());
        tag.put("Nodes", compounds(graph.nodes().values().stream()
                .map(IndustrialResourceBindingNbt::electricalNode).toList()));
        tag.put("Edges", compounds(graph.edges().values().stream()
                .map(IndustrialResourceBindingNbt::electricalEdge).toList()));
        return tag;
    }

    private static ElectricalNetworkGraph electrical(CompoundTag tag) {
        List<ElectricalNetworkNode> nodes = new ArrayList<>();
        for (Tag raw : tag.getList("Nodes", Tag.TAG_COMPOUND)) nodes.add(electricalNode((CompoundTag) raw));
        List<ElectricalWireEdge> edges = new ArrayList<>();
        for (Tag raw : tag.getList("Edges", Tag.TAG_COMPOUND)) edges.add(electricalEdge((CompoundTag) raw));
        return new ElectricalNetworkGraph(id(tag, "Graph"), tag.getString("World"),
                id(tag, "Dimension"), nodes, edges, tag.getLong("Generation"));
    }

    private static CompoundTag electricalNode(ElectricalNetworkNode value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Node", value.nodeId());
        tag.putString("Kind", value.kind().name()); tag.put("Position", position(value.position()));
        tag.putString("Primary", value.primaryTier().name());
        value.secondaryTier().ifPresent(tier -> tag.putString("Secondary", tier.name()));
        putEnums(tag, "Inputs", value.inputFaces()); putEnums(tag, "Outputs", value.outputFaces());
        tag.putLong("Generation", value.generationPerTick()); tag.putLong("Consumption", value.consumptionPerTick());
        tag.putLong("Stored", value.storedEnergy()); tag.putLong("Capacity", value.storageCapacity());
        tag.putString("Hash", value.stateSha256()); tag.putBoolean("Observed", value.serverObserved());
        return tag;
    }

    private static ElectricalNetworkNode electricalNode(CompoundTag tag) {
        return new ElectricalNetworkNode(id(tag, "Node"), ElectricalNodeKind.valueOf(tag.getString("Kind")),
                position(tag.getCompound("Position")), VoltageTier.valueOf(tag.getString("Primary")),
                tag.contains("Secondary", Tag.TAG_STRING) ? Optional.of(VoltageTier.valueOf(tag.getString("Secondary"))) : Optional.empty(),
                directionSet(tag, "Inputs"), directionSet(tag, "Outputs"), tag.getLong("Generation"),
                tag.getLong("Consumption"), tag.getLong("Stored"), tag.getLong("Capacity"),
                tag.getString("Hash"), tag.getBoolean("Observed"));
    }

    private static CompoundTag electricalEdge(ElectricalWireEdge value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Edge", value.edgeId());
        putId(tag, "From", value.fromNodeId()); putId(tag, "To", value.toNodeId());
        tag.putString("Tier", value.voltageTier().name()); tag.putInt("Distance", value.transferDistance());
        tag.putInt("Maximum", value.maximumDistance()); tag.putLong("Capacity", value.capacityPerTick());
        tag.put("Envelope", positions(value.collisionEnvelope())); tag.putString("Hash", value.connectionSha256());
        tag.putBoolean("Connected", value.endpointsConnected()); tag.putBoolean("CollisionFree", value.collisionFree());
        tag.putBoolean("Observed", value.serverObserved()); return tag;
    }

    private static ElectricalWireEdge electricalEdge(CompoundTag tag) {
        return new ElectricalWireEdge(id(tag, "Edge"), id(tag, "From"), id(tag, "To"),
                VoltageTier.valueOf(tag.getString("Tier")), tag.getInt("Distance"), tag.getInt("Maximum"),
                tag.getLong("Capacity"), positions(tag.getList("Envelope", Tag.TAG_COMPOUND)),
                tag.getString("Hash"), tag.getBoolean("Connected"), tag.getBoolean("CollisionFree"),
                tag.getBoolean("Observed"));
    }

    private static CompoundTag fluid(FluidNetworkGraph graph) {
        CompoundTag tag = graphBase(graph.graphId(), graph.worldIdentity(), graph.dimension(), graph.generation());
        tag.put("Nodes", compounds(graph.nodes().values().stream().map(IndustrialResourceBindingNbt::fluidNode).toList()));
        tag.put("Routes", compounds(graph.routes().values().stream().map(IndustrialResourceBindingNbt::fluidRoute).toList()));
        return tag;
    }

    private static FluidNetworkGraph fluid(CompoundTag tag) {
        List<FluidNetworkNode> nodes = new ArrayList<>();
        for (Tag raw : tag.getList("Nodes", Tag.TAG_COMPOUND)) nodes.add(fluidNode((CompoundTag) raw));
        List<FluidRoute> routes = new ArrayList<>();
        for (Tag raw : tag.getList("Routes", Tag.TAG_COMPOUND)) routes.add(fluidRoute((CompoundTag) raw));
        return new FluidNetworkGraph(id(tag, "Graph"), tag.getString("World"), id(tag, "Dimension"),
                nodes, routes, tag.getLong("Generation"));
    }

    private static CompoundTag fluidNode(FluidNetworkNode value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Node", value.nodeId());
        tag.putString("Kind", value.kind().name()); tag.put("Position", position(value.position()));
        value.contents().ifPresent(identity -> tag.put("Contents", fluidIdentity(identity)));
        tag.putLong("Amount", value.amountMb()); tag.putLong("Capacity", value.capacityMb());
        putEnums(tag, "Inputs", value.inputFaces()); putEnums(tag, "Outputs", value.outputFaces());
        tag.putLong("PumpRate", value.pumpRateMbPerTick()); tag.putString("Hash", value.snapshotSha256());
        tag.putBoolean("Observed", value.serverObserved()); return tag;
    }

    private static FluidNetworkNode fluidNode(CompoundTag tag) {
        return new FluidNetworkNode(id(tag, "Node"), FluidNodeKind.valueOf(tag.getString("Kind")),
                position(tag.getCompound("Position")), tag.contains("Contents", Tag.TAG_COMPOUND)
                        ? Optional.of(fluidIdentity(tag.getCompound("Contents"))) : Optional.empty(),
                tag.getLong("Amount"), tag.getLong("Capacity"), directionSet(tag, "Inputs"),
                directionSet(tag, "Outputs"), tag.getLong("PumpRate"), tag.getString("Hash"),
                tag.getBoolean("Observed"));
    }

    private static CompoundTag fluidRoute(FluidRoute value) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Route", value.routeId());
        putId(tag, "From", value.fromNodeId()); putId(tag, "To", value.toNodeId());
        tag.put("Fluid", fluidIdentity(value.fluid())); tag.put("Positions", positions(value.positions()));
        tag.putLong("Required", value.requiredMb()); tag.putLong("Capacity", value.capacityMb());
        tag.putBoolean("Directional", value.directional()); tag.putBoolean("Valve", value.valveOpen());
        tag.putBoolean("CollisionFree", value.collisionFree()); tag.putBoolean("Connected", value.endpointsConnected());
        tag.putString("Hash", value.routeSha256()); tag.putBoolean("Observed", value.serverObserved()); return tag;
    }

    private static FluidRoute fluidRoute(CompoundTag tag) {
        return new FluidRoute(id(tag, "Route"), id(tag, "From"), id(tag, "To"),
                fluidIdentity(tag.getCompound("Fluid")), positions(tag.getList("Positions", Tag.TAG_COMPOUND)),
                tag.getLong("Required"), tag.getLong("Capacity"), tag.getBoolean("Directional"),
                tag.getBoolean("Valve"), tag.getBoolean("CollisionFree"), tag.getBoolean("Connected"),
                tag.getString("Hash"), tag.getBoolean("Observed"));
    }

    private static CompoundTag graphBase(ResourceId graph, String world, ResourceId dimension, long generation) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Graph", graph); tag.putString("World", world);
        putId(tag, "Dimension", dimension); tag.putLong("Generation", generation); return tag;
    }
    private static CompoundTag warehouseKey(WarehouseResourceKey key) {
        CompoundTag tag = new CompoundTag(); tag.putString("Type", key.resourceType().serializedName());
        putId(tag, "Resource", key.resourceId()); tag.putString("Components", key.componentSha256()); return tag;
    }
    private static WarehouseResourceKey warehouseKey(CompoundTag tag) {
        return new WarehouseResourceKey(GenericResourceType.fromSerializedName(tag.getString("Type")),
                id(tag, "Resource"), tag.getString("Components"));
    }
    private static CompoundTag fluidIdentity(FluidIdentity identity) {
        CompoundTag tag = new CompoundTag(); putId(tag, "Fluid", identity.fluidId());
        tag.putString("Components", identity.componentSha256()); return tag;
    }
    private static FluidIdentity fluidIdentity(CompoundTag tag) {
        return new FluidIdentity(id(tag, "Fluid"), tag.getString("Components"));
    }
    private static ListTag warehouseAmounts(Map<WarehouseResourceKey, Long> values) {
        ListTag rows = new ListTag(); values.forEach((key, amount) -> { CompoundTag row = warehouseKey(key);
            row.putLong("Amount", amount); rows.add(row); }); return rows;
    }
    private static Map<WarehouseResourceKey, Long> warehouseAmounts(ListTag rows) {
        Map<WarehouseResourceKey, Long> values = new LinkedHashMap<>();
        for (Tag raw : rows) { CompoundTag row = (CompoundTag) raw; values.put(warehouseKey(row), row.getLong("Amount")); }
        return values;
    }
    private static ListTag resourceAmounts(Map<ResourceId, Long> values) {
        ListTag rows = new ListTag(); values.forEach((key, amount) -> { CompoundTag row = new CompoundTag();
            putId(row, "Resource", key); row.putLong("Amount", amount); rows.add(row); }); return rows;
    }
    private static Map<ResourceId, Long> resourceAmounts(ListTag rows) {
        Map<ResourceId, Long> values = new LinkedHashMap<>(); for (Tag raw : rows) { CompoundTag row = (CompoundTag) raw;
            values.put(id(row, "Resource"), row.getLong("Amount")); } return values;
    }
    private static ListTag fluidAmounts(Map<FluidIdentity, Long> values) {
        ListTag rows = new ListTag(); values.forEach((key, amount) -> { CompoundTag row = fluidIdentity(key);
            row.putLong("Amount", amount); rows.add(row); }); return rows;
    }
    private static Map<FluidIdentity, Long> fluidAmounts(ListTag rows) {
        Map<FluidIdentity, Long> values = new LinkedHashMap<>(); for (Tag raw : rows) { CompoundTag row = (CompoundTag) raw;
            values.put(fluidIdentity(row), row.getLong("Amount")); } return values;
    }
    private static CompoundTag position(BlockPos3i value) { CompoundTag tag = new CompoundTag();
        tag.putInt("X", value.x()); tag.putInt("Y", value.y()); tag.putInt("Z", value.z()); return tag; }
    private static BlockPos3i position(CompoundTag tag) { return new BlockPos3i(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")); }
    private static ListTag positions(List<BlockPos3i> values) { return compounds(values.stream().map(IndustrialResourceBindingNbt::position).toList()); }
    private static List<BlockPos3i> positions(ListTag rows) { List<BlockPos3i> values = new ArrayList<>();
        for (Tag raw : rows) values.add(position((CompoundTag) raw)); return values; }
    private static ListTag compounds(List<CompoundTag> values) { ListTag rows = new ListTag(); rows.addAll(values); return rows; }
    private static void putId(CompoundTag tag, String key, ResourceId value) { tag.putString(key, value.toString()); }
    private static ResourceId id(CompoundTag tag, String key) { return ResourceId.parse(tag.getString(key)); }
    private static void putIds(CompoundTag tag, String key, List<ResourceId> values) { ListTag rows = new ListTag();
        values.forEach(value -> rows.add(StringTag.valueOf(value.toString()))); tag.put(key, rows); }
    private static List<ResourceId> ids(CompoundTag tag, String key) { return strings(tag, key).stream().map(ResourceId::parse).toList(); }
    private static void putEnums(CompoundTag tag, String key, Iterable<? extends Enum<?>> values) { ListTag rows = new ListTag();
        values.forEach(value -> rows.add(StringTag.valueOf(value.name()))); tag.put(key, rows); }
    private static List<String> strings(CompoundTag tag, String key) { return tag.getList(key, Tag.TAG_STRING).stream().map(Tag::getAsString).toList(); }
    private static Set<Direction6> directionSet(CompoundTag tag, String key) { Set<Direction6> values = new LinkedHashSet<>();
        strings(tag, key).forEach(value -> values.add(Direction6.valueOf(value))); return values; }
}
