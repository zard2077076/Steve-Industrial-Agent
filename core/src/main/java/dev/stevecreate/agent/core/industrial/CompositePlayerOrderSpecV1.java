package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loader-neutral definition of one reviewed Composite player order.
 *
 * <p>A Composite graph alone is not a construction authorization.  This spec is the
 * bridge that makes a graph orderable: it states, for every admitted node, the exact
 * process inputs that stage consumes and the exact output it must physically produce,
 * and for every admitted edge, the route infrastructure that carries the intermediate.
 * From that it derives the one thing a player order needs before anything runs — the
 * external material demand, which is every stage input the player must supply because
 * no upstream edge delivers it.</p>
 *
 * <p>Intermediates are deliberately excluded from that demand.  A stage input covered
 * by an incoming edge must be produced by the real upstream handler, so reserving it
 * from the player's chest would let a composite complete without ever running its
 * first stage.  For the same reason a producer surplus is reported separately as
 * salvage rather than silently discarded.</p>
 *
 * <p>Boundary chests, hoppers and route locks are declared here as ordinary reserved
 * infrastructure rather than free scaffolding.  A Composite order places far more
 * owned blocks than one single-stage project, and charging them keeps the completion
 * report's cleanup settlement exact instead of letting the run conjure containers.</p>
 */
