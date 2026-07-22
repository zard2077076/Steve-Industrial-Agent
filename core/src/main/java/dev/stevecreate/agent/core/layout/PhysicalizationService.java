package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.graph.EdgeMode;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.LogicalGraphNode;
import dev.stevecreate.agent.core.planning.LogicalNodeKind;
import dev.stevecreate.agent.core.planning.LogicalPortRequirement;
import dev.stevecreate.agent.core.planning.LogicalResourceEdge;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;

/** Bounded deterministic implementation-bound layout and routing service. */
public final class PhysicalizationService {
    private static final int MODULE_SPACING = 16;
    private static final ResourceId BOUNDARY_IMPLEMENTATION = id("layout:external_boundary");
    private static final ResourceId POWER_IMPLEMENTATION = id("layout:bounded_power_source");
    private static final List<int[]> NEIGHBORS = List.of(
            new int[] {1, 0, 0}, new int[] {0, 0, 1}, new int[] {-1, 0, 0},
            new int[] {0, 0, -1}, new int[] {0, 1, 0}, new int[] {0, -1, 0});

    public PhysicalizationResult physicalize(
            VerifiedImplementationBoundPlan boundPlan,
            ImmutableMachineGeometryCatalog geometries,
            LayoutConstraints constraints) {
        if (boundPlan == null || geometries == null || constraints == null) {
            return failure(LayoutFailureCode.LAYOUT_CONSTRAINTS_MISSING,
                    LayoutStage.CONSTRAINT_VALIDATION, null, null,
                    "A verified bound plan, geometry catalog and typed constraints are required");
        }
        String runtime = boundPlan.graph().runtimeFingerprint();
        if (!runtime.equals(geometries.runtimeFingerprint())
                || !runtime.equals(constraints.snapshot().runtimeFingerprint())) {
            return failure(LayoutFailureCode.WORLD_SNAPSHOT_STALE,
                    LayoutStage.CONSTRAINT_VALIDATION, null, null,
                    "Binding, geometry and world snapshot fingerprints differ");
        }

        List<BoundMachineNode> boundNodes = new ArrayList<>(
                boundPlan.graph().boundProcessNodes().values());
        Map<ResourceId, MachineGeometryDescriptor> resolvedGeometries = new LinkedHashMap<>();
        long requiredStress = 0;
        for (BoundMachineNode node : boundNodes) {
            MachineGeometryDescriptor geometry = geometries.find(node.implementationId()).orElse(null);
            if (geometry == null) {
                return failure(LayoutFailureCode.UNSUPPORTED_IMPLEMENTATION_GEOMETRY,
                        LayoutStage.GEOMETRY_RESOLUTION, node.logicalNodeId(), node.implementationId(),
                        "No exact geometry descriptor exists for the selected implementation");
            }
            if (geometry.footprint().cells().isEmpty()) {
                return failure(LayoutFailureCode.FOOTPRINT_UNAVAILABLE,
                        LayoutStage.GEOMETRY_RESOLUTION, node.logicalNodeId(), node.implementationId(),
                        "The selected implementation has no footprint");
            }
            resolvedGeometries.put(node.logicalNodeId(), geometry);
            requiredStress = Math.addExact(requiredStress, geometry.stressImpact());
        }
        if (requiredStress > constraints.stressCapacity()) {
            return failure(LayoutFailureCode.STRESS_CAPACITY_INSUFFICIENT,
                    LayoutStage.CAPACITY_VALIDATION, null, null,
                    "Required stress " + requiredStress + " exceeds " + constraints.stressCapacity());
        }

        MutableBudget budget = new MutableBudget(constraints.searchBudget());
        FailureMarker marker = new FailureMarker();
        int examined = 0;
        for (QuarterTurn orientation : constraints.allowedOrientations()) {
            boolean supported = resolvedGeometries.values().stream()
                    .allMatch(value -> value.supportedOrientations().contains(orientation));
            if (!supported) {
                marker.orientation = true;
                continue;
            }
            for (BlockPos3i offset : candidateOffsets(constraints.maximumSearchRadius())) {
                if (++examined > constraints.maximumCandidates()) break;
                BlockPos3i origin = constraints.anchor().translate(offset.x(), 0, offset.z());
                CandidateAssembly assembly;
                try {
                    assembly = assemblePlacements(
                            boundNodes,
                            boundPlan.graph().logicalPlan().logicalGraph().ports(),
                            resolvedGeometries, origin, orientation, constraints, budget, marker);
                } catch (BudgetExhausted ignored) {
                    return failure(LayoutFailureCode.SEARCH_BUDGET_EXHAUSTED,
                            LayoutStage.CANDIDATE_GENERATION, null, null,
                            "The declared deterministic search budget was exhausted");
                }
                if (assembly == null) continue;

                PhysicalizationResult assembled;
                try {
                    assembled = assembleAndVerify(
                            boundPlan, geometries, constraints, assembly, examined, budget, marker);
                } catch (BudgetExhausted ignored) {
                    return failure(LayoutFailureCode.SEARCH_BUDGET_EXHAUSTED,
                            LayoutStage.ROUTING, null, null,
                            "The declared deterministic routing budget was exhausted");
                }
                if (assembled instanceof PhysicalizationSuccess) return assembled;
                if (assembled instanceof PhysicalizationFailure value
                        && value.failure().code() != LayoutFailureCode.ITEM_ROUTE_NOT_FOUND) {
                    return value;
                }
                marker.itemRoute = true;
            }
        }
        if (marker.orientation && examined == 0) {
            return failure(LayoutFailureCode.ORIENTATION_UNAVAILABLE,
                    LayoutStage.GEOMETRY_RESOLUTION, null, null,
                    "No requested orientation is supported by every selected implementation");
        }
        LayoutFailureCode code = marker.itemRoute ? LayoutFailureCode.ITEM_ROUTE_NOT_FOUND
                : marker.powerRoute ? LayoutFailureCode.ROTATIONAL_POWER_ROUTE_NOT_FOUND
                : marker.port ? LayoutFailureCode.PHYSICAL_PORT_RESOLUTION_FAILED
                : marker.stale ? LayoutFailureCode.WORLD_SNAPSHOT_STALE
                : marker.clearance ? LayoutFailureCode.CLEARANCE_BLOCKED
                : marker.collision ? LayoutFailureCode.PLACEMENT_COLLISION
                : LayoutFailureCode.PHYSICALIZATION_FAILED;
        LayoutStage stage = marker.itemRoute ? LayoutStage.ROUTING : LayoutStage.PLACEMENT_VALIDATION;
        return failure(code, stage, null, null, "No bounded deterministic candidate was feasible");
    }

