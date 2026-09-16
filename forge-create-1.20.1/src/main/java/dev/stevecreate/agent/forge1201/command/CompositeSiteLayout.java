package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic ground plan for one Composite player order.
 *
 * <p>The reviewed acceptance fixtures hand-place every chest, hopper and route lock at
 * literal arena coordinates.  A player order cannot do that: it gets one site origin
 * and must derive the same physical shape, so the run the player starts is the run the
 * existing wrapper already has evidence for.</p>
 *
 * <p>A physical route is not the same thing as a graph edge.  Composite-03's root split
 * carries three edges through one hopper, so routes are modelled by the hardware they
 * install and each one records the cell its hopper must push into — the wrappers check
 * hopper facing against that neighbour, and deriving it here keeps this class free of
 * any Minecraft type.</p>
 */
final class CompositeSiteLayout {
    /** East-running spacing between one linear stage boundary and the next. */
    private static final int STAGE_PITCH = 5;
    /**
     * Machine anchors sit on their own lane, offset on both axes.  The linear boundary
     * lane runs east far enough that a shared x offset would put the last stage's
     * delivery chest exactly on the first stage's anchor.
     */
    private static final int MACHINE_LANE_OFFSET = 18;
    private static final int MACHINE_LANE_START = 8;
    private static final int MACHINE_PITCH = 8;
    private static final int WORKER_LANE_OFFSET = 5;
    /** North-running distance from the branch/merge split plane to the merge plane. */
    private static final int MERGE_PITCH = 5;

    private final CompositePlayerOrderSpecV1 spec;
    private final List<StageCells> stages;
    private final List<RouteCells> routes;
    private final Optional<SplitCells> split;
    private final BlockPos3i regionMinimum;
    private final BlockPos3i regionMaximum;

    private CompositeSiteLayout(
            CompositePlayerOrderSpecV1 spec,
            List<StageCells> stages,
            List<RouteCells> routes,
            Optional<SplitCells> split,
            BlockPos3i regionMinimum,
            BlockPos3i regionMaximum) {
        this.spec = spec;
        this.stages = List.copyOf(stages);
        this.routes = List.copyOf(routes);
        this.split = split;
        this.regionMinimum = regionMinimum;
        this.regionMaximum = regionMaximum;
    }

