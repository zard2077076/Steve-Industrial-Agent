package dev.stevecreate.agent.core.siteprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotInventory;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.BotWorkerStatus;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.execution.fleet.FleetWorker;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ReconciliationEvidence;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerrainPreparationFleetAdapterTest {
    private static final String PLAN_HASH = "1".repeat(64);
    private static final String SITE_HASH = "2".repeat(64);
    private static final String STATE_HASH = "3".repeat(64);
    private static final ResourceId SESSION = id("site-prep:test_session");

    @Test
    void typedTerrainAdapterUsesSharedFleetAndPreservesCollectDeliverContinuity() {
        TerrainPreparationPlan plan = plan(false);
        TerrainPreparationFleetTaskAdapter adapter = new TerrainPreparationFleetTaskAdapter();
        RecordingExecutor executor = new RecordingExecutor(false);
        List<FixtureWorker> workers = workers(3);
        GraphNeutralFleetCoordinator<TerrainPreparationTaskGraph, TerrainPreparationTask> fleet =
                fleet(plan, adapter, executor, workers);

        fleet.tick(0);
        fleet.tick(1);
        fleet.tick(2);
        fleet.tick(3);
        var complete = fleet.tick(4);

        ResourceId mineWorker = executor.workers.get(id("site-task:mine-0"));
        assertThat(executor.workers.get(id("site-task:collect-0"))).isEqualTo(mineWorker);
        assertThat(executor.workers.get(id("site-task:deliver-0"))).isEqualTo(mineWorker);
        assertThat(complete.completedUpdates()).hasSize(4);
        assertThat(complete.terminalUpdates()).isEmpty();
        assertThat(adapter.requiredCapabilities(plan.taskGraph(),
                plan.taskGraph().tasks().get(0)))
                .contains(BotWorkerCapability.REMOVE_APPROVED_TERRAIN_BLOCK)
                .doesNotContain(BotWorkerCapability.PLACE_BLOCK);
        assertThat(adapter.graphFingerprint(plan.taskGraph())).matches("[0-9a-f]{64}");
    }

    @Test
    void reloadCannotReassignUntilExactTerrainRescanEvidenceIsBound() {
        TerrainPreparationPlan plan = plan(true);
        TerrainPreparationFleetTaskAdapter adapter = new TerrainPreparationFleetTaskAdapter();
        RecordingExecutor pending = new RecordingExecutor(true);
        List<FixtureWorker> initialWorkers = workers(3);
        GraphNeutralFleetCoordinator<TerrainPreparationTaskGraph, TerrainPreparationTask> initial =
                fleet(plan, adapter, pending, initialWorkers);
        Assignment original = initial.tick(10).activeAssignments().values().iterator().next();
        initial.tick(11);
        var snapshot = initial.snapshot(12);

        List<FixtureWorker> restoredWorkers = workers(3);
        restoredWorkers.get(0).offline = true;
        RecordingExecutor resumed = new RecordingExecutor(false);
        var restored = GraphNeutralFleetCoordinator.restore(
                snapshot, plan.taskGraph(), adapter,
                new TerrainPreparationFleetDispatcher(plan, resumed, adapter),
                TerrainPreparationFleetDispatcher.DISPATCHER_ID,
                restoredWorkers, adapter.workPositions(plan.taskGraph()),
                AssignmentPolicy.LOWEST_WORKER_ID, new RetryBudget(2, 0),
                new WorkerHealthPolicy(1, true), 200);
        assertThat(restored.tick(13).newlyAssignedTaskIds()).isEmpty();

        ReconciliationEvidence wrong = evidence(original, 14, false, SITE_HASH);
        assertThat(restored.recordReconciliation(
                original.taskId(), List.of(wrong), 14)).isFalse();
        ReconciliationEvidence exact = evidence(original, 15, true, SITE_HASH);
        assertThat(restored.recordReconciliation(
                original.taskId(), List.of(exact), 15)).isTrue();
        Assignment reassigned = restored.tick(16).activeAssignments().get(original.taskId());
        assertThat(reassigned.workerId()).isNotEqualTo(original.workerId());
        assertThat(reassigned.attempt()).isEqualTo(2);
    }

    @Test
    void terrainGraphCannotForgeContinuityOrReuseAChangedCanonicalGraph() {
        TerrainPreparationPlan plan = plan(false);
        TerrainPreparationFleetTaskAdapter adapter = new TerrainPreparationFleetTaskAdapter();
        TerrainPreparationTask invalidDeliver = new TerrainPreparationTask(
                "site-task:bad-deliver", TerrainPreparationTaskKind.DELIVER_SALVAGE,
                List.of(new BlockPos3i(3, 64, 0)), Set.of("site-task:mine-0"), 0, 100);
        TerrainPreparationTaskGraph invalid = new TerrainPreparationTaskGraph(
                "site-prep:bad-graph", PLAN_HASH, SITE_HASH, "approval:test",
                List.of(plan.taskGraph().tasks().get(0), invalidDeliver), 1);
        assertThatThrownBy(() -> adapter.workerContinuityPredecessorId(
                invalid, invalidDeliver)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same-worker predecessor");

        TerrainPreparationTask moved = new TerrainPreparationTask(
                "site-task:mine-0", TerrainPreparationTaskKind.MINE_AUTHORIZED_BLOCK,
                List.of(new BlockPos3i(99, 64, 0)), Set.of(), 1, 100);
        TerrainPreparationTaskGraph changed = new TerrainPreparationTaskGraph(
                plan.taskGraph().graphIdentity(), PLAN_HASH, SITE_HASH, "approval:test",
                List.of(moved), 1);
        assertThat(adapter.graphFingerprint(changed))
                .isNotEqualTo(adapter.graphFingerprint(plan.taskGraph()));
    }

    private static GraphNeutralFleetCoordinator<TerrainPreparationTaskGraph,
            TerrainPreparationTask> fleet(
            TerrainPreparationPlan plan,
            TerrainPreparationFleetTaskAdapter adapter,
            BotClearingExecutor executor,
            List<FixtureWorker> workers) {
        return new GraphNeutralFleetCoordinator<>(
                SESSION, plan.taskGraph(), adapter,
                new TerrainPreparationFleetDispatcher(plan, executor, adapter),
                TerrainPreparationFleetDispatcher.DISPATCHER_ID,
                workers, adapter.workPositions(plan.taskGraph()),
                AssignmentPolicy.LOWEST_WORKER_ID, new RetryBudget(2, 0),
                new WorkerHealthPolicy(1, true), 200);
    }

    private static TerrainPreparationPlan plan(boolean onlyMine) {
        BlockPos3i target = new BlockPos3i(1, 64, 0);
        BlockPos3i salvage = new BlockPos3i(3, 64, 0);
        TerrainPreparationTask mine = new TerrainPreparationTask(
                "site-task:mine-0", TerrainPreparationTaskKind.MINE_AUTHORIZED_BLOCK,
                List.of(target), Set.of(), 1, 100);
        List<TerrainPreparationTask> tasks = new ArrayList<>();
        tasks.add(mine);
        if (!onlyMine) {
            TerrainPreparationTask collect = new TerrainPreparationTask(
                    "site-task:collect-0", TerrainPreparationTaskKind.COLLECT_DROPS,
                    List.of(target), Set.of(mine.taskIdentity()), 0, 100);
            TerrainPreparationTask deliver = new TerrainPreparationTask(
                    "site-task:deliver-0", TerrainPreparationTaskKind.DELIVER_SALVAGE,
                    List.of(salvage), Set.of(collect.taskIdentity()), 0, 100);
            TerrainPreparationTask verify = new TerrainPreparationTask(
                    "site-task:verify", TerrainPreparationTaskKind.VERIFY_GROUND,
                    List.of(target), Set.of(deliver.taskIdentity()), 0, 100);
            tasks.addAll(List.of(collect, deliver, verify));
        }
        TerrainPreparationTaskGraph graph = new TerrainPreparationTaskGraph(
                "site-prep:test-graph", PLAN_HASH, SITE_HASH, "approval:test", tasks, 1);
        ApprovedObstacle obstacle = new ApprovedObstacle(
                "obstacle:test", target, STATE_HASH,
                ObstacleClassification.SAFE_NATURAL_CLEARABLE);
        DemolitionApprovalToken approval = new DemolitionApprovalToken(
                "approval:test", "world:test", id("minecraft:overworld"), PLAN_HASH,
                SITE_HASH, "4".repeat(64), "player:test", List.of(obstacle),
                Instant.parse("2026-07-27T00:00:00Z"),
                Instant.parse("2026-07-27T01:00:00Z"), 1,
                DemolitionApprovalState.ACTIVE);
        return new TerrainPreparationPlan(
                "terrain-plan:test", approval, graph, GroundLevelingPolicy.conservative(),
                "salvage:test", List.of("exact-approval-only"));
    }

    private static ReconciliationEvidence evidence(
            Assignment assignment, long tick, boolean passed, String siteHash) {
        return new ReconciliationEvidence(
                TerrainPreparationFleetTaskAdapter.AUTHORITATIVE_TERRAIN_RESCAN,
                assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.assignmentId(), assignment.workerId(), tick, passed,
                Map.of(id("site-prep:site_snapshot_hash"), siteHash,
                        id("site-prep:world_state"), passed ? "exact" : "changed"),
                "site-prep:authoritative-server-rescan");
    }

    private static List<FixtureWorker> workers(int count) {
        List<FixtureWorker> workers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            workers.add(new FixtureWorker(id("site-prep:worker-" + index)));
        }
        return workers;
    }

    private static final class RecordingExecutor implements BotClearingExecutor {
        private final boolean pending;
        private final Map<ResourceId, ResourceId> workers = new LinkedHashMap<>();

        private RecordingExecutor(boolean pending) { this.pending = pending; }

        @Override
        public BotClearingUpdate execute(
                TerrainPreparationPlan plan,
                TerrainPreparationTask task,
                Assignment assignment,
                GraphNeutralFleetCoordinator.ExecutionContext context) {
            workers.put(ResourceId.parse(task.taskIdentity()), assignment.workerId());
            BotClearingState state = context.command()
                    == GraphNeutralFleetCoordinator.Command.CANCEL
                    ? BotClearingState.CANCELLED
                    : pending ? BotClearingState.RUNNING : BotClearingState.COMPLETED;
            return new BotClearingUpdate(
                    state, state == BotClearingState.COMPLETED ? 1 : 0,
                    task.maximumMutations(), new SalvageLedger(
                    "ledger:test", "salvage:test", List.of()), List.of(), Optional.empty(),
                    Set.of(id("site-prep:task-state")),
                    Map.of(id("site-prep:task-kind"), task.kind().name()),
                    "typed terrain fixture update");
        }
    }

    private static final class FixtureWorker implements FleetWorker {
        private final ResourceId workerId;
        private boolean offline;

        private FixtureWorker(ResourceId workerId) { this.workerId = workerId; }
        @Override public ResourceId workerId() { return workerId; }

        @Override
        public BotWorkerSnapshot snapshot(long tick) {
            return new BotWorkerSnapshot(
                    workerId, offline ? BotWorkerStatus.OFFLINE : BotWorkerStatus.IDLE,
                    new BlockPos3i(0, 64, 0), id("site-prep:test-region"),
                    EnumSet.allOf(BotWorkerCapability.class), Optional.empty(), Optional.empty(),
                    Optional.empty(), new BotInventory(workerId, 64, Map.of(), 0, tick),
                    20, !offline, true, 0, tick);
        }
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
