package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BotFleetExecutorTest {
    private static final ResourceId PLAN = id("plan:verified_bot_fleet");
    private static final String RUNTIME = "bot-runtime-v1";
    private static final ResourceId EXECUTOR = id("executor:bots");
    private static final Set<ResourceId> EXECUTOR_CAPABILITIES = Set.of(
            id("executor:navigation"), id("executor:material_carry"));

    @Test
    void executorRequiresExactWorkerCapabilitiesAndRefusesHighRisk() {
        ConstructionTask normal = task(
                "task:normal_place", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, RetryPolicy.NO_RETRY,
                RecoveryPolicy.REFUSE, "reservation:normal_place", "condition:normal_place");
        ConstructionTask highRisk = task(
                "task:high_risk", TaskKind.SAFE_MACHINE_INTERACTION,
                ConstructionTaskClass.HIGH_RISK_INTERACTION, RetryPolicy.NO_RETRY,
                RecoveryPolicy.REFUSE, "reservation:high_risk", "condition:high_risk");
        ConstructionTaskGraph graph = graph(List.of(normal, highRisk), List.of());
        FixtureWorker incapable = new FixtureWorker(
                id("worker:incapable"), EnumSet.of(BotWorkerCapability.REGISTRATION), Behavior.SUCCESS);
        FixtureWorker healthy = worker("worker:healthy", Behavior.SUCCESS);
        BotFleetExecutor executor = executor(incapable, healthy);

        TaskAssignment incapableAssignment = assignment(graph, normal, incapable.workerId(), 1, 10);
        TaskExecutionResult unsupported = executor.execute(
                graph, normal, incapableAssignment, context(ConstructionExecutionCommand.START, 10));
        assertThat(unsupported.outcome()).isEqualTo(TaskExecutionOutcome.UNSUPPORTED);
        assertThat(unsupported.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id());

        TaskAssignment highRiskAssignment = assignment(graph, highRisk, healthy.workerId(), 1, 10);
        TaskExecutionResult refused = executor.execute(
                graph, highRisk, highRiskAssignment, context(ConstructionExecutionCommand.START, 10));
        assertThat(refused.outcome()).isEqualTo(TaskExecutionOutcome.TERMINAL_FAILURE);
        assertThat(refused.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED.id());
        assertThat(healthy.executeCalls).hasValue(0);
    }

    @Test
    void coordinatorRunsParallelTransportThenSequentialInstallation() {
        ConstructionTask transportA = materialTask("task:transport_a", "condition:transport_a");
        ConstructionTask transportB = materialTask("task:transport_b", "condition:transport_b");
        ConstructionTask place = task(
                "task:place", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, RetryPolicy.NO_RETRY,
                RecoveryPolicy.REFUSE, "reservation:place", "condition:place");
        ConstructionTaskGraph graph = graph(
                List.of(transportA, transportB, place),
                List.of(
                        dependency(transportA, place),
                        dependency(transportB, place)));
        FixtureWorker one = worker("worker:one", Behavior.SUCCESS);
        FixtureWorker two = worker("worker:two", Behavior.SUCCESS);
        BotFleetCoordinator coordinator = coordinator(
                graph, executor(one, two), Map.of(id("reservation:place"), new BlockPos3i(4, 64, 4)));

        BotFleetTickResult scheduledTransport = coordinator.tick(10);
        assertThat(scheduledTransport.newlyAssignedTaskIds())
                .containsExactlyInAnyOrder(transportA.taskId(), transportB.taskId());
        assertThat(scheduledTransport.activeAssignments()).hasSize(2);

        BotFleetTickResult transportCompleteAndPlaceScheduled = coordinator.tick(11);
        assertThat(transportCompleteAndPlaceScheduled.completedResults().keySet())
                .containsExactlyInAnyOrder(transportA.taskId(), transportB.taskId());
        assertThat(transportCompleteAndPlaceScheduled.newlyAssignedTaskIds())
                .containsExactly(place.taskId());
        assertThat(transportCompleteAndPlaceScheduled.workPositionReservations().values())
                .singleElement()
                .satisfies(value -> assertThat(value.status()).isEqualTo(ReservationStatus.ACTIVE));

        BotFleetTickResult completed = coordinator.tick(12);
        assertThat(completed.completedResults().keySet())
                .containsExactlyInAnyOrder(transportA.taskId(), transportB.taskId(), place.taskId());
        assertThat(completed.activeAssignments()).isEmpty();
        assertThat(completed.workPositionReservations().values())
                .singleElement()
                .satisfies(value -> assertThat(value.status()).isEqualTo(ReservationStatus.RELEASED));
        BotFleetCoordinatorSnapshot completedSnapshot = coordinator.snapshot(12);
        assertThat(completedSnapshot.completedWorkerIds().keySet())
                .containsExactlyInAnyOrder(
                        transportA.taskId(), transportB.taskId(), place.taskId());
        assertThat(completedSnapshot.completedWorkerIds().values())
                .allMatch(executor(one, two).workers()::containsKey);
    }

    @Test
    void workerFailureRequiresExactReconciliationBeforeDifferentWorkerReassignment() {
        ResourceId recoveryRequirement = id("condition:recovery_scan");
        RetryPolicy retry = new RetryPolicy(
                2, 0, Set.of(ConstructionFailureCode.WORKER_UNAVAILABLE.id()));
        RecoveryPolicy recovery = new RecoveryPolicy(
                RecoveryStrategy.EXACT_RESCAN_REASSIGN, 1, true, Set.of(recoveryRequirement));
        ConstructionTask task = task(
                "task:reassign", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, retry, recovery,
                "reservation:reassign", "condition:reassign");
        ConstructionTaskGraph graph = graph(List.of(task), List.of());
        FixtureWorker failing = worker("worker:a_failing", Behavior.RETRYABLE_WORKER_FAILURE);
        FixtureWorker replacement = worker("worker:b_replacement", Behavior.SUCCESS);
        BotFleetCoordinator coordinator = coordinator(
                graph,
                executor(failing, replacement),
                Map.of(id("reservation:reassign"), new BlockPos3i(8, 64, 8)));

        TaskAssignment first = coordinator.tick(20).activeAssignments().get(task.taskId());
        assertThat(first.workerId()).contains(failing.workerId());
        BotFleetTickResult failed = coordinator.tick(21);
        assertThat(failed.activeAssignments()).isEmpty();
        assertThat(failed.latestResults().get(task.taskId()).outcome())
                .isEqualTo(TaskExecutionOutcome.RETRYABLE_FAILURE);
        assertThat(coordinator.tick(22).newlyAssignedTaskIds()).isEmpty();

        ExecutionEvidence reconciled = recoveryEvidence(first, recoveryRequirement, 22);
        assertThat(coordinator.recordReconciliation(task.taskId(), List.of(reconciled), 22)).isTrue();
        TaskAssignment reassigned = coordinator.tick(23).activeAssignments().get(task.taskId());
        assertThat(reassigned.workerId()).contains(replacement.workerId());
        assertThat(reassigned.attempt()).isEqualTo(2);
        assertThat(coordinator.tick(24).completedResults()).containsKey(task.taskId());
    }

    @Test
    void reloadTurnsPendingWorkIntoReconciliationRequiredInsteadOfReplay() {
        ResourceId recoveryRequirement = id("condition:reload_scan");
        RetryPolicy retry = new RetryPolicy(2, 0, Set.of(
                ConstructionFailureCode.WORKER_UNAVAILABLE.id()));
        RecoveryPolicy recovery = new RecoveryPolicy(
                RecoveryStrategy.EXACT_RESCAN_REASSIGN, 1, true, Set.of(recoveryRequirement));
        ConstructionTask task = task(
                "task:reload", TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORDINARY_BLOCK, retry, recovery,
                "reservation:reload", "condition:reload");
        ConstructionTaskGraph graph = graph(List.of(task), List.of());
        FixtureWorker original = worker("worker:a_original", Behavior.PENDING);
        FixtureWorker standby = worker("worker:b_standby", Behavior.SUCCESS);
        Map<ResourceId, BlockPos3i> positions = Map.of(
                id("reservation:reload"), new BlockPos3i(9, 64, 9));
        BotFleetExecutor originalExecutor = executor(original, standby);
        BotFleetCoordinator coordinator = coordinator(graph, originalExecutor, positions);
        coordinator.tick(30);
        TaskAssignment prior = coordinator.tick(31).activeAssignments().get(task.taskId());
        BotFleetCoordinatorSnapshot reloadSnapshot = coordinator.snapshot(32);
        assertThat(reloadSnapshot.reconciliationRequiredTaskIds()).contains(task.taskId());
        assertThat(reloadSnapshot.graphFingerprint()).matches("[0-9a-f]{64}");

        FixtureWorker offline = worker("worker:a_original", Behavior.SUCCESS);
        offline.status = BotWorkerStatus.OFFLINE;
        FixtureWorker recoveredStandby = worker("worker:b_standby", Behavior.SUCCESS);
        BotFleetCoordinatorSnapshot tampered = new BotFleetCoordinatorSnapshot(
                reloadSnapshot.sessionId(), reloadSnapshot.graphId(), "c".repeat(64),
                reloadSnapshot.activeAssignments(), reloadSnapshot.latestResults(),
                reloadSnapshot.completedResults(), reloadSnapshot.completedWorkerIds(),
                reloadSnapshot.terminalFailures(), reloadSnapshot.workPositionReservations(),
                reloadSnapshot.recoveryAssignments(), reloadSnapshot.reassignmentCounts(),
                reloadSnapshot.reconciliationRequiredTaskIds(), reloadSnapshot.globallyCancelled(),
                reloadSnapshot.generation(), reloadSnapshot.savedTick());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BotFleetCoordinator.restore(
                        tampered, graph, executor(offline, recoveredStandby), positions,
                        AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                        PriorityPolicy.TOPOLOGICAL_THEN_ID, new RetryBudget(2, 0),
                        new WorkerHealthPolicy(1, true),
                        ChunkLoadPolicy.REQUIRE_ALREADY_LOADED, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        BotFleetCoordinator restored = BotFleetCoordinator.restore(
                reloadSnapshot,
                graph,
                executor(offline, recoveredStandby),
                positions,
                AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                PriorityPolicy.TOPOLOGICAL_THEN_ID,
                new RetryBudget(2, 0),
                new WorkerHealthPolicy(1, true),
                ChunkLoadPolicy.REQUIRE_ALREADY_LOADED,
                100);
        assertThat(restored.tick(33).newlyAssignedTaskIds()).isEmpty();
        assertThat(restored.recordReconciliation(
                task.taskId(), List.of(recoveryEvidence(prior, recoveryRequirement, 33)), 33)).isTrue();
        TaskAssignment reassigned = restored.tick(34).activeAssignments().get(task.taskId());
        assertThat(reassigned.workerId()).contains(recoveredStandby.workerId());
        assertThat(restored.tick(35).completedResults()).containsKey(task.taskId());
        assertThat(original.executeCalls).hasValue(1);
    }

    @Test
    void globalCancelStopsSchedulingAndCancelsEveryActiveWorker() {
        ConstructionTask first = materialTask("task:cancel_first", "condition:cancel_first");
        ConstructionTask second = materialTask("task:cancel_second", "condition:cancel_second");
        ConstructionTaskGraph graph = graph(List.of(first, second), List.of());
        FixtureWorker one = worker("worker:cancel_one", Behavior.PENDING);
        FixtureWorker two = worker("worker:cancel_two", Behavior.PENDING);
        BotFleetCoordinator coordinator = coordinator(graph, executor(one, two), Map.of());

        assertThat(coordinator.tick(40).activeAssignments()).hasSize(2);
        BotFleetTickResult pending = coordinator.tick(41);
        assertThat(pending.activeAssignments()).hasSize(2);

        BotFleetTickResult cancelled = coordinator.cancelAll(id("reason:operator_cancel"), 42);
        assertThat(cancelled.globallyCancelled()).isTrue();
        assertThat(cancelled.activeAssignments()).isEmpty();
        assertThat(cancelled.latestResults()).allSatisfy((taskId, result) ->
                assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.CANCELLED));
        assertThat(one.status).isEqualTo(BotWorkerStatus.IDLE);
        assertThat(two.status).isEqualTo(BotWorkerStatus.IDLE);
        assertThat(coordinator.tick(43).newlyAssignedTaskIds()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.tick(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void pathConflictsAndDeadlocksAreDetectedWithoutTeleporting() {
        PathConflictResolver resolver = new PathConflictResolver();
        BotPathReservation first = new BotPathReservation(
                id("path:first"), id("assignment:first"), id("worker:first"),
                List.of(new BlockPos3i(0, 64, 0), new BlockPos3i(1, 64, 0)), 10, 0);
        BotPathReservation collision = new BotPathReservation(
                id("path:collision"), id("assignment:collision"), id("worker:collision"),
                List.of(new BlockPos3i(2, 64, 0), new BlockPos3i(1, 64, 0)), 10, 0);
        assertThat(resolver.reserve(first)).isEmpty();
        assertThat(resolver.reserve(collision)).isPresent();
        assertThatThrownByPathTeleport();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BotPathReservation(
                id("path:overflow"), id("assignment:overflow"), id("worker:overflow"),
                List.of(new BlockPos3i(0, 64, 0), new BlockPos3i(1, 64, 0)), Long.MAX_VALUE, 0))
                .isInstanceOf(ArithmeticException.class);

        ResourceId one = id("worker:one");
        ResourceId two = id("worker:two");
        ResourceId three = id("worker:three");
        DeadlockDetector.Deadlock deadlock = DeadlockDetector.detect(Map.of(
                one, two,
                two, three,
                three, one)).orElseThrow();
        assertThat(deadlock.workers()).containsExactlyInAnyOrder(one, two, three);
        assertThat(DeadlockDetector.detect(Map.of(one, two))).isEmpty();
    }

    private static void assertThatThrownByPathTeleport() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BotPathReservation(
                id("path:teleport"), id("assignment:teleport"), id("worker:teleport"),
                List.of(new BlockPos3i(0, 64, 0), new BlockPos3i(2, 64, 0)), 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teleport");
    }

    private static BotFleetCoordinator coordinator(
            ConstructionTaskGraph graph,
            BotFleetExecutor executor,
            Map<ResourceId, BlockPos3i> positions) {
        return new BotFleetCoordinator(
                id("session:fleet"), graph, executor, positions,
                AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                PriorityPolicy.TOPOLOGICAL_THEN_ID,
                new RetryBudget(2, 0),
                new WorkerHealthPolicy(1, true),
                ChunkLoadPolicy.REQUIRE_ALREADY_LOADED,
                100);
    }

    private static BotFleetExecutor executor(FixtureWorker... workers) {
        return new BotFleetExecutor(EXECUTOR, EXECUTOR_CAPABILITIES, List.of(workers));
    }

    private static FixtureWorker worker(String workerId, Behavior behavior) {
        return new FixtureWorker(
                id(workerId), EnumSet.allOf(BotWorkerCapability.class), behavior);
    }

    private static ConstructionTask materialTask(String taskId, String conditionId) {
        return new ConstructionTask(
                id(taskId),
                new VerifiedPlanTaskSource(PLAN, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                        id("physical:" + taskId.substring(taskId.indexOf(':') + 1)), 0),
                id("capability:bot_test"),
                TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                Set.of(ExecutionMode.BOTS),
                List.of(),
                List.of(post(conditionId)),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                CleanupPolicy.NONE,
                Set.of(),
                Set.of(id("material:" + taskId.substring(taskId.indexOf(':') + 1))),
                Set.of(),
                1_200,
                Map.of(id("test:kind"), "material"));
    }

    private static ConstructionTask task(
            String taskId,
            TaskKind kind,
            ConstructionTaskClass taskClass,
            RetryPolicy retry,
            RecoveryPolicy recovery,
            String placementReservation,
            String conditionId) {
        return new ConstructionTask(
                id(taskId),
                new VerifiedPlanTaskSource(PLAN, TaskSourceKind.VERIFIED_PLACEMENT,
                        id("physical:" + taskId.substring(taskId.indexOf(':') + 1)), 0),
                id("capability:bot_test"),
                kind,
                taskClass,
                Set.of(ExecutionMode.BOTS),
                List.of(),
                List.of(post(conditionId)),
                retry,
                true,
                recovery,
                new CleanupPolicy(
                        CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                        false,
                        true,
                        16),
                Set.of(id(placementReservation)),
                kind.operatesOnMaterial()
                        ? Set.of(id("material:" + taskId.substring(taskId.indexOf(':') + 1)))
                        : Set.of(),
                Set.of(),
                1_200,
                Map.of(id("test:kind"), "placement"));
    }

    private static TaskPostcondition post(String conditionId) {
        return new TaskPostcondition(
                id(conditionId),
                TaskConditionKind.CURRENT_STATE_MATCHES,
                id("subject:" + conditionId.substring(conditionId.indexOf(':') + 1)),
                ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                Map.of(id("value:state"), "verified"));
    }

    private static TaskDependency dependency(ConstructionTask predecessor, ConstructionTask successor) {
        return new TaskDependency(
                predecessor.taskId(), successor.taskId(), TaskDependencyKind.FINISH_TO_START,
                predecessor.postconditionIds());
    }

    private static ConstructionTaskGraph graph(
            List<ConstructionTask> tasks,
            List<TaskDependency> dependencies) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, new ModeCapabilityDeclaration(
                ExecutionMode.DIRECT, CapabilitySupport.UNSUPPORTED, Set.of(), "test uses Bots"));
        modes.put(ExecutionMode.BOTS, new ModeCapabilityDeclaration(
                ExecutionMode.BOTS, CapabilitySupport.SUPPORTED, EXECUTOR_CAPABILITIES, ""));
        modes.put(ExecutionMode.HYBRID, new ModeCapabilityDeclaration(
                ExecutionMode.HYBRID, CapabilitySupport.UNSUPPORTED, Set.of(), "test uses Bots"));
        CapabilityExecutionDescriptor descriptor = new CapabilityExecutionDescriptor(
                id("capability:bot_test"), id("implementation:bot_test"), id("adapter:test"),
                RUNTIME, EnumSet.allOf(TaskKind.class), modes, Map.of(id("test:version"), "1"));
        return new ConstructionTaskGraph(
                id("graph:bot_test_" + tasks.stream().map(value -> value.taskId().path())
                        .sorted().reduce("", (left, right) -> left + "_" + right)),
                PLAN,
                RUNTIME,
                "bot-snapshot-v1",
                List.of(descriptor),
                tasks,
                dependencies);
    }

    private static TaskAssignment assignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ResourceId workerId,
            int attempt,
            long tick) {
        TaskOwnership ownership = new TaskOwnership(
                id("session:fixture"), graph.graphId(), task.taskId(), EXECUTOR,
                ExecutionMode.BOTS, Optional.of(workerId), 1, tick, tick + 100,
                "b".repeat(64));
        return TaskAssignment.assign(
                graph, task.taskId(), id("assignment:" + task.taskId().path() + "_" + attempt),
                ownership, attempt, tick);
    }

    private static ConstructionExecutionContext context(ConstructionExecutionCommand command, long tick) {
        return new ConstructionExecutionContext(
                command, tick, List.of(), Optional.empty(),
                command == ConstructionExecutionCommand.CANCEL
                        ? Optional.of(id("reason:test")) : Optional.empty());
    }

    private static ExecutionEvidence recoveryEvidence(
            TaskAssignment assignment,
            ResourceId requirementId,
            long tick) {
        Map<ResourceId, String> values = Map.of(id("value:state"), "reconciled");
        return new ExecutionEvidence(
                id("evidence:" + assignment.assignmentId().path() + "_recovery"),
                requirementId,
                assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.assignmentId(), assignment.executorId(), assignment.mode(),
                ExecutionEvidenceKind.RECOVERY_RECONCILED,
                assignment.taskId(), values, values, tick, true,
                "fixture:authoritative-rescan");
    }

    private enum Behavior { SUCCESS, PENDING, RETRYABLE_WORKER_FAILURE }

    private static final class FixtureWorker implements BotWorker {
        private final ResourceId workerId;
        private final Set<BotWorkerCapability> capabilities;
        private final Behavior behavior;
        private final AtomicInteger executeCalls = new AtomicInteger();
        private BotWorkerStatus status = BotWorkerStatus.IDLE;
        private TaskAssignment activeAssignment;

        private FixtureWorker(
                ResourceId workerId,
                Set<BotWorkerCapability> capabilities,
                Behavior behavior) {
            this.workerId = workerId;
            this.capabilities = Set.copyOf(capabilities);
            this.behavior = behavior;
        }

        @Override
        public ResourceId workerId() {
            return workerId;
        }

        @Override
        public BotWorkerSnapshot snapshot(long currentTick) {
            boolean busy = status == BotWorkerStatus.BUSY;
            return new BotWorkerSnapshot(
                    workerId,
                    status,
                    new BlockPos3i(0, 64, 0),
                    id("region:test"),
                    capabilities,
                    busy ? Optional.of(activeAssignment.sessionId()) : Optional.empty(),
                    busy ? Optional.of(activeAssignment.taskId()) : Optional.empty(),
                    busy ? Optional.of(activeAssignment.assignmentId()) : Optional.empty(),
                    new BotInventory(workerId, 64, Map.of(), 0, currentTick),
                    status == BotWorkerStatus.DEAD ? 0 : 20,
                    status != BotWorkerStatus.OFFLINE,
                    true,
                    executeCalls.get(),
                    currentTick);
        }

        @Override
        public TaskExecutionResult execute(
                ConstructionTaskGraph graph,
                ConstructionTask task,
                TaskAssignment assignment,
                ConstructionExecutionContext context) {
            executeCalls.incrementAndGet();
            if (context.command() == ConstructionExecutionCommand.CANCEL) {
                status = BotWorkerStatus.IDLE;
                activeAssignment = null;
                TaskFailure cancellation = new TaskFailure(
                        ConstructionFailureCode.CANCELLED.id(), TaskFailureCategory.CANCELLED,
                        false, Optional.empty(), Optional.of(task.taskId()), "fixture cancellation",
                        List.of(assignment.assignmentId()), "fixture cancelled safely");
                return TaskExecutionResult.cancelled(
                        task, assignment, context.currentTick(), List.of(), cancellation,
                        "fixture cancellation complete");
            }
            if (behavior == Behavior.PENDING) {
                status = BotWorkerStatus.BUSY;
                activeAssignment = assignment;
                return TaskExecutionResult.pending(
                        task, assignment, context.currentTick(), List.of(), 0, 0,
                        "fixture Bot pending");
            }
            if (behavior == Behavior.RETRYABLE_WORKER_FAILURE) {
                status = BotWorkerStatus.OFFLINE;
                activeAssignment = null;
                TaskFailure failure = new TaskFailure(
                        ConstructionFailureCode.WORKER_UNAVAILABLE.id(),
                        TaskFailureCategory.WORKER_UNAVAILABLE,
                        true,
                        Optional.empty(), Optional.of(workerId), "fixture worker unloaded",
                        List.of(assignment.assignmentId()), "reconcile and reassign");
                return TaskExecutionResult.failure(
                        task, assignment, context.currentTick(), List.of(), failure, 0, 0,
                        "fixture worker failure");
            }
            status = BotWorkerStatus.IDLE;
            activeAssignment = null;
            List<ExecutionEvidence> evidence = task.postconditions().stream()
                    .map(postcondition -> new ExecutionEvidence(
                            id("evidence:" + assignment.assignmentId().path() + "_"
                                    + postcondition.conditionId().path()),
                            postcondition.conditionId(), assignment.sessionId(), assignment.graphId(),
                            assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                            assignment.mode(), postcondition.requiredEvidenceKind(),
                            postcondition.subjectId(), postcondition.expectedValues(),
                            postcondition.expectedValues(), context.currentTick(), true,
                            "fixture:bot-readback"))
                    .toList();
            return TaskExecutionResult.success(
                    task, assignment, context.currentTick(), evidence,
                    task.kind().mutatesWorld() ? 1 : 0,
                    task.kind().operatesOnMaterial() ? 1 : 0,
                    "fixture Bot success");
        }
    }
}
