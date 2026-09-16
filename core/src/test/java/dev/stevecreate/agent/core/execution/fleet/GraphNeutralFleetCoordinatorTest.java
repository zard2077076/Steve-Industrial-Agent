package dev.stevecreate.agent.core.execution.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotInventory;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.BotWorkerStatus;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Command;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Outcome;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ReconciliationEvidence;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Snapshot;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Update;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GraphNeutralFleetCoordinatorTest {
    private static final ResourceId DISPATCHER = id("fleet:test_dispatcher");
    private static final ResourceId RECOVERY = id("fleet:authoritative_rescan");

    @Test
    void threeAndFiveWorkersUseTheSameBoundedAssignmentKernel() {
        for (int workerCount : List.of(3, 5)) {
            Fixture fixture = fixture(workerCount, false, false);
            var assigned = fixture.coordinator.tick(0);
            assertThat(assigned.activeAssignments()).hasSize(workerCount);
            assertThat(assigned.newlyAssignedTaskIds()).hasSize(workerCount);
            assertThat(assigned.activeAssignments().values())
                    .extracting(Assignment::workerId).doesNotHaveDuplicates();
            assertThat(assigned.workLeases().values())
                    .allMatch(lease -> lease.activeAt(0));

            var completed = fixture.coordinator.tick(1);
            assertThat(completed.completedUpdates()).hasSize(workerCount);
            assertThat(completed.activeAssignments()).isEmpty();
            assertThat(completed.workLeases().values())
                    .allMatch(lease -> !lease.activeAt(1));
            assertThat(fixture.workers).allSatisfy(worker -> {
                assertThat(worker.executeCalls).hasValue(1);
                assertThat(worker.status).isEqualTo(BotWorkerStatus.IDLE);
            });
        }
    }

    @Test
    void exclusiveCellsPreventOverlapAndFleetBoundsFailClosed() {
        Fixture fixture = fixture(3, true, false);
        var first = fixture.coordinator.tick(10);
        assertThat(first.activeAssignments()).hasSize(1);
        assertThat(first.workLeases().values().stream()
                .filter(lease -> lease.activeAt(10))).hasSize(1);

        var second = fixture.coordinator.tick(11);
        assertThat(second.completedUpdates()).hasSize(1);
        assertThat(second.activeAssignments()).hasSize(1);
        assertThat(second.workLeases().values().stream()
                .filter(lease -> lease.activeAt(11))).hasSize(1);

        assertThatThrownBy(() -> fixture(1, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 5");
        assertThatThrownBy(() -> fixture(6, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 5");
    }

    @Test
    void reloadPreservesExactAssignmentLeaseAndRequiresBoundRescanBeforeReassignment() {
        Fixture fixture = fixture(3, false, true);
        Assignment prior = fixture.coordinator.tick(20).activeAssignments()
                .get(id("fleet:task_0"));
        fixture.coordinator.tick(21);
        Snapshot snapshot = fixture.coordinator.snapshot(22);
        assertThat(snapshot.reconciliationRequiredTaskIds()).contains(prior.taskId());
        assertThat(snapshot.recoveryAssignments().get(prior.taskId())).isEqualTo(prior);

        List<FixtureWorker> restoredWorkers = workers(3);
        restoredWorkers.get(0).status = BotWorkerStatus.OFFLINE;
        GraphNeutralFleetCoordinator<DomainGraph, DomainTask> restored =
                GraphNeutralFleetCoordinator.restore(
                        snapshot, fixture.graph, ADAPTER,
                        dispatcher(restoredWorkers, false), DISPATCHER,
                        restoredWorkers, fixture.positions,
                        AssignmentPolicy.LOWEST_WORKER_ID, new RetryBudget(2, 0),
                        new WorkerHealthPolicy(1, true), 100);
        Snapshot restoredSnapshot = restored.snapshot(23);
        assertThat(restoredSnapshot.recoveryAssignments().get(prior.taskId())).isEqualTo(prior);
        assertThat(restoredSnapshot.workLeases().values())
                .filteredOn(lease -> lease.taskId().equals(prior.taskId()))
                .allMatch(lease -> lease.status()
                        == GraphNeutralFleetCoordinator.LeaseStatus.RECONCILIATION_REQUIRED);
        assertThat(restored.tick(24).newlyAssignedTaskIds()).doesNotContain(prior.taskId());

        ReconciliationEvidence wrong = evidence(prior, RECOVERY, 24, false);
        assertThat(restored.recordReconciliation(prior.taskId(), List.of(wrong), 24)).isFalse();
        ReconciliationEvidence exact = evidence(prior, RECOVERY, 25, true);
        assertThat(restored.recordReconciliation(prior.taskId(), List.of(exact), 25)).isTrue();
        Assignment reassigned = restored.tick(26).activeAssignments().get(prior.taskId());
        assertThat(reassigned.workerId()).isNotEqualTo(prior.workerId());
        assertThat(reassigned.attempt()).isEqualTo(2);
    }

    @Test
    void reloadBeforeFirstDispatcherUpdateStillRequiresExactBoundReconciliation() {
        Fixture fixture = fixture(3, false, true);
        Assignment prior = fixture.coordinator.tick(30).activeAssignments()
                .get(id("fleet:task_0"));

        Snapshot snapshot = fixture.coordinator.snapshot(30);

        assertThat(snapshot.reconciliationRequiredTaskIds()).contains(prior.taskId());
        assertThat(snapshot.recoveryAssignments().get(prior.taskId())).isEqualTo(prior);
        assertThat(snapshot.latestUpdates().get(prior.taskId()).failureCode())
                .contains(GraphNeutralFleetCoordinator.RELOAD_INTERRUPTED);
    }

    private static Fixture fixture(int workerCount, boolean sharedCell, boolean failFirst) {
        List<DomainTask> tasks = new ArrayList<>();
        Map<ResourceId, BlockPos3i> positions = new LinkedHashMap<>();
        for (int index = 0; index < Math.max(1, workerCount); index++) {
            ResourceId taskId = id("fleet:task_" + index);
            ResourceId leaseId = id("fleet:lease_" + index);
            tasks.add(new DomainTask(taskId, Set.of(), Set.of(leaseId), index,
                    2, failFirst && index == 0));
            positions.put(leaseId, sharedCell
                    ? new BlockPos3i(0, 64, 0) : new BlockPos3i(index, 64, 0));
        }
        DomainGraph graph = new DomainGraph(id("fleet:graph_" + workerCount + "_"
                + sharedCell + "_" + failFirst), tasks);
        List<FixtureWorker> workers = workers(workerCount);
        GraphNeutralFleetCoordinator<DomainGraph, DomainTask> coordinator =
                new GraphNeutralFleetCoordinator<>(
                        id("fleet:session_" + workerCount + "_" + sharedCell + "_" + failFirst),
                        graph, ADAPTER, dispatcher(workers, failFirst), DISPATCHER, workers,
                        positions, AssignmentPolicy.LOWEST_WORKER_ID, new RetryBudget(2, 0),
                        new WorkerHealthPolicy(1, true), 100);
        return new Fixture(graph, positions, workers, coordinator);
    }

    private static List<FixtureWorker> workers(int count) {
        List<FixtureWorker> workers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            workers.add(new FixtureWorker(id("fleet:worker_" + index)));
        }
        return workers;
    }

    private static FleetTaskDispatcher<DomainGraph, DomainTask> dispatcher(
            List<FixtureWorker> workers, boolean failFirst) {
        Map<ResourceId, FixtureWorker> indexed = workers.stream().collect(
                java.util.stream.Collectors.toMap(
                        worker -> worker.workerId, worker -> worker,
                        (left, right) -> left, LinkedHashMap::new));
        return (graph, task, assignment, context) -> {
            FixtureWorker worker = indexed.get(assignment.workerId());
            worker.executeCalls.incrementAndGet();
            if (context.command() == Command.CANCEL) {
                worker.idle();
                return update(task, assignment, context, Outcome.CANCELLED, Optional.empty());
            }
            if (failFirst && task.failFirst() && assignment.attempt() == 1) {
                worker.busy(assignment);
                return update(task, assignment, context, Outcome.PENDING, Optional.empty());
            }
            worker.idle();
            return update(task, assignment, context, Outcome.SUCCEEDED, Optional.empty());
        };
    }

    private static Update update(
            DomainTask task, Assignment assignment, ExecutionContext context,
            Outcome outcome, Optional<ResourceId> failure) {
        return new Update(
                task.taskId(), assignment.assignmentId(), assignment.workerId(), DISPATCHER,
                context.currentTick(), outcome, failure, Set.of(id("fleet:evidence")),
                Map.of(id("fleet:domain"), "fixture"), "fixture fleet update");
    }

    private static ReconciliationEvidence evidence(
            Assignment assignment, ResourceId requirement, long tick, boolean passed) {
        return new ReconciliationEvidence(
                requirement, assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.assignmentId(), assignment.workerId(), tick, passed,
                Map.of(id("fleet:state"), passed ? "reconciled" : "stale"),
                "fixture:authoritative-rescan");
    }

    private static final FleetTaskAdapter<DomainGraph, DomainTask> ADAPTER =
            new FleetTaskAdapter<>() {
                @Override public ResourceId graphId(DomainGraph graph) { return graph.graphId(); }
                @Override public String graphFingerprint(DomainGraph graph) {
                    return "a".repeat(64);
                }
                @Override public Map<ResourceId, DomainTask> tasks(DomainGraph graph) {
                    return graph.tasks().stream().collect(java.util.stream.Collectors.toMap(
                            DomainTask::taskId, task -> task, (left, right) -> left,
                            LinkedHashMap::new));
                }
                @Override public ResourceId taskId(DomainTask task) { return task.taskId(); }
                @Override public Set<ResourceId> predecessorTaskIds(
                        DomainGraph graph, DomainTask task) { return task.predecessors(); }
                @Override public Set<ResourceId> requiredWorkLeaseIds(
                        DomainGraph graph, DomainTask task) { return task.leaseIds(); }
                @Override public Set<BotWorkerCapability> requiredCapabilities(
                        DomainGraph graph, DomainTask task) {
                    return EnumSet.of(BotWorkerCapability.REGISTRATION);
                }
                @Override public int priority(DomainGraph graph, DomainTask task) {
                    return task.priority();
                }
                @Override public int maximumAttempts(DomainGraph graph, DomainTask task) {
                    return task.maximumAttempts();
                }
                @Override public boolean allowsReassignment(
                        DomainGraph graph, DomainTask task, ResourceId failureCode) {
                    return task.failFirst()
                            && failureCode.equals(GraphNeutralFleetCoordinator.RELOAD_INTERRUPTED);
                }
                @Override public Set<ResourceId> requiredReconciliationEvidenceIds(
                        DomainGraph graph, DomainTask task) {
                    return task.failFirst() ? Set.of(RECOVERY) : Set.of();
                }
            };

    private record DomainGraph(ResourceId graphId, List<DomainTask> tasks) {
        private DomainGraph { tasks = List.copyOf(tasks); }
    }

    private record DomainTask(
            ResourceId taskId,
            Set<ResourceId> predecessors,
            Set<ResourceId> leaseIds,
            int priority,
            int maximumAttempts,
            boolean failFirst) {}

    private record Fixture(
            DomainGraph graph,
            Map<ResourceId, BlockPos3i> positions,
            List<FixtureWorker> workers,
            GraphNeutralFleetCoordinator<DomainGraph, DomainTask> coordinator) {}

    private static final class FixtureWorker implements FleetWorker {
        private final ResourceId workerId;
        private final AtomicInteger executeCalls = new AtomicInteger();
        private BotWorkerStatus status = BotWorkerStatus.IDLE;
        private Assignment assignment;

        private FixtureWorker(ResourceId workerId) { this.workerId = workerId; }

        @Override public ResourceId workerId() { return workerId; }

        @Override
        public BotWorkerSnapshot snapshot(long currentTick) {
            boolean busy = status == BotWorkerStatus.BUSY;
            return new BotWorkerSnapshot(
                    workerId, status, new BlockPos3i(0, 64, 0), id("fleet:test_region"),
                    EnumSet.allOf(BotWorkerCapability.class),
                    busy ? Optional.of(assignment.sessionId()) : Optional.empty(),
                    busy ? Optional.of(assignment.taskId()) : Optional.empty(),
                    busy ? Optional.of(assignment.assignmentId()) : Optional.empty(),
                    new BotInventory(workerId, 64, Map.of(), 0, currentTick),
                    status == BotWorkerStatus.DEAD ? 0 : 20,
                    status != BotWorkerStatus.OFFLINE, true, executeCalls.get(), currentTick);
        }

        private void idle() {
            status = BotWorkerStatus.IDLE;
            clearAssignment();
        }

        private void busy(Assignment value) {
            status = BotWorkerStatus.BUSY;
            assignment = value;
        }

        private void clearAssignment() { assignment = null; }
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