    private PhysicalizationResult assembleAndVerify(
            VerifiedImplementationBoundPlan boundPlan,
            ImmutableMachineGeometryCatalog geometries,
            LayoutConstraints constraints,
            CandidateAssembly assembly,
            int examined,
            MutableBudget budget,
            FailureMarker marker) {
        LogicalGraphData graphData = logicalGraphData(boundPlan, assembly);
        List<PhysicalRoute> routes = new ArrayList<>();
        List<LogicalResourceEdge> logicalEdges = boundPlan.graph().logicalPlan().logicalGraph()
                .edges().values().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList();
        for (LogicalResourceEdge edge : logicalEdges) {
            if (edge.resourceType() != GenericResourceType.ITEM) {
                return failure(LayoutFailureCode.PHYSICALIZATION_FAILED,
                        LayoutStage.ROUTING, null, null, "Only ITEM logical routing is enabled in this foundation");
            }
            if (edge.amount() > constraints.itemRouteCapacity()) {
                return failure(LayoutFailureCode.ROUTE_CAPACITY_INSUFFICIENT,
                        LayoutStage.CAPACITY_VALIDATION, null, null,
                        "Logical item amount exceeds the declared route capacity");
            }
            BlockPos3i source = graphData.endpointPositions.get(edge.sourcePortId());
            BlockPos3i target = graphData.endpointPositions.get(edge.targetPortId());
            if (source == null || target == null) {
                return failure(LayoutFailureCode.PHYSICAL_PORT_RESOLUTION_FAILED,
                        LayoutStage.ROUTING, null, null, "A logical route endpoint has no physical port");
            }
            Set<BlockPos3i> blocked = new HashSet<>(assembly.reservedCells);
            for (PhysicalMachinePlacement placement : assembly.placements) {
                if (placement.ports().containsKey(edge.sourcePortId())
                        || placement.ports().containsKey(edge.targetPortId())) {
                    blocked.removeAll(placement.clearance());
                }
            }
            blocked.addAll(assembly.componentCells);
            List<BlockPos3i> path = route(
                    source, target, blocked, constraints, budget);
            if (path == null) {
                marker.itemRoute = true;
                return failure(LayoutFailureCode.ITEM_ROUTE_NOT_FOUND,
                        LayoutStage.ROUTING, null, null, "No bounded collision-free ITEM route exists");
            }
            routes.add(new PhysicalRoute(
                    id("layout:item_route_" + routes.size()), edge.sourcePortId(), edge.targetPortId(),
                    GenericResourceType.ITEM, edge.amount(), constraints.itemRouteCapacity(), path));
            graphData.edges.add(new MachineEdge(
                    id("layout:item_edge_" + routes.size()),
                    graphData.graphPortIds.get(edge.sourcePortId()),
                    graphData.graphPortIds.get(edge.targetPortId()),
                    GenericResourceType.ITEM, EdgeMode.DIRECTED,
                    OptionalLong.of(constraints.itemRouteCapacity()),
                    Map.of("route", routes.get(routes.size() - 1).id().toString())));
        }
        addPowerTopology(assembly, graphData, constraints);
        UnifiedMachineGraph graph;
        try {
            graph = new UnifiedMachineGraph(
                    id("layout:physical_" + boundPlan.graph().id().path()),
                    graphData.nodes, graphData.ports, graphData.edges);
        } catch (IllegalArgumentException exception) {
            return failure(LayoutFailureCode.UNIFIED_GRAPH_INVALID,
                    LayoutStage.GRAPH_ASSEMBLY, null, null, exception.getMessage());
        }
        List<String> trace = new ArrayList<>();
        trace.add("layout:binding=" + boundPlan.id());
        trace.add("layout:orientation=" + assembly.orientation.name());
        trace.add("layout:placements=" + assembly.placements.size());
        trace.add("layout:item_routes=" + routes.size());
        trace.add("layout:search_operations=" + budget.used());
        PhysicalLayoutCandidate candidate = new PhysicalLayoutCandidate(
                id("layout:candidate_" + boundPlan.graph().id().path()), boundPlan,
                assembly.placements, routes, graph, constraints.snapshot().runtimeFingerprint(),
                examined, budget.used(), trace);
        return new PhysicalizationVerifier().verify(candidate, geometries, constraints);
    }