    /**
     * The only way to build a layout.  Both shape builders are private because calling
     * the wrong one for a graph is a mistake the compiler should prevent, not one the
     * gate should discover ten seconds into a run.
     */
    static CompositeSiteLayout forSpec(CompositePlayerOrderSpecV1 spec, BlockPos3i origin) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(origin, "origin");
        CompositeSiteLayout layout = switch (spec.graph().shape()) {
            case LINEAR_CHAIN -> linear(spec, origin);
            case BRANCH_MERGE -> branchMerge(spec, origin);
            case CONCURRENT_LINES -> throw new IllegalArgumentException(
                    "concurrent-lines composites have no player layout yet");
        };
        // Decide here what a dedicated server would otherwise take minutes to refuse.
        List<String> violations = CompositeLayoutContract.violations(spec, layout);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "composite layout violates its physical contract: " + violations);
        }
        return layout;
    }

    /**
     * Linear layout.  The origin is the first stage's source chest; a locked hopper
     * pulls from the producer's delivery chest directly above it and pushes east into
     * the consumer's source chest.
     */
    private static CompositeSiteLayout linear(
            CompositePlayerOrderSpecV1 spec, BlockPos3i origin) {
        if (spec.graph().shape() != CompositeProductionGraph.Shape.LINEAR_CHAIN) {
            throw new IllegalArgumentException("linear layout requires a linear composite graph");
        }
        List<CompositeProductionGraph.Node> ordered = linearOrder(spec.graph());
        List<StageCells> stages = new ArrayList<>();
        List<RouteCells> routes = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            CompositeProductionGraph.Node node = ordered.get(index);
            BlockPos3i source = offset(origin, index * (STAGE_PITCH + 1), 0, 0);
            List<CompositeProductionGraph.MaterialEdge> outgoing = spec.graph().outgoing(node.nodeId());
            BlockPos3i delivery;
            if (outgoing.isEmpty()) {
                delivery = offset(source, STAGE_PITCH + 1, 0, 0);
            } else {
                BlockPos3i hopper = offset(source, STAGE_PITCH, 0, 0);
                CompositeProductionGraph.MaterialEdge edge = outgoing.get(0);
                delivery = offset(hopper, 0, 1, 0);
                routes.add(new RouteCells(edge.edgeId(), hopper, offset(hopper, 0, 0, 1),
                        offset(hopper, 1, 0, 0),
                        spec.route(edge.edgeId()).overflowRequired()
                                ? Optional.of(offset(hopper, 0, 0, -1)) : Optional.empty()));
            }
            stages.add(new StageCells(node.nodeId(), source, delivery,
                    offset(origin, MACHINE_LANE_OFFSET, 0,
                            MACHINE_LANE_START + index * MACHINE_PITCH),
                    workerStarts(origin, 0, WORKER_LANE_OFFSET + index * MACHINE_PITCH,
                            spec.stage(node.nodeId()).botWorkers())));
        }
        BlockPos3i last = stages.get(stages.size() - 1).delivery();
        BlockPos3i minimum = offset(origin, -1, 0, -2);
        BlockPos3i maximum = new BlockPos3i(
                Math.max(last.x(), origin.x() + MACHINE_LANE_OFFSET) + 11,
                origin.y() + 5,
                origin.z() + MACHINE_LANE_START + (ordered.size() - 1) * MACHINE_PITCH + 9);
        return new CompositeSiteLayout(spec, stages, routes, Optional.empty(), minimum, maximum);
    }

    /**
     * Branch/merge layout.  The origin is the split hopper's ground cell: the raw chest
     * the player fills sits directly above it, the hopper pushes west into the first
     * branch and the second branch's source is its eastern neighbour.  Each branch
     * delivers into a chest above its own merge hopper, which pushes inward into the
     * shared merge source with a salvage boundary on its outer side.
     */
    private static CompositeSiteLayout branchMerge(
            CompositePlayerOrderSpecV1 spec, BlockPos3i origin) {
        if (spec.graph().shape() != CompositeProductionGraph.Shape.BRANCH_MERGE
                || spec.rootSplit().isEmpty()) {
            throw new IllegalArgumentException("branch/merge layout requires a split-rooted graph");
        }
        CompositePlayerOrderSpecV1.RootSplitSpec rootSplit = spec.rootSplit().orElseThrow();
        List<ResourceId> branches = branchOrder(spec, rootSplit.nodeId());
        ResourceId first = branches.get(0);
        ResourceId second = branches.get(1);
        ResourceId sink = sinkNode(spec);

        BlockPos3i splitHopper = origin;
        BlockPos3i rawSource = offset(splitHopper, 0, 1, 0);
        BlockPos3i firstSource = offset(splitHopper, -1, 0, 0);
        BlockPos3i secondSource = offset(splitHopper, 1, 0, 0);
        BlockPos3i mergeSource = offset(splitHopper, 0, 0, MERGE_PITCH);
        BlockPos3i firstMergeHopper = offset(mergeSource, -1, 0, 0);
        BlockPos3i secondMergeHopper = offset(mergeSource, 1, 0, 0);
        BlockPos3i finalDelivery = offset(splitHopper, 0, 0, MERGE_PITCH * 2);

        List<StageCells> stages = List.of(
                new StageCells(first, firstSource, offset(firstMergeHopper, 0, 1, 0),
                        offset(origin, -MACHINE_LANE_OFFSET, 0, MACHINE_LANE_START),
                        workerStarts(origin, -MACHINE_LANE_OFFSET, WORKER_LANE_OFFSET,
                                spec.stage(first).botWorkers())),
                new StageCells(second, secondSource, offset(secondMergeHopper, 0, 1, 0),
                        offset(origin, MACHINE_LANE_OFFSET, 0, MACHINE_LANE_START),
                        workerStarts(origin, MACHINE_LANE_OFFSET, WORKER_LANE_OFFSET,
                                spec.stage(second).botWorkers())),
                new StageCells(sink, mergeSource, finalDelivery,
                        offset(origin, MACHINE_LANE_OFFSET, 0,
                                MACHINE_LANE_START + MACHINE_PITCH * 2),
                        workerStarts(origin, MACHINE_LANE_OFFSET,
                                WORKER_LANE_OFFSET + MACHINE_PITCH * 2,
                                spec.stage(sink).botWorkers())));

        List<RouteCells> routes = List.of(
                new RouteCells(rootSplit.nodeId(), splitHopper, offset(splitHopper, 0, 0, -1),
                        firstSource, Optional.empty()),
                new RouteCells(mergeEdge(spec, first), firstMergeHopper,
                        offset(firstMergeHopper, 0, 0, -1), mergeSource,
                        Optional.of(offset(firstMergeHopper, -1, 0, 0))),
                new RouteCells(mergeEdge(spec, second), secondMergeHopper,
                        offset(secondMergeHopper, 0, 0, -1), mergeSource,
                        Optional.of(offset(secondMergeHopper, 1, 0, 0))));

        BlockPos3i minimum = new BlockPos3i(origin.x() - MACHINE_LANE_OFFSET - 4,
                origin.y() - 1, origin.z() - 3);
        BlockPos3i maximum = new BlockPos3i(origin.x() + MACHINE_LANE_OFFSET + 11,
                origin.y() + 5,
                origin.z() + MACHINE_LANE_START + MACHINE_PITCH * 2 + 9);
        return new CompositeSiteLayout(spec, stages, routes,
                Optional.of(new SplitCells(rootSplit.nodeId(), rawSource, splitHopper,
                        offset(splitHopper, 0, 0, -1), first, firstSource, second, secondSource)),
                minimum, maximum);
    }

    /**
     * Rebuilds a layout with substituted parts so the contract tests can express a
     * defect that used to require a server run. Deliberately bypasses the contract:
     * producing a knowingly bad layout is the point, and nothing outside tests calls it.
     */
    static CompositeSiteLayout forTesting(
            CompositeSiteLayout template,
            List<StageCells> stages,
            List<RouteCells> routes,
            Optional<SplitCells> split) {
        return new CompositeSiteLayout(template.spec, stages, routes, split,
                template.regionMinimum, template.regionMaximum);
    }

    List<StageCells> stages() { return stages; }

    List<RouteCells> routes() { return routes; }

    Optional<SplitCells> split() { return split; }

    BlockPos3i regionMinimum() { return regionMinimum; }

    BlockPos3i regionMaximum() { return regionMaximum; }

    StageCells stage(ResourceId nodeId) {
        return stages.stream().filter(value -> value.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown composite stage " + nodeId));
    }

    RouteCells route(ResourceId routeId) {
        return routes.stream().filter(value -> value.routeId().equals(routeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown composite route " + routeId));
    }

    /**
     * Every cell this order installs an owned block into.  Placement, the pre-start
     * emptiness check and cleanup all read the same list, so the order cannot install
     * a block it never checked or forget to remove one it installed.
     */
    List<BlockPos3i> ownedCells() {
        LinkedHashSet<BlockPos3i> cells = new LinkedHashSet<>();
        split.ifPresent(value -> cells.add(value.rawSource()));
        for (StageCells stage : stages) {
            cells.add(stage.source());
            cells.add(stage.delivery());
        }
        for (RouteCells route : routes) {
            cells.add(route.hopper());
            cells.add(route.lock());
            route.overflow().ifPresent(cells::add);
        }
        return List.copyOf(cells);
    }

    /** The block each owned cell must receive. */
    ResourceId ownedBlock(BlockPos3i cell) {
        for (RouteCells route : routes) {
            if (route.hopper().equals(cell)) return ResourceId.parse("minecraft:hopper");
            if (route.lock().equals(cell)) return ResourceId.parse("minecraft:redstone_block");
        }
        return ResourceId.parse("minecraft:chest");
    }

    /**
     * Owned blocks by item, checked against the spec's declared infrastructure so a
     * layout change can never quietly install more containers than the player paid for.
     */
    Map<ResourceId, Long> infrastructureMaterials() {
        LinkedHashMap<ResourceId, Long> totals = new LinkedHashMap<>();
        ownedCells().forEach(cell -> totals.merge(ownedBlock(cell), 1L, Math::addExact));
        return Map.copyOf(totals);
    }

    boolean matchesDeclaredInfrastructure() {
        return infrastructureMaterials().equals(spec.infrastructureMaterials());
    }

    /**
     * Orders the two branches by how much raw material each takes from the split.
     *
     * <p>The wrapper's root-split contract is one unit to the branch the hopper faces
     * and two to the branch beside it, so the single-input branch has to be the one on
     * the facing side.  Ordering by name instead put the two-input branch there and the
     * whole order refused before its first tick.</p>
     */
    private static List<ResourceId> branchOrder(
            CompositePlayerOrderSpecV1 spec, ResourceId splitNode) {
        Map<ResourceId, Long> fromSplit = new LinkedHashMap<>();
        spec.graph().outgoing(splitNode).forEach(edge ->
                fromSplit.merge(edge.consumerNodeId(), 1L, Math::addExact));
        List<ResourceId> branches = fromSplit.keySet().stream()
                .sorted(Comparator.<ResourceId, Long>comparing(fromSplit::get)
                        .thenComparing(ResourceId::toString)).toList();
        if (branches.size() != 2
                || fromSplit.get(branches.get(0)) != 1L
                || fromSplit.get(branches.get(1)) != 2L) {
            throw new IllegalArgumentException(
                    "branch/merge layout expects one single-input and one two-input branch");
        }
        return branches;
    }

    private static ResourceId sinkNode(CompositePlayerOrderSpecV1 spec) {
        return spec.stages().stream().map(CompositePlayerOrderSpecV1.StageSpec::nodeId)
                .filter(node -> spec.graph().outgoing(node).isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("branch/merge graph has no sink"));
    }

    private static ResourceId mergeEdge(CompositePlayerOrderSpecV1 spec, ResourceId producer) {
        return spec.graph().outgoing(producer).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "branch " + producer + " has no merge route")).edgeId();
    }

    private static List<CompositeProductionGraph.Node> linearOrder(CompositeProductionGraph graph) {
        CompositeProductionGraph.Node root = graph.nodes().stream()
                .filter(node -> graph.incoming(node.nodeId()).isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("linear composite has no root stage"));
        List<CompositeProductionGraph.Node> ordered = new ArrayList<>();
        Set<ResourceId> seen = new LinkedHashSet<>();
        CompositeProductionGraph.Node current = root;
        while (current != null && seen.add(current.nodeId())) {
            ordered.add(current);
            List<CompositeProductionGraph.MaterialEdge> outgoing = graph.outgoing(current.nodeId());
            current = outgoing.isEmpty() ? null : graph.node(outgoing.get(0).consumerNodeId());
        }
        if (ordered.size() != graph.nodes().size()) {
            throw new IllegalArgumentException("linear composite is not a single chain");
        }
        return List.copyOf(ordered);
    }

    private static List<BlockPos3i> workerStarts(
            BlockPos3i origin, int laneX, int laneZ, int workers) {
        List<BlockPos3i> starts = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            starts.add(offset(origin, laneX + worker, 0, laneZ));
        }
        return List.copyOf(starts);
    }

    private static BlockPos3i offset(BlockPos3i base, int x, int y, int z) {
        return new BlockPos3i(base.x() + x, base.y() + y, base.z() + z);
    }

    record StageCells(
            ResourceId nodeId,
            BlockPos3i source,
            BlockPos3i delivery,
            BlockPos3i machineAnchor,
            List<BlockPos3i> workerStarts) {
        StageCells {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(machineAnchor, "machineAnchor");
            workerStarts = List.copyOf(Objects.requireNonNull(workerStarts, "workerStarts"));
        }
    }

    /**
     * One physical hopper route.  {@code pushesInto} is the neighbour the hopper must
     * face; both wrappers validate facing against it.
     */
    record RouteCells(
            ResourceId routeId,
            BlockPos3i hopper,
            BlockPos3i lock,
            BlockPos3i pushesInto,
            Optional<BlockPos3i> overflow) {
        RouteCells {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(hopper, "hopper");
            Objects.requireNonNull(lock, "lock");
            Objects.requireNonNull(pushesInto, "pushesInto");
            overflow = Objects.requireNonNull(overflow, "overflow");
        }
    }

    /** The raw chest and hardware a branch/merge root split draws from. */
    record SplitCells(
            ResourceId nodeId,
            BlockPos3i rawSource,
            BlockPos3i hopper,
            BlockPos3i lock,
            ResourceId facingBranchNode,
            BlockPos3i firstBranchSource,
            ResourceId adjacentBranchNode,
            BlockPos3i secondBranchSource) {
        SplitCells {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(hopper, "hopper");
            Objects.requireNonNull(lock, "lock");
            Objects.requireNonNull(facingBranchNode, "facingBranchNode");
            Objects.requireNonNull(firstBranchSource, "firstBranchSource");
            Objects.requireNonNull(adjacentBranchNode, "adjacentBranchNode");
            Objects.requireNonNull(secondBranchSource, "secondBranchSource");
        }
    }
}