public record CompositePlayerOrderSpecV1(
        ResourceId orderType,
        ResourceId target,
        long targetQuantity,
        CompositeProductionGraph graph,
        List<StageSpec> stages,
        List<RouteSpec> routes,
        Optional<RootSplitSpec> rootSplit,
        Map<ResourceId, Long> infrastructureMaterials,
        List<IndustrialCapability> capabilities) {
    public static final int MAX_STAGE_INPUTS = 16;

    public CompositePlayerOrderSpecV1 {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(target, "target");
        if (targetQuantity < 1 || targetQuantity > 64) {
            throw new IllegalArgumentException("composite player target quantity is invalid");
        }
        Objects.requireNonNull(graph, "graph");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        rootSplit = Objects.requireNonNull(rootSplit, "rootSplit");
        infrastructureMaterials = materials(infrastructureMaterials, "infrastructureMaterials");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.isEmpty()
                || capabilities.size() > IndustrialPlayerOrderPlanV1.MAX_CAPABILITIES) {
            throw new IllegalArgumentException("composite player capabilities are invalid");
        }
        validateStages(graph, stages, rootSplit);
        validateRoutes(graph, stages, routes, rootSplit);
        StageSpec sink = sinkStage(graph, stages);
        if (!sink.target().equals(target) || sink.targetQuantity() < targetQuantity) {
            throw new IllegalArgumentException(
                    "composite order target is not the admitted sink stage output");
        }
    }

    /**
     * Every material the player must reserve before the first handler starts: each
     * stage's process inputs less whatever an incoming edge physically delivers, plus
     * everything a root split hands out.
     *
     * <p>A root split is not a processing stage.  It produces nothing: it takes raw
     * material the player supplied and distributes it into the branch source chests, so
     * every resource it hands out is external demand by definition.</p>
     */
    public Map<ResourceId, Long> externalProcessInputs() {
        TreeMap<ResourceId, Long> demand = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        rootSplit.ifPresent(split -> split.distributed().forEach(
                (resource, quantity) -> demand.merge(resource, quantity, Math::addExact)));
        for (StageSpec stage : stages) {
            Map<ResourceId, Long> delivered = deliveredInputs(stage.nodeId());
            stage.processInputs().forEach((resource, quantity) -> {
                long covered = delivered.getOrDefault(resource, 0L);
                long external = quantity - covered;
                if (external > 0) demand.merge(resource, external, Math::addExact);
            });
        }
        return unmodifiable(demand);
    }

    /**
     * Stage output a downstream edge does not claim.  This is real produced material
     * and must be settled into salvage, never treated as an unaccounted item.
     */
    public Map<ResourceId, Long> intermediateSalvage() {
        TreeMap<ResourceId, Long> surplus = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        for (StageSpec stage : stages) {
            if (graph.outgoing(stage.nodeId()).isEmpty()) continue;
            long claimed = graph.outgoing(stage.nodeId()).stream()
                    .filter(edge -> edge.resourceId().equals(stage.target()))
                    .mapToLong(CompositeProductionGraph.MaterialEdge::quantity).sum();
            long excess = stage.targetQuantity() - claimed;
            if (excess > 0) surplus.merge(stage.target(), excess, Math::addExact);
        }
        return unmodifiable(surplus);
    }

    public StageSpec stage(ResourceId nodeId) {
        return stages.stream().filter(value -> value.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown composite stage " + nodeId));
    }

    public RouteSpec route(ResourceId edgeId) {
        return routes.stream().filter(value -> value.edgeId().equals(edgeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown composite route " + edgeId));
    }

    private Map<ResourceId, Long> deliveredInputs(ResourceId nodeId) {
        LinkedHashMap<ResourceId, Long> delivered = new LinkedHashMap<>();
        graph.incoming(nodeId).forEach(edge ->
                delivered.merge(edge.resourceId(), edge.quantity(), Math::addExact));
        return delivered;
    }

    private static StageSpec sinkStage(CompositeProductionGraph graph, List<StageSpec> stages) {
        List<StageSpec> sinks = stages.stream()
                .filter(stage -> graph.outgoing(stage.nodeId()).isEmpty()).toList();
        if (sinks.size() != 1) {
            throw new IllegalArgumentException("composite player order needs exactly one sink stage");
        }
        return sinks.get(0);
    }

    private static void validateStages(
            CompositeProductionGraph graph,
            List<StageSpec> stages,
            Optional<RootSplitSpec> rootSplit) {
        Set<ResourceId> nodes = graph.nodes().stream()
                .map(CompositeProductionGraph.Node::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        Set<ResourceId> specified = new java.util.LinkedHashSet<>(
                stages.stream().map(StageSpec::nodeId).toList());
        if (specified.size() != stages.size()) {
            throw new IllegalArgumentException("composite player order repeats a stage node");
        }
        rootSplit.ifPresent(split -> {
            if (!specified.add(split.nodeId())) {
                throw new IllegalArgumentException(
                        "composite root split cannot also be a processing stage");
            }
            if (!graph.incoming(split.nodeId()).isEmpty()) {
                throw new IllegalArgumentException("composite root split must be a graph root");
            }
        });
        if (!specified.equals(nodes)) {
            throw new IllegalArgumentException(
                    "composite player order must cover exactly every admitted node once");
        }
    }

    private static void validateRoutes(
            CompositeProductionGraph graph,
            List<StageSpec> stages,
            List<RouteSpec> routes,
            Optional<RootSplitSpec> rootSplit) {
        Set<ResourceId> edges = graph.edges().stream()
                .map(CompositeProductionGraph.MaterialEdge::edgeId)
                .collect(java.util.stream.Collectors.toSet());
        Set<ResourceId> specified = routes.stream().map(RouteSpec::edgeId)
                .collect(java.util.stream.Collectors.toSet());
        if (routes.size() != graph.edges().size() || !specified.equals(edges)) {
            throw new IllegalArgumentException(
                    "composite player order must specify exactly one route per admitted edge");
        }
        Map<ResourceId, StageSpec> byNode = new LinkedHashMap<>();
        stages.forEach(stage -> byNode.put(stage.nodeId(), stage));
        Map<ResourceId, RouteSpec> byEdge = new LinkedHashMap<>();
        routes.forEach(route -> byEdge.put(route.edgeId(), route));
        for (CompositeProductionGraph.MaterialEdge edge : graph.edges()) {
            StageSpec consumer = byNode.get(edge.consumerNodeId());
            if (consumer == null) {
                throw new IllegalArgumentException(
                        "composite edge " + edge.edgeId() + " feeds a node that processes nothing");
            }
            long required = consumer.processInputs().getOrDefault(edge.resourceId(), 0L);
            if (required < edge.quantity()) {
                throw new IllegalArgumentException(
                        "composite edge " + edge.edgeId() + " delivers an input its consumer never uses");
            }
            List<CompositeProductionGraph.MaterialEdge> sameResource =
                    graph.outgoing(edge.producerNodeId()).stream()
                            .filter(value -> value.resourceId().equals(edge.resourceId())).toList();
            if (sameResource.size() != 1) {
                throw new IllegalArgumentException("composite producer " + edge.producerNodeId()
                        + " splits " + edge.resourceId() + " across routes, so surplus is ambiguous");
            }
            RouteSpec route = byEdge.get(edge.edgeId());
            StageSpec producer = byNode.get(edge.producerNodeId());
            if (producer == null) {
                // A root-split edge carries raw material the player supplied, not output
                // anything produced. It can never leave surplus, so it can never own one.
                RootSplitSpec split = rootSplit.filter(value ->
                        value.nodeId().equals(edge.producerNodeId())).orElseThrow(() ->
                        new IllegalArgumentException("composite edge " + edge.edgeId()
                                + " has no producing stage or root split"));
                long available = split.distributed().getOrDefault(edge.resourceId(), 0L);
                if (available < edge.quantity()) {
                    throw new IllegalArgumentException("composite root split does not hand out "
                            + edge.quantity() + " " + edge.resourceId());
                }
                if (route.overflowRequired()) {
                    throw new IllegalArgumentException("composite root-split route " + edge.edgeId()
                            + " cannot own an overflow destination");
                }
                continue;
            }
            if (!producer.target().equals(edge.resourceId())) {
                throw new IllegalArgumentException(
                        "composite edge " + edge.edgeId() + " does not carry its producer output");
            }
            if (edge.quantity() > producer.targetQuantity()) {
                throw new IllegalArgumentException(
                        "composite edge " + edge.edgeId() + " claims more than its producer makes");
            }
            // A linear route installs its overflow chest only where surplus exists.  A
            // branch/merge route always has one, because the wrapper's merge contract
            // requires a salvage boundary on every branch whether it fills or not.
            boolean surplus = producer.targetQuantity() > edge.quantity();
            boolean expected = graph.shape() == CompositeProductionGraph.Shape.BRANCH_MERGE
                    || surplus;
            if (expected != route.overflowRequired()) {
                throw new IllegalArgumentException("composite route " + edge.edgeId()
                        + " overflow declaration does not match its shape and surplus");
            }
        }
    }

    private static Map<ResourceId, Long> unmodifiable(Map<ResourceId, Long> values) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<ResourceId, Long> materials(Map<ResourceId, Long> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.size() > MAX_STAGE_INPUTS) {
            throw new IllegalArgumentException(name + " is outside its bounded contract");
        }
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        values.forEach((resource, quantity) -> {
            Objects.requireNonNull(resource, name + " resource");
            if (quantity == null || quantity < 1 || quantity > 1_024) {
                throw new IllegalArgumentException(name + " quantity is outside its bound");
            }
            sorted.put(resource, quantity);
        });
        return unmodifiable(sorted);
    }

    /**
     * One admitted node: what it consumes and what it must physically produce.  The
     * capability lives on the graph node and the exact recipe is resolved by the
     * runtime planner, so neither is restated — a reviewed catalog must not carry a
     * recipe identity nobody verified against the running game.
     */
    public record StageSpec(
            ResourceId nodeId,
            ResourceId target,
            long targetQuantity,
            Map<ResourceId, Long> processInputs,
            int botWorkers) {
        public StageSpec {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(target, "target");
            if (targetQuantity < 1 || targetQuantity > 64) {
                throw new IllegalArgumentException("composite stage quantity must be between 1 and 64");
            }
            processInputs = materials(processInputs, "processInputs");
            // Fleet size is a reviewed execution parameter, not a layout convenience.
            // Both wrappers assert an exact peak worker count, and concurrent branches
            // add up: Composite-01 runs three per serial stage, while Composite-03's
            // cutting and deploying branches run 2 and 3 at once for the five its
            // wrapper requires.
            if (botWorkers < 2 || botWorkers > 5) {
                throw new IllegalArgumentException("composite stage fleet size is invalid");
            }
        }
    }

    /**
     * One admitted edge.  An edge whose producer makes more than it claims must
     * declare an overflow destination, so surplus intermediate lands in a real
     * salvage chest instead of being lost at the route.
     */
    public record RouteSpec(ResourceId edgeId, boolean overflowRequired) {
        public RouteSpec {
            Objects.requireNonNull(edgeId, "edgeId");
        }
    }

    /**
     * The graph root of a branch/merge composite.  It is not a processing stage: it
     * produces nothing and instead hands the player's own raw material out to the
     * branch source chests, which is why everything it distributes is external demand.
     */
    public record RootSplitSpec(ResourceId nodeId, Map<ResourceId, Long> distributed) {
        public RootSplitSpec {
            Objects.requireNonNull(nodeId, "nodeId");
            distributed = materials(distributed, "distributed");
        }
    }
}