    private CandidateAssembly assemblePlacements(
            List<BoundMachineNode> nodes,
            Map<ResourceId, LogicalPortRequirement> logicalPorts,
            Map<ResourceId, MachineGeometryDescriptor> geometries,
            BlockPos3i origin,
            QuarterTurn orientation,
            LayoutConstraints constraints,
            MutableBudget budget,
            FailureMarker marker) {
        List<PhysicalMachinePlacement> placements = new ArrayList<>();
        Set<BlockPos3i> componentCells = new HashSet<>();
        Set<BlockPos3i> reservedCells = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            BoundMachineNode node = nodes.get(index);
            MachineGeometryDescriptor geometry = geometries.get(node.logicalNodeId());
            BlockPos3i moduleOffset = new BlockPos3i(index * MODULE_SPACING, 0, 0).rotateY(orientation);
            BlockPos3i anchor = origin.translate(moduleOffset.x(), moduleOffset.y(), moduleOffset.z());
            List<ResolvedGeometryComponent> components = new ArrayList<>();
            Set<BlockPos3i> previousComponents = Set.copyOf(componentCells);
            for (GeometryComponent component : geometry.components()) {
                budget.operation();
                BlockPos3i position = resolve(anchor, component.relativePosition(), orientation);
                Optional<LayoutCellState> state = constraints.snapshot().state(position);
                if (state.isEmpty() || state.get() == LayoutCellState.UNLOADED) {
                    marker.stale = true;
                    return null;
                }
                if (state.get() != LayoutCellState.REPLACEABLE
                        || reservedCells.contains(position)
                        || !componentCells.add(position)) {
                    marker.collision = true;
                    return null;
                }
                components.add(new ResolvedGeometryComponent(
                        component.roleId(), component.blockId(), position,
                        rotateBlockState(component.blockState(), orientation)));
            }
            List<BlockPos3i> clearance = new ArrayList<>();
            for (BlockPos3i relative : geometry.clearance().cells()) {
                budget.operation();
                BlockPos3i position = resolve(anchor, relative, orientation);
                Optional<LayoutCellState> state = constraints.snapshot().state(position);
                if (state.isEmpty() || state.get() == LayoutCellState.UNLOADED) {
                    marker.stale = true;
                    return null;
                }
                if (previousComponents.contains(position)
                        || (!componentCells.contains(position)
                        && state.get() != LayoutCellState.REPLACEABLE)) {
                    marker.clearance = true;
                    return null;
                }
                clearance.add(position);
            }
            reservedCells.addAll(clearance);
            Map<ResourceId, ResolvedPhysicalPort> ports = new LinkedHashMap<>();
            for (Map.Entry<ResourceId, ResourceId> mapping : node.logicalToImplementationPorts().entrySet()) {
                PhysicalPortRule rule;
                try {
                    rule = geometry.port(mapping.getValue());
                } catch (IllegalArgumentException exception) {
                    marker.port = true;
                    return null;
                }
                LogicalPortRequirement logical = logicalPorts.get(mapping.getKey());
                if (logical == null || logical.resourceType() != rule.resourceType()
                        || logical.mode() != rule.mode()) {
                    marker.port = true;
                    return null;
                }
                BlockPos3i position = resolve(anchor, rule.relativePosition(), orientation);
                Optional<Direction6> side = rule.side().map(value -> value.rotateY(orientation));
                ports.put(mapping.getKey(), new ResolvedPhysicalPort(
                        mapping.getKey(), mapping.getValue(), position, side,
                        rule.resourceType(), rule.mode(), rule.capacity()));
            }
            List<BlockPos3i> powerRoute = geometry.rotationalPowerRoute().stream()
                    .map(relative -> resolve(anchor, relative, orientation)).toList();
            if (powerRoute.size() > constraints.maximumRouteLength()
                    || geometry.rotationalPowerCapacity() > constraints.rotationalPowerCapacity()) {
                marker.powerRoute = true;
                return null;
            }
            placements.add(new PhysicalMachinePlacement(
                    node.logicalNodeId(), node.implementationId(), anchor, orientation,
                    components, ports, clearance, powerRoute, geometry.stressImpact(),
                    geometry.topologyContract()));
        }
        return new CandidateAssembly(orientation, placements, componentCells, reservedCells);
    }

    private LogicalGraphData logicalGraphData(
            VerifiedImplementationBoundPlan boundPlan,
            CandidateAssembly assembly) {
        var logical = boundPlan.graph().logicalPlan().logicalGraph();
        Map<ResourceId, PhysicalMachinePlacement> placementByNode = new HashMap<>();
        assembly.placements.forEach(value -> placementByNode.put(value.logicalNodeId(), value));
        LogicalGraphData data = new LogicalGraphData();
        for (PhysicalMachinePlacement placement : assembly.placements) {
            placement.ports().forEach((logicalPortId, physicalPort) ->
                    data.endpointPositions.put(logicalPortId, physicalPort.position()));
        }
        for (LogicalResourceEdge edge : logical.edges().values()) {
            LogicalPortRequirement source = logical.port(edge.sourcePortId());
            LogicalPortRequirement target = logical.port(edge.targetPortId());
            if (!data.endpointPositions.containsKey(source.id())
                    && data.endpointPositions.containsKey(target.id())) {
                data.endpointPositions.put(source.id(), adjacent(
                        data.endpointPositions.get(target.id()), Optional.empty(), true));
            }
            if (!data.endpointPositions.containsKey(target.id())
                    && data.endpointPositions.containsKey(source.id())) {
                data.endpointPositions.put(target.id(), adjacent(
                        data.endpointPositions.get(source.id()), Optional.empty(), false));
            }
        }
        int nodeIndex = 0;
        for (LogicalGraphNode logicalNode : logical.nodes().values()) {
            PhysicalMachinePlacement placement = placementByNode.get(logicalNode.id());
            ResourceId graphNodeId = id("layout:node_" + nodeIndex++);
            data.graphNodeIds.put(logicalNode.id(), graphNodeId);
            if (placement != null) {
                data.nodes.add(new MachineNode(
                        graphNodeId, id("layout:role_process"), placement.implementationId(),
                        placement.anchor(), MachineOrientation.NONE, Set.of(),
                        Map.of("logical_node", logicalNode.id().toString(),
                                "orientation", placement.orientation().name(),
                                "topology", placement.topologyContract())));
            } else {
                BlockPos3i boundaryPosition = logical.ports().values().stream()
                        .filter(value -> value.nodeId().equals(logicalNode.id()))
                        .map(value -> data.endpointPositions.get(value.id()))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Logical boundary has no resolved physical endpoint: " + logicalNode.id()));
                data.nodes.add(new MachineNode(
                        graphNodeId, boundaryRole(logicalNode.kind()), BOUNDARY_IMPLEMENTATION,
                        boundaryPosition, MachineOrientation.NONE, Set.of(),
                        Map.of("logical_node", logicalNode.id().toString())));
            }
        }
        int portIndex = 0;
        for (LogicalPortRequirement logicalPort : logical.ports().values()) {
            ResourceId graphPortId = id("layout:port_" + portIndex++);
            data.graphPortIds.put(logicalPort.id(), graphPortId);
            PhysicalMachinePlacement placement = placementByNode.get(logicalPort.nodeId());
            ResolvedPhysicalPort resolved = placement == null ? null : placement.ports().get(logicalPort.id());
            Optional<Direction6> side = resolved == null ? Optional.empty() : resolved.side();
            OptionalLong capacity = OptionalLong.of(resolved == null
                    ? logicalPort.amount() : Math.max(resolved.capacity(), logicalPort.amount()));
            data.ports.add(new MachinePort(
                    graphPortId, data.graphNodeIds.get(logicalPort.nodeId()), logicalPort.resourceType(),
                    logicalPort.mode(), side, capacity,
                    Map.of("logical_port", logicalPort.id().toString(),
                            "resource", logicalPort.resourceId().toString())));
        }
        return data;
    }

    private void addPowerTopology(
            CandidateAssembly assembly,
            LogicalGraphData data,
            LayoutConstraints constraints) {
        int index = 0;
        for (PhysicalMachinePlacement placement : assembly.placements) {
            ResourceId processNode = data.graphNodeIds.get(placement.logicalNodeId());
            ResourceId powerNode = id("layout:power_node_" + index);
            ResourceId powerOutput = id("layout:power_output_" + index);
            ResourceId powerInput = id("layout:power_input_" + index);
            data.nodes.add(new MachineNode(
                    powerNode, id("layout:role_power_source"), POWER_IMPLEMENTATION,
                    placement.rotationalPowerRoute().get(0), MachineOrientation.NONE, Set.of(),
                    Map.of("bounded", "true")));
            data.ports.add(new MachinePort(powerOutput, powerNode, GenericResourceType.ROTATIONAL_POWER,
                    PortMode.OUTPUT, Optional.empty(), OptionalLong.of(constraints.rotationalPowerCapacity()),
                    Map.of("route", "descriptor")));
            data.ports.add(new MachinePort(powerInput, processNode, GenericResourceType.ROTATIONAL_POWER,
                    PortMode.INPUT, Optional.empty(), OptionalLong.of(constraints.rotationalPowerCapacity()),
                    Map.of("route", "descriptor")));
            data.edges.add(new MachineEdge(id("layout:power_edge_" + index), powerOutput, powerInput,
                    GenericResourceType.ROTATIONAL_POWER, EdgeMode.DIRECTED,
                    OptionalLong.of(constraints.rotationalPowerCapacity()),
                    Map.of("positions", Integer.toString(placement.rotationalPowerRoute().size()))));
            index++;
        }
    }

    private List<BlockPos3i> route(
            BlockPos3i start,
            BlockPos3i target,
            Set<BlockPos3i> componentCells,
            LayoutConstraints constraints,
            MutableBudget budget) {
        Queue<BlockPos3i> queue = new ArrayDeque<>();
        Map<BlockPos3i, BlockPos3i> previous = new HashMap<>();
        Map<BlockPos3i, Integer> distance = new HashMap<>();
        queue.add(start);
        previous.put(start, null);
        distance.put(start, 0);
        while (!queue.isEmpty()) {
            BlockPos3i current = queue.remove();
            budget.operation();
            if (current.equals(target)) return reconstruct(previous, target);
            int nextDistance = distance.get(current) + 1;
            if (nextDistance > constraints.maximumRouteLength()) continue;
            for (int[] delta : NEIGHBORS) {
                BlockPos3i next = current.translate(delta[0], delta[1], delta[2]);
                if (previous.containsKey(next)
                        || manhattan(start, next) > constraints.maximumSearchRadius()) continue;
                boolean endpoint = next.equals(target);
                LayoutCellState state = constraints.snapshot().state(next).orElse(LayoutCellState.UNLOADED);
                if (!endpoint && (state != LayoutCellState.REPLACEABLE || componentCells.contains(next))) continue;
                previous.put(next, current);
                distance.put(next, nextDistance);
                queue.add(next);
            }
        }
        return null;
    }

    private static List<BlockPos3i> reconstruct(Map<BlockPos3i, BlockPos3i> previous, BlockPos3i target) {
        List<BlockPos3i> reversed = new ArrayList<>();
        for (BlockPos3i cursor = target; cursor != null; cursor = previous.get(cursor)) reversed.add(cursor);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static int manhattan(BlockPos3i left, BlockPos3i right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y()) + Math.abs(left.z() - right.z());
    }

    private static BlockPos3i adjacent(BlockPos3i position, Optional<Direction6> ignored, boolean source) {
        if (position == null) throw new IllegalArgumentException("Boundary must connect directly to a physical process port");
        return position.translate(source ? -1 : 1, 0, 0);
    }

    private static List<BlockPos3i> candidateOffsets(int radius) {
        List<BlockPos3i> values = new ArrayList<>();
        values.add(new BlockPos3i(0, 0, 0));
        for (int r = 1; r <= radius; r++) {
            for (int x = -r; x <= r; x++) values.add(new BlockPos3i(x, 0, -r));
            for (int z = -r + 1; z <= r; z++) values.add(new BlockPos3i(r, 0, z));
            for (int x = r - 1; x >= -r; x--) values.add(new BlockPos3i(x, 0, r));
            for (int z = r - 1; z > -r; z--) values.add(new BlockPos3i(-r, 0, z));
        }
        return values;
    }

    private static BlockPos3i resolve(BlockPos3i anchor, BlockPos3i relative, QuarterTurn orientation) {
        BlockPos3i rotated = relative.rotateY(orientation);
        return anchor.translate(rotated.x(), rotated.y(), rotated.z());
    }

    private static Map<String, String> rotateBlockState(
            Map<String, String> state,
            QuarterTurn orientation) {
        Map<String, String> rotated = new LinkedHashMap<>(state);
        String axis = rotated.get("axis");
        if (axis != null && (axis.equals("x") || axis.equals("z"))
                && (orientation == QuarterTurn.CLOCKWISE_90
                || orientation == QuarterTurn.CLOCKWISE_270)) {
            rotated.put("axis", axis.equals("x") ? "z" : "x");
        }
        String facing = rotated.get("facing");
        if (facing != null) {
            try {
                rotated.put("facing", Direction6.valueOf(facing.toUpperCase(java.util.Locale.ROOT))
                        .rotateY(orientation).name().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported geometry facing state: " + facing, exception);
            }
        }
        return Map.copyOf(rotated);
    }

    private static ResourceId boundaryRole(LogicalNodeKind kind) {
        return id("layout:role_" + kind.name().toLowerCase());
    }

    private static PhysicalizationFailure failure(
            LayoutFailureCode code,
            LayoutStage stage,
            ResourceId node,
            ResourceId implementation,
            String message) {
        return new PhysicalizationFailure(new LayoutFailure(
                code, stage, Optional.ofNullable(node), Optional.ofNullable(implementation),
                message == null ? code.name() : message,
                Map.of("bounded", "true", "deterministic", "true"),
                List.of("physicalization:" + code.name().toLowerCase()),
                "Correct the typed constraint or refresh the read-only snapshot, then physicalize again"));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static final class CandidateAssembly {
        private final QuarterTurn orientation;
        private final List<PhysicalMachinePlacement> placements;
        private final Set<BlockPos3i> componentCells;
        private final Set<BlockPos3i> reservedCells;

        private CandidateAssembly(
                QuarterTurn orientation,
                List<PhysicalMachinePlacement> placements,
                Set<BlockPos3i> componentCells,
                Set<BlockPos3i> reservedCells) {
            this.orientation = orientation;
            this.placements = List.copyOf(placements);
            this.componentCells = Set.copyOf(componentCells);
            this.reservedCells = Set.copyOf(reservedCells);
        }
    }

    private static final class LogicalGraphData {
        private final List<MachineNode> nodes = new ArrayList<>();
        private final List<MachinePort> ports = new ArrayList<>();
        private final List<MachineEdge> edges = new ArrayList<>();
        private final Map<ResourceId, ResourceId> graphNodeIds = new LinkedHashMap<>();
        private final Map<ResourceId, ResourceId> graphPortIds = new LinkedHashMap<>();
        private final Map<ResourceId, BlockPos3i> endpointPositions = new LinkedHashMap<>();
    }

    private static final class FailureMarker {
        private boolean orientation;
        private boolean collision;
        private boolean clearance;
        private boolean stale;
        private boolean port;
        private boolean itemRoute;
        private boolean powerRoute;
    }

    private static final class MutableBudget {
        private final int maximum;
        private int used;
        private MutableBudget(int maximum) { this.maximum = maximum; }
        private void operation() {
            if (++used > maximum) throw new BudgetExhausted();
        }
        private int used() { return used; }
    }

    private static final class BudgetExhausted extends RuntimeException {}
}
