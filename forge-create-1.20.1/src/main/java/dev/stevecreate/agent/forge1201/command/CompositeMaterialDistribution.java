package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderSpecV1;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where each withdrawn item must physically land, and in what order.
 *
 * <p>Both wrappers assert exact per-chest contents, so this decides whether a run
 * starts at all. It was previously inlined in the order service against live world
 * objects, which made it untestable — and it is where this session's most expensive
 * defect lived: a vanilla hopper draws by slot rather than by type, so the raw chest's
 * slot order decides which branch gets served first. Nothing in either wrapper asserts
 * that; the reviewed fixture merely seeded an ordered chest.</p>
 *
 * <p>Everything here is a pure function of the layout and the reviewed spec. The
 * service performs the resulting moves; it does not decide them.</p>
 */
final class CompositeMaterialDistribution {
    private CompositeMaterialDistribution() {}

    /**
     * Demand per destination chest.
     *
     * <p>A branch/merge run is stricter than a linear one: the wrapper asserts that the
     * raw chest holds exactly the split's units and every branch source holds exactly
     * its own installation escrow. That falls out rather than being special-cased,
     * because a stage whose inputs all arrive over an edge has no external demand
     * left.</p>
     */
    static Map<BlockPos3i, Map<ResourceId, Long>> demand(
            CompositePlayerOrderSpecV1 spec,
            CompositeSiteLayout layout,
            Map<ResourceId, Map<ResourceId, Long>> installationMaterialsByNode) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(installationMaterialsByNode, "installationMaterialsByNode");
        Map<BlockPos3i, Map<ResourceId, Long>> demand = new LinkedHashMap<>();
        layout.split().ifPresent(cells -> demand.put(cells.rawSource(),
                new LinkedHashMap<>(spec.rootSplit().orElseThrow().distributed())));
        for (CompositePlayerOrderSpecV1.StageSpec stage : spec.stages()) {
            Map<ResourceId, Long> wanted = new LinkedHashMap<>();
            externalInputs(spec, stage).forEach((resource, quantity) ->
                    wanted.merge(resource, quantity, Math::addExact));
            installationMaterialsByNode.getOrDefault(stage.nodeId(), Map.of())
                    .forEach((resource, quantity) -> wanted.merge(resource, quantity, Math::addExact));
            demand.put(layout.stage(stage.nodeId()).source(), wanted);
        }
        return demand;
    }

    /** A stage's process inputs less whatever an incoming edge physically delivers. */
    static Map<ResourceId, Long> externalInputs(
            CompositePlayerOrderSpecV1 spec, CompositePlayerOrderSpecV1.StageSpec stage) {
        Map<ResourceId, Long> delivered = new LinkedHashMap<>();
        spec.graph().incoming(stage.nodeId()).forEach(edge ->
                delivered.merge(edge.resourceId(), edge.quantity(), Math::addExact));
        Map<ResourceId, Long> external = new LinkedHashMap<>();
        stage.processInputs().forEach((resource, quantity) -> {
            long remaining = quantity - delivered.getOrDefault(resource, 0L);
            if (remaining > 0) external.put(resource, remaining);
        });
        return Map.copyOf(external);
    }

    /**
     * The order the split's raw units must occupy in the chest, first slot first.
     *
     * <p>A vanilla hopper pulls by slot, not by type, so this ordering — not the
     * distribution logic — is what decides which branch is served first. Withdrawal order
     * is alphabetical by item id, which put {@code create:shaft} ahead of the log the
     * facing branch needed and routed it to the wrong branch. The wrapper arguments and
     * the physical slot order are both derived from this list so they cannot disagree.
     */
    static List<CompositeProductionGraph.MaterialEdge> splitDrawOrder(
            CompositePlayerOrderSpecV1 spec, CompositeSiteLayout.SplitCells cells) {
        List<CompositeProductionGraph.MaterialEdge> outgoing =
                spec.graph().outgoing(cells.nodeId());
        List<CompositeProductionGraph.MaterialEdge> facing = outgoing.stream()
                .filter(edge -> edge.consumerNodeId().equals(cells.facingBranchNode())).toList();
        List<CompositeProductionGraph.MaterialEdge> adjacent = outgoing.stream()
                .filter(edge -> edge.consumerNodeId().equals(cells.adjacentBranchNode()))
                .sorted(Comparator.comparing(edge -> edge.resourceId().toString())).toList();
        if (facing.size() != 1 || adjacent.size() != 2) {
            throw new IllegalStateException("branch/merge split must carry one and two raw units");
        }
        return List.of(facing.get(0), adjacent.get(0), adjacent.get(1));
    }
}
