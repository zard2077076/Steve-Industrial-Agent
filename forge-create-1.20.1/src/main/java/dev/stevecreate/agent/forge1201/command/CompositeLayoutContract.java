package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Offline restatement of the physical preconditions the Composite wrappers enforce.
 *
 * <p>Between them, {@code CreateV606CompositeExecution} and
 * {@code CreateV606BranchMergeExecution} refuse a run on seventeen distinct grounds.
 * Twelve of those depend only on geometry and graph identity, which means they can be
 * decided from a layout without a world — but they were previously discoverable only by
 * booting a dedicated server and watching it refuse, at roughly thirty seconds to ten
 * minutes per attempt. Every Composite-03 layout defect found so far was one of these
 * twelve.</p>
 *
 * <p>The remaining five need real world state (a block really is a hopper, a chest holds
 * exactly its escrow) and stay where they are. This class deliberately does not restate
 * them: a check that cannot be evaluated honestly here would be worse than absent.</p>
 *
 * <p>Each rule below names the wrapper assertion it mirrors. When a wrapper's contract
 * changes, this must change with it — the equivalence is asserted by the layout tests
 * against both reviewed graphs, not assumed.</p>
 */
final class CompositeLayoutContract {
    private CompositeLayoutContract() {}

    /** Every offline-decidable reason a wrapper would refuse this layout. Empty is good. */
    static List<String> violations(
            CompositePlayerOrderSpecV1 spec, CompositeSiteLayout layout) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(layout, "layout");
        List<String> problems = new ArrayList<>();
        checkOwnedCellsDistinct(layout, problems);
        checkWorkspaceClearOfOwnedCells(layout, problems);
        checkEverythingInsideRegion(layout, problems);
        checkRouteGeometry(spec, layout, problems);
        checkSplitGeometry(spec, layout, problems);
        checkFleetPeak(spec, problems);
        return List.copyOf(problems);
    }

    /**
     * Placement, the pre-start emptiness check and cleanup all read {@code ownedCells},
     * so a repeated cell would install one block and try to clean two.
     */
    private static void checkOwnedCellsDistinct(
            CompositeSiteLayout layout, List<String> problems) {
        Set<BlockPos3i> seen = new LinkedHashSet<>();
        for (BlockPos3i cell : layout.ownedCells()) {
            if (!seen.add(cell)) problems.add("owned cell claimed twice: " + cell);
        }
    }

    /**
     * A machine anchor or worker start sitting on a boundary chest refused the whole
     * order with COMPOSITE_SITE_CELLS_OVERLAP, because the site emptiness check covers
     * both. The linear boundary lane runs east past the machine lane's x offset, which
     * is exactly how the last delivery chest once landed on the first machine anchor.
     */
    private static void checkWorkspaceClearOfOwnedCells(
            CompositeSiteLayout layout, List<String> problems) {
        Set<BlockPos3i> owned = Set.copyOf(layout.ownedCells());
        Set<BlockPos3i> anchors = new LinkedHashSet<>();
        for (CompositeSiteLayout.StageCells stage : layout.stages()) {
            if (owned.contains(stage.machineAnchor())) {
                problems.add("machine anchor sits on an owned cell: " + stage.machineAnchor());
            }
            if (!anchors.add(stage.machineAnchor())) {
                problems.add("two stages share a machine anchor: " + stage.machineAnchor());
            }
            for (BlockPos3i start : stage.workerStarts()) {
                if (owned.contains(start)) {
                    problems.add("worker start sits on an owned cell: " + start);
                }
            }
        }
    }

    /** Mirrors "chest is outside the bounded region" and its hopper/overflow variants. */
    private static void checkEverythingInsideRegion(
            CompositeSiteLayout layout, List<String> problems) {
        BlockPos3i minimum = layout.regionMinimum();
        BlockPos3i maximum = layout.regionMaximum();
        for (BlockPos3i cell : layout.ownedCells()) {
            if (!within(minimum, maximum, cell)) {
                problems.add("owned cell is outside the authorized region: " + cell);
            }
        }
        for (CompositeSiteLayout.StageCells stage : layout.stages()) {
            if (!within(minimum, maximum, stage.machineAnchor())) {
                problems.add("machine anchor is outside the region: " + stage.machineAnchor());
            }
            for (BlockPos3i start : stage.workerStarts()) {
                if (!within(minimum, maximum, start)) {
                    problems.add("worker start is outside the region: " + start);
                }
            }
        }
    }

    /**
     * Mirrors "Hopper route is outside or detached from delivery", "Hopper does not face
     * the next stage source" and the overflow adjacency rule. A hopper pulls from the
     * chest directly above it and pushes into the neighbour it faces, so those two cells
     * are what tie a route to its producer and consumer.
     */
    private static void checkRouteGeometry(
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<String> problems) {
        for (CompositeSiteLayout.RouteCells route : layout.routes()) {
            if (layout.split().map(split -> split.nodeId().equals(route.routeId())).orElse(false)) {
                continue;
            }
            CompositeProductionGraph.MaterialEdge edge = spec.graph().edges().stream()
                    .filter(value -> value.edgeId().equals(route.routeId())).findFirst()
                    .orElse(null);
            if (edge == null) {
                problems.add("route has no admitted graph edge: " + route.routeId());
                continue;
            }
            BlockPos3i producerDelivery = layout.stage(edge.producerNodeId()).delivery();
            BlockPos3i consumerSource = layout.stage(edge.consumerNodeId()).source();
            if (!above(route.hopper()).equals(producerDelivery)) {
                problems.add("route " + route.routeId()
                        + " does not pull from its producer's delivery chest");
            }
            if (!route.pushesInto().equals(consumerSource)) {
                problems.add("route " + route.routeId()
                        + " does not push into its consumer's source chest");
            }
            if (!adjacent(route.hopper(), route.pushesInto())) {
                problems.add("route " + route.routeId() + " hopper is not adjacent to its target");
            }
            if (!adjacent(route.hopper(), route.lock())) {
                problems.add("route " + route.routeId() + " lock cannot power its hopper");
            }
            route.overflow().ifPresent(overflow -> {
                if (!adjacent(route.hopper(), overflow)) {
                    problems.add("route " + route.routeId() + " salvage chest is not adjacent");
                }
                if (overflow.equals(route.pushesInto())) {
                    problems.add("route " + route.routeId()
                            + " salvage chest is the cell the hopper faces");
                }
            });
        }
    }

    /**
     * Mirrors "Root splitter geometry is invalid". The wrapper's root split takes one
     * unit for the branch its hopper faces and two for the branch beside it, so the
     * facing branch must be the single-input one — ordering branches by name put the
     * two-input branch there and the order refused before its first tick.
     */
    private static void checkSplitGeometry(
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            List<String> problems) {
        CompositeSiteLayout.SplitCells split = layout.split().orElse(null);
        if (split == null) return;
        if (!above(split.hopper()).equals(split.rawSource())) {
            problems.add("split hopper does not pull from the raw chest above it");
        }
        if (!adjacent(split.hopper(), split.firstBranchSource())) {
            problems.add("split hopper is not adjacent to the branch it faces");
        }
        if (!adjacent(split.hopper(), split.secondBranchSource())) {
            problems.add("split hopper is not adjacent to its second branch");
        }
        if (!adjacent(split.hopper(), split.lock())) {
            problems.add("split lock cannot power its hopper");
        }
        long facingEdges = rawEdgesTo(spec, split.nodeId(), split.facingBranchNode());
        long adjacentEdges = rawEdgesTo(spec, split.nodeId(), split.adjacentBranchNode());
        if (facingEdges != 1 || adjacentEdges != 2) {
            problems.add("split must face the single-input branch: facing takes "
                    + facingEdges + ", adjacent takes " + adjacentEdges);
        }
    }

    /**
     * Mirrors the completion evidence both wrappers assert. Fleet size is not a layout
     * convenience: a linear composite runs its stages serially so the peak is one
     * stage's fleet, while branch/merge runs two branches at once and the wrapper
     * demands exactly five concurrent workers.
     */
    private static void checkFleetPeak(
            CompositePlayerOrderSpecV1 spec, List<String> problems) {
        if (spec.graph().shape() == CompositeProductionGraph.Shape.LINEAR_CHAIN) {
            List<Integer> sizes = spec.stages().stream()
                    .map(CompositePlayerOrderSpecV1.StageSpec::botWorkers).distinct().toList();
            if (sizes.size() != 1 || sizes.get(0) != 3) {
                problems.add("a linear composite must run three workers per stage, saw " + sizes);
            }
            return;
        }
        CompositePlayerOrderSpecV1.RootSplitSpec split = spec.rootSplit().orElse(null);
        if (split == null) return;
        long concurrent = spec.stages().stream()
                .filter(stage -> !spec.graph().incoming(stage.nodeId()).isEmpty()
                        && spec.graph().incoming(stage.nodeId()).stream()
                                .allMatch(edge -> edge.producerNodeId().equals(split.nodeId())))
                .mapToLong(CompositePlayerOrderSpecV1.StageSpec::botWorkers).sum();
        if (concurrent != 5) {
            problems.add("branch/merge branches must run five workers at once, saw " + concurrent);
        }
    }

    private static long rawEdgesTo(
            CompositePlayerOrderSpecV1 spec, ResourceId splitNode, ResourceId branch) {
        return spec.graph().outgoing(splitNode).stream()
                .filter(edge -> edge.consumerNodeId().equals(branch)).count();
    }

    private static boolean within(BlockPos3i minimum, BlockPos3i maximum, BlockPos3i cell) {
        return cell.x() >= minimum.x() && cell.x() <= maximum.x()
                && cell.y() >= minimum.y() && cell.y() <= maximum.y()
                && cell.z() >= minimum.z() && cell.z() <= maximum.z();
    }

    private static boolean adjacent(BlockPos3i left, BlockPos3i right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z()) == 1;
    }

    private static BlockPos3i above(BlockPos3i value) {
        return new BlockPos3i(value.x(), value.y() + 1, value.z());
    }
}
