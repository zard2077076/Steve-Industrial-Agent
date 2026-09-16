package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.assignment;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.simpleGraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DirectWorldExecutorTest {
    @Test
    void delegatesOneExactBoundedDirectCall() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        AtomicInteger calls = new AtomicInteger();
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:bounded_world_action")),
                (actualGraph, actualTask, actualAssignment, context) -> {
                    calls.incrementAndGet();
                    return TaskExecutionResult.pending(
                            actualTask, actualAssignment, context.currentTick(), List.of(), 1, 0,
                            "one bounded placement action");
                });

        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.START, 10, List.of(), Optional.empty()));

        assertThat(calls).hasValue(1);
        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.PENDING);
        assertThat(result.worldMutationCount()).isEqualTo(1);
        assertThat(result.assignmentId()).isEqualTo(assignment.assignmentId());
    }

    @Test
    void missingDeclaredCapabilityReturnsUnsupportedWithoutCallingBackend() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        AtomicInteger calls = new AtomicInteger();
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:different_capability")),
                (actualGraph, actualTask, actualAssignment, context) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("unsupported backend must not run");
                });

        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.START, 10, List.of(), Optional.empty()));

        assertThat(calls).hasValue(0);
        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.UNSUPPORTED);
        assertThat(result.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED.id());
    }

    @Test
    void foreignPriorEvidenceFailsBeforeWorldBackend() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        ConstructionTask otherTask = graph.task(id("task:fetch"));
        TaskAssignment other = assignment(graph, otherTask, ExecutionMode.DIRECT, 1, 10);
        ExecutionEvidence foreign = ConstructionContractFixtures.passingEvidence(
                other, otherTask.postconditions().get(0), 10);
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:bounded_world_action")),
                (actualGraph, actualTask, actualAssignment, context) -> {
                    throw new AssertionError("invalid prior evidence must not reach the backend");
                });

        assertThatThrownBy(() -> executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.CONTINUE, 11, List.of(foreign), Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another assignment");
    }

    @Test
    void refusedRecoveryNeverInvokesWorldBackend() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        ExecutionEvidence evidence = ConstructionContractFixtures.passingEvidence(
                assignment, task.postconditions().get(0), 10);
        AtomicInteger calls = new AtomicInteger();
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:bounded_world_action")),
                (actualGraph, actualTask, actualAssignment, context) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("REFUSE recovery must not reach the backend");
                });

        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.RECOVER, 10, List.of(evidence), Optional.empty()));

        assertThat(calls).hasValue(0);
        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.RECOVERY_REQUIRED);
        assertThat(result.failure().orElseThrow().code())
                .isEqualTo(ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION.id());
    }

    @Test
    void mismatchedBackendTickIsRejected() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:bounded_world_action")),
                (actualGraph, actualTask, actualAssignment, context) -> TaskExecutionResult.pending(
                        actualTask, actualAssignment, 10, List.of(), 0, 0, "stale result"));

        assertThatThrownBy(() -> executor.execute(
                graph,
                task,
                assignment,
                context(ConstructionExecutionCommand.CONTINUE, 11, List.of(), Optional.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another assignment, mode or tick");
    }

    @Test
    void exactCancellationStillRunsAfterOwnershipExpiry() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        AtomicInteger calls = new AtomicInteger();
        DirectWorldExecutor executor = new DirectWorldExecutor(
                id("executor:direct"),
                Set.of(id("executor:bounded_world_action")),
                (actualGraph, actualTask, actualAssignment, context) -> {
                    calls.incrementAndGet();
                    TaskFailure cancellation = new TaskFailure(
                            ConstructionFailureCode.CANCELLED.id(),
                            TaskFailureCategory.CANCELLED,
                            false,
                            Optional.empty(),
                            Optional.of(actualTask.taskId()),
                            "expired ownership cancellation",
                            List.of(actualAssignment.assignmentId()),
                            "inspect cleanup evidence");
                    return TaskExecutionResult.cancelled(
                            actualTask,
                            actualAssignment,
                            context.currentTick(),
                            List.of(),
                            cancellation,
                            "cancelled after ownership expiry");
                });

        TaskExecutionResult result = executor.execute(
                graph,
                task,
                assignment,
                new ConstructionExecutionContext(
                        ConstructionExecutionCommand.CANCEL,
                        110,
                        List.of(),
                        Optional.empty(),
                        Optional.of(id("reason:test"))));

        assertThat(calls).hasValue(1);
        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.CANCELLED);
    }

    private static ConstructionExecutionContext context(
            ConstructionExecutionCommand command,
            long tick,
            List<ExecutionEvidence> evidence,
            Optional<TaskFailure> failure) {
        return new ConstructionExecutionContext(command, tick, evidence, failure, Optional.empty());
    }
}
