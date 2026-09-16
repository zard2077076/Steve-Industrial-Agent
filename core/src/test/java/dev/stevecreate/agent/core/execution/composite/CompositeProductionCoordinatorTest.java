package dev.stevecreate.agent.core.execution.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionCoordinator.Failure;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionCoordinator.NodeStatus;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.MaterialEdge;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Node;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph.Shape;
import dev.stevecreate.agent.core.execution.construction.ReservationStatus;
import dev.stevecreate.agent.core.execution.construction.SharedInfrastructureReservation;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompositeProductionCoordinatorTest {
    private static final ResourceId LINE = id("line:one");
    private static final ResourceId POWER = id("infrastructure:shared_power");

    @Test
    void linearThreeStageMovesExactIntermediatesWithoutReplayAfterReload() {
        CompositeProductionGraph graph = linearGraph();
        CompositeProductionCoordinator coordinator = new CompositeProductionCoordinator(graph);

        assertThat(coordinator.readyNodes()).containsExactly(id("node:cut_log"));
        assertThat(coordinator.start(id("node:cut_log")).accepted()).isTrue();
        assertThat(coordinator.complete(
                id("node:cut_log"), Map.of(id("minecraft:stripped_oak_log"), 1L)).accepted())
                .isTrue();
        assertThat(coordinator.readyNodes()).containsExactly(id("node:cut_planks"));
        assertThat(coordinator.start(id("node:cut_planks")).accepted()).isTrue();
        assertThat(coordinator.bufferQuantities()).containsEntry(id("buffer:stripped_log"), 0L);

        CompositeProductionCoordinator restored = CompositeProductionCoordinator.restore(
                graph, coordinator.snapshot());
        assertThat(restored.statuses())
                .containsEntry(id("node:cut_planks"), NodeStatus.RECOVERY_REQUIRED);
        assertThat(restored.complete(
                id("node:cut_planks"), Map.of(id("minecraft:oak_planks"), 6L)).failure())
                .isEqualTo(Failure.NOT_RUNNING);
        CompositeProductionCoordinator.RecoveryEvidence wrongReservation =
                new CompositeProductionCoordinator.RecoveryEvidence(
                        graph.graphId(), graph.fingerprint(), id("node:cut_planks"),
                        coordinator.snapshot().generation(), true, true,
                        Map.of(id("buffer:stripped_log"), 2L),
                        "fixture:wrong-buffer-quantity");
        assertThat(restored.reconcileRunningNode(
                id("node:cut_planks"), wrongReservation).failure())
                .isEqualTo(Failure.RECOVERY_EVIDENCE_MISMATCH);
        CompositeProductionCoordinator.RecoveryEvidence evidence =
                new CompositeProductionCoordinator.RecoveryEvidence(
                        graph.graphId(), graph.fingerprint(), id("node:cut_planks"),
                        coordinator.snapshot().generation(), true, true,
                        Map.of(id("buffer:stripped_log"), 1L),
                        "fixture:exact-handler-and-buffer-rescan");
        assertThat(restored.reconcileRunningNode(id("node:cut_planks"), evidence).accepted())
                .isTrue();
        assertThat(restored.complete(
                id("node:cut_planks"), Map.of(id("minecraft:oak_planks"), 6L)).accepted()).isTrue();
        assertThat(restored.readyNodes()).containsExactly(id("node:deploy_cogwheel"));
        assertThat(restored.start(id("node:deploy_cogwheel")).accepted()).isTrue();
        assertThat(restored.bufferQuantities())
                .containsEntry(id("buffer:stripped_log"), 0L)
                .containsEntry(id("buffer:planks"), 0L);
        assertThat(restored.complete(
                id("node:deploy_cogwheel"), Map.of(id("create:cogwheel"), 1L)).accepted()).isTrue();
        assertThat(restored.statuses().values()).containsOnly(NodeStatus.SUCCEEDED);
    }

    @Test
    void bufferBackpressureAndContaminationFailClosedWithTypedResults() {
        CompositeProductionGraph graph = linearGraph();
        CompositeProductionCoordinator coordinator = new CompositeProductionCoordinator(graph);
        assertThat(coordinator.observeBuffer(
                id("buffer:stripped_log"), id("minecraft:dirt"), 1).failure())
                .isEqualTo(Failure.CONTAMINATION);
        assertThat(coordinator.readyNodes()).containsExactly(id("node:cut_log"));
        coordinator.start(id("node:cut_log"));
        assertThat(coordinator.complete(
                id("node:cut_log"), Map.of(id("minecraft:stripped_oak_log"), 1L)).failure())
                .isEqualTo(Failure.CONTAMINATION);
        assertThat(coordinator.readyNodes()).isEmpty();

        assertThat(coordinator.observeBuffer(
                id("buffer:stripped_log"), id("minecraft:stripped_oak_log"), 1).accepted()).isTrue();
        assertThat(coordinator.complete(
                id("node:cut_log"), Map.of(id("minecraft:stripped_oak_log"), 1L)).failure())
                .isEqualTo(Failure.BACKPRESSURE);
        assertThat(coordinator.observeBuffer(
                id("buffer:stripped_log"), id("minecraft:stripped_oak_log"), 0).accepted()).isTrue();
        assertThat(coordinator.complete(
                id("node:cut_log"), Map.of(id("minecraft:stripped_oak_log"), 1L)).accepted())
                .isTrue();
        assertThat(coordinator.readyNodes()).containsExactly(id("node:cut_planks"));
        assertThat(coordinator.observeBuffer(
                id("buffer:stripped_log"), id("minecraft:stripped_oak_log"), 2).failure())
                .isEqualTo(Failure.BACKPRESSURE);
    }

    @Test
    void cancellingRunningConsumerReturnsItsExactIntermediateReservation() {
        CompositeProductionGraph graph = linearGraph();
        CompositeProductionCoordinator coordinator = new CompositeProductionCoordinator(graph);
        coordinator.start(id("node:cut_log"));
        coordinator.complete(
                id("node:cut_log"), Map.of(id("minecraft:stripped_oak_log"), 1L));
        coordinator.start(id("node:cut_planks"));
        assertThat(coordinator.bufferQuantities()).containsEntry(id("buffer:stripped_log"), 0L);

        assertThat(coordinator.cancelLine(LINE)).contains(id("node:cut_planks"));
        assertThat(coordinator.bufferQuantities()).containsEntry(id("buffer:stripped_log"), 1L);
    }

    @Test
    void independentBranchFailureDoesNotRunMergeOrStopHealthyBranch() {
        CompositeProductionGraph graph = branchMergeGraph();
        CompositeProductionCoordinator coordinator = new CompositeProductionCoordinator(graph);
        coordinator.start(id("node:raw_split"));
        coordinator.complete(id("node:raw_split"), Map.of(
                id("minecraft:oak_log"), 1L,
                id("minecraft:andesite"), 1L));

        assertThat(coordinator.readyNodes())
                .containsExactly(id("node:alloy_branch"), id("node:wood_branch"));
        coordinator.start(id("node:wood_branch"));
        coordinator.start(id("node:alloy_branch"));
        coordinator.fail(id("node:wood_branch"), "saw obstruction");
        assertThat(coordinator.complete(
                id("node:alloy_branch"), Map.of(id("create:andesite_alloy"), 1L)).accepted())
                .isTrue();
        assertThat(coordinator.statuses())
                .containsEntry(id("node:wood_branch"), NodeStatus.FAILED)
                .containsEntry(id("node:alloy_branch"), NodeStatus.SUCCEEDED)
                .containsEntry(id("node:merge"), NodeStatus.WAITING);
        assertThat(coordinator.readyNodes()).isEmpty();
    }

    @Test
    void concurrentLineCancelAndReferenceCountPreserveOtherLineInfrastructure() {
        CompositeProductionGraph graph = concurrentGraph();
        CompositeProductionCoordinator coordinator = new CompositeProductionCoordinator(graph);
        assertThat(coordinator.readyNodes())
                .containsExactly(id("node:line_a"), id("node:line_b"));
        coordinator.start(id("node:line_a"));
        coordinator.start(id("node:line_b"));
        assertThat(coordinator.cancelLine(id("line:a"))).containsExactly(id("node:line_a"));
        assertThat(coordinator.statuses())
                .containsEntry(id("node:line_a"), NodeStatus.CANCELLED)
                .containsEntry(id("node:line_b"), NodeStatus.RUNNING);

        CompositeProductionSnapshot wrapperSnapshot = coordinator.snapshot();
        CompositeProductionCoordinator restored = CompositeProductionCoordinator.restore(
                graph, wrapperSnapshot);
        assertThat(restored.statuses())
                .containsEntry(id("node:line_a"), NodeStatus.CANCELLED)
                .containsEntry(id("node:line_b"), NodeStatus.RECOVERY_REQUIRED);
        CompositeProductionCoordinator.RecoveryEvidence wrong =
                new CompositeProductionCoordinator.RecoveryEvidence(
                        graph.graphId(), graph.fingerprint(), id("node:line_b"),
                        wrapperSnapshot.generation() + 1, true, true, Map.of(),
                        "fixture:wrong-snapshot-generation");
        assertThat(restored.reconcileRunningNode(id("node:line_b"), wrong).failure())
                .isEqualTo(Failure.RECOVERY_EVIDENCE_MISMATCH);
        CompositeProductionCoordinator.RecoveryEvidence exact =
                new CompositeProductionCoordinator.RecoveryEvidence(
                        graph.graphId(), graph.fingerprint(), id("node:line_b"),
                        wrapperSnapshot.generation(), true, true, Map.of(),
                        "fixture:exact-concurrent-wrapper-rescan");
        assertThat(restored.reconcileRunningNode(id("node:line_b"), exact).accepted())
                .isTrue();
        assertThat(restored.complete(id("node:line_b"), Map.of()).accepted()).isTrue();

        SharedInfrastructureReservation initial = new SharedInfrastructureReservation(
                id("reservation:power"), id("plan:composite_02"), POWER,
                Set.of(id("session:a")), List.of(new BlockPos3i(1, 64, 1)),
                ReservationStatus.ACTIVE, 0, 10);
        SharedInfrastructureLeaseRegistry leases = new SharedInfrastructureLeaseRegistry(initial);
        assertThat(leases.acquire(id("reservation:power"), id("session:b"), 11).referenceCount())
                .isEqualTo(2);
        SharedInfrastructureLeaseRegistry.ReleaseResult releaseA =
                leases.release(id("reservation:power"), id("session:a"), 12);
        assertThat(releaseA.cleanupAllowed()).isFalse();
        assertThat(releaseA.reservation().status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(releaseA.reservation().owningSessionIds()).containsExactly(id("session:b"));
        SharedInfrastructureLeaseRegistry.ReleaseResult releaseB =
                leases.release(id("reservation:power"), id("session:b"), 13);
        assertThat(releaseB.cleanupAllowed()).isTrue();
        assertThat(releaseB.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
    }

    /**
     * Resuming a chain part-way hands the executor the stages that are left, so the
     * suffix has to stand on its own as a graph — same rules, no partial-run exemption.
     */
    @Test
    void aSuffixOfALinearChainIsItselfALinearChain() {
        CompositeProductionGraph graph = new CompositeProductionGraph(
                id("graph:three"), Shape.LINEAR_CHAIN,
                List.of(node("node:a", "capability:one", "line:one"),
                        node("node:b", "capability:two", "line:one"),
                        node("node:c", "capability:three", "line:one")),
                List.of(edge("edge:a_b", "node:a", "node:b", "minecraft:stone", 1, 1),
                        edge("edge:b_c", "node:b", "node:c", "minecraft:stone", 1, 1)));

        CompositeProductionGraph suffix =
                graph.retaining(List.of(id("node:b"), id("node:c")));

        assertThat(suffix.nodes()).hasSize(2);
        assertThat(suffix.edges()).hasSize(1);
        // The edge into the dropped stage goes with it; keeping it would name a producer
        // the graph no longer has, which the constructor refuses anyway.
        assertThat(suffix.edges().get(0).edgeId()).isEqualTo(id("edge:b_c"));
        // A different chain is a different fingerprint. Resumed evidence must not be
        // mistakable for evidence of the whole run.
        assertThat(suffix.fingerprint()).isNotEqualTo(graph.fingerprint());


        // A one-node suffix is a legal graph now — everything a production order needs
        // around a single machine lives on this path, so refusing one stage was refusing
        // eighty-five products the ledger, the carry and unattended dispatch.
        assertThat(graph.retaining(List.of(id("node:c"))).nodes()).hasSize(1);
        assertThatThrownBy(() -> graph.retaining(List.of(id("node:missing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks");
    }

    @Test
    void graphShapeAndReloadIdentityCannotBeWeakened() {
        // A single stage is admitted now, and not because a lone machine is a composite.
        // Everything a production order needs around a machine is built on this path:
        // refusing one stage kept eighty-five single-machine products away from the
        // ledger, the carry, residency, unattended dispatch and restart recovery, all of
        // which a hundred and eleven cutting chains already had.
        new CompositeProductionGraph(
                id("graph:one_node"), Shape.LINEAR_CHAIN,
                List.of(node("node:a", "capability:one", "line:one")),
                List.of());
        // An empty graph is still nothing at all.
        assertThatThrownBy(() -> new CompositeProductionGraph(
                id("graph:empty"), Shape.LINEAR_CHAIN, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // Two stages of the same machine type are accepted. They were not, and the
        // survey showed that rule alone refusing all 113 derivable products: two
        // recipes on one machine type still produce different items and still need the
        // routing between them that makes this a composite.
        new CompositeProductionGraph(
                id("graph:one_capability"), Shape.LINEAR_CHAIN,
                List.of(node("node:a", "capability:one", "line:one"),
                        node("node:b", "capability:one", "line:one")),
                List.of(edge("edge:a_b", "node:a", "node:b", "minecraft:stone", 1, 1)));
        // A branch is still not a chain — the shape rule that did survive.
        assertThatThrownBy(() -> new CompositeProductionGraph(
                id("graph:branched"), Shape.LINEAR_CHAIN,
                List.of(node("node:a", "capability:one", "line:one"),
                        node("node:b", "capability:two", "line:one"),
                        node("node:c", "capability:three", "line:one")),
                List.of(edge("edge:a_b", "node:a", "node:b", "minecraft:stone", 1, 1),
                        edge("edge:a_c", "node:a", "node:c", "minecraft:stone", 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbranched");
        CompositeProductionGraph graph = linearGraph();
        CompositeProductionSnapshot snapshot = new CompositeProductionCoordinator(graph).snapshot();
        CompositeProductionGraph changed = new CompositeProductionGraph(
                id("graph:changed"), graph.shape(), graph.nodes(), graph.edges());
        assertThatThrownBy(() -> CompositeProductionCoordinator.restore(changed, snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
    }

    private static CompositeProductionGraph linearGraph() {
        return new CompositeProductionGraph(
                id("graph:composite_01"), Shape.LINEAR_CHAIN,
                List.of(
                        node("node:cut_log", "capability:create_cutting", "line:one"),
                        node("node:cut_planks", "capability:create_cutting", "line:one"),
                        node("node:deploy_cogwheel", "capability:create_deploying", "line:one")),
                List.of(
                        edge("buffer:stripped_log", "node:cut_log", "node:cut_planks",
                                "minecraft:stripped_oak_log", 1, 1),
                        edge("buffer:planks", "node:cut_planks", "node:deploy_cogwheel",
                                "minecraft:oak_planks", 1, 6)));
    }

    private static CompositeProductionGraph concurrentGraph() {
        Node lineA = new Node(id("node:line_a"), id("capability:create_crushing"),
                id("line:a"), Set.of(POWER));
        Node lineB = new Node(id("node:line_b"), id("capability:create_compacting"),
                id("line:b"), Set.of(POWER));
        return new CompositeProductionGraph(
                id("graph:composite_02"), Shape.CONCURRENT_LINES,
                List.of(lineA, lineB), List.of());
    }

    private static CompositeProductionGraph branchMergeGraph() {
        return new CompositeProductionGraph(
                id("graph:composite_03"), Shape.BRANCH_MERGE,
                List.of(
                        node("node:raw_split", "capability:typed_split", "line:dag"),
                        node("node:wood_branch", "capability:create_cutting", "line:dag"),
                        node("node:alloy_branch", "capability:create_mixing", "line:dag"),
                        node("node:merge", "capability:create_deploying", "line:dag")),
                List.of(
                        edge("buffer:raw_wood", "node:raw_split", "node:wood_branch",
                                "minecraft:oak_log", 1, 1),
                        edge("buffer:raw_andesite", "node:raw_split", "node:alloy_branch",
                                "minecraft:andesite", 1, 1),
                        edge("buffer:stripped_wood", "node:wood_branch", "node:merge",
                                "minecraft:stripped_oak_log", 1, 1),
                        edge("buffer:alloy", "node:alloy_branch", "node:merge",
                                "create:andesite_alloy", 1, 1)));
    }

    private static Node node(String node, String capability, String line) {
        return new Node(id(node), id(capability), id(line), Set.of());
    }

    private static MaterialEdge edge(
            String edge, String producer, String consumer, String resource,
            long quantity, long capacity) {
        return new MaterialEdge(
                id(edge), id(producer), id(consumer), id(resource), quantity, capacity);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
