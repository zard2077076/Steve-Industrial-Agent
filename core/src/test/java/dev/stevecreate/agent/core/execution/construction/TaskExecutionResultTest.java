package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.PLAN_ID;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.RUNTIME;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.SNAPSHOT;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.assignment;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.descriptor;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.passingEvidence;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.post;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.simpleGraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskExecutionResultTest {
    @Test
    void successRequiresCompleteObjectiveEvidenceFromTheExactAssignment() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);

        assertThatThrownBy(() -> TaskExecutionResult.success(
                task, assignment, 11, List.of(), 1, 1, "memory flag only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing objective postcondition evidence");

        ExecutionEvidence evidence = passingEvidence(assignment, task.postconditions().get(0), 11);
        TaskExecutionResult result = TaskExecutionResult.success(
                task, assignment, 11, List.of(evidence), 1, 1, "block readback matched");

        assertThat(result.outcome()).isEqualTo(TaskExecutionOutcome.SUCCEEDED);
        assertThat(result.evidence()).containsExactly(evidence);
        assertThat(result.worldMutationCount()).isEqualTo(1);

        ExecutionEvidence foreign = new ExecutionEvidence(
                id("evidence:foreign"),
                evidence.requirementId(),
                id("session:other"),
                evidence.graphId(),
                evidence.taskId(),
                evidence.assignmentId(),
                evidence.executorId(),
                evidence.mode(),
                evidence.kind(),
                evidence.subjectId(),
                evidence.observedValues(),
                evidence.expectedValues(),
                11,
                true,
                "fixture:foreign");
        assertThatThrownBy(() -> TaskExecutionResult.success(
                task, assignment, 11, List.of(foreign), 1, 1, "foreign evidence"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another assignment");
    }

    @Test
    void retryCancellationAndUnsupportedOutcomesAreTypedAndPolicyBound() {
        TaskFailure retryFailure = failure(
                "failure:navigation_blocked",
                TaskFailureCategory.NAVIGATION_BLOCKED,
                true);
        ConstructionTask task = retryTask(retryFailure.code());
        ConstructionTaskGraph graph = singleTaskGraph(task);
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.BOTS, 1, 10);

        TaskExecutionResult retry = TaskExecutionResult.failure(
                task, assignment, 11, List.of(), retryFailure, 0, 0, "repath later");
        assertThat(retry.outcome()).isEqualTo(TaskExecutionOutcome.RETRYABLE_FAILURE);

        TaskFailure cancellation = failure(
                "failure:cancelled",
                TaskFailureCategory.CANCELLED,
                false);
        assertThat(TaskExecutionResult.cancelled(
                task, assignment, 12, List.of(), cancellation, "cancelled").outcome())
                .isEqualTo(TaskExecutionOutcome.CANCELLED);

        TaskFailure unsupported = failure(
                "failure:unsupported",
                TaskFailureCategory.UNSUPPORTED_CAPABILITY,
                false);
        assertThat(TaskExecutionResult.unsupported(
                task, assignment, 12, unsupported, "unsupported").outcome())
                .isEqualTo(TaskExecutionOutcome.UNSUPPORTED);

        TaskAssignment exhausted = assignment(graph, task, ExecutionMode.BOTS, 2, 20);
        assertThatThrownBy(() -> TaskExecutionResult.failure(
                task, exhausted, 21, List.of(), retryFailure, 0, 0, "no attempts left"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the task retry policy");
    }

    @Test
    void oneExecutorCallCannotHideMultipleWorldOrMaterialMutations() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        ExecutionEvidence evidence = passingEvidence(assignment, task.postconditions().get(0), 11);

        assertThatThrownBy(() -> TaskExecutionResult.pending(
                task, assignment, 11, List.of(evidence), 2, 0, "too much work"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one");
    }

    @Test
    void latestEvidenceAndCurrentOwnershipControlSuccess() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        TaskPostcondition postcondition = task.postconditions().get(0);
        ExecutionEvidence passed = passingEvidence(assignment, postcondition, 11);
        ExecutionEvidence laterFailure = new ExecutionEvidence(
                id("evidence:later_failure"),
                postcondition.conditionId(),
                assignment.sessionId(),
                assignment.graphId(),
                assignment.taskId(),
                assignment.assignmentId(),
                assignment.executorId(),
                assignment.mode(),
                postcondition.requiredEvidenceKind(),
                postcondition.subjectId(),
                Map.of(id("value:state"), "minecraft:air"),
                postcondition.expectedValues(),
                12,
                false,
                "fixture:later-readback");

        assertThatThrownBy(() -> TaskExecutionResult.success(
                task, assignment, 12, List.of(passed, laterFailure), 1, 1, "stale pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing objective postcondition evidence");
        assertThatThrownBy(() -> TaskExecutionResult.success(
                task, assignment, 110, List.of(passed), 1, 1, "expired owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current task ownership");
    }

    @Test
    void aPassingEvidenceFlagCannotContradictItsObservedValues() {
        ConstructionTaskGraph graph = simpleGraph();
        ConstructionTask task = graph.task(id("task:place"));
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, 10);
        TaskPostcondition postcondition = task.postconditions().get(0);

        assertThatThrownBy(() -> new ExecutionEvidence(
                id("evidence:contradiction"),
                postcondition.conditionId(),
                assignment.sessionId(),
                assignment.graphId(),
                assignment.taskId(),
                assignment.assignmentId(),
                assignment.executorId(),
                assignment.mode(),
                postcondition.requiredEvidenceKind(),
                postcondition.subjectId(),
                Map.of(id("value:state"), "minecraft:air"),
                postcondition.expectedValues(),
                11,
                true,
                "fixture:contradiction"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected observed value");
    }

    private static ConstructionTask retryTask(dev.stevecreate.agent.core.model.ResourceId failureCode) {
        TaskPostcondition postcondition = post(
                "condition:navigation",
                ExecutionEvidenceKind.NAVIGATION_REACHED,
                "subject:work_position",
                "reached");
        return new ConstructionTask(
                id("task:navigate"),
                new VerifiedPlanTaskSource(
                        PLAN_ID,
                        TaskSourceKind.VERIFIED_PORT,
                        id("physical:work_position"),
                        0),
                descriptor().capabilityId(),
                TaskKind.VERIFY_STATE,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                EnumSet.allOf(ExecutionMode.class),
                List.of(),
                List.of(postcondition),
                new RetryPolicy(2, 5, Set.of(failureCode)),
                true,
                new RecoveryPolicy(
                        RecoveryStrategy.EXACT_RESCAN_REASSIGN,
                        2,
                        true,
                        Set.of(id("evidence:worker_position"))),
                CleanupPolicy.NONE,
                Set.of(),
                Set.of(),
                Set.of(),
                100,
                Map.of());
    }

    private static ConstructionTaskGraph singleTaskGraph(ConstructionTask task) {
        return new ConstructionTaskGraph(
                id("graph:retry"), PLAN_ID, RUNTIME, SNAPSHOT,
                List.of(descriptor()), List.of(task), List.of());
    }

    private static TaskFailure failure(
            String code,
            TaskFailureCategory category,
            boolean retryable) {
        return new TaskFailure(
                id(code),
                category,
                retryable,
                Optional.empty(),
                Optional.empty(),
                "fixture failure",
                List.of(id("trace:fixture")),
                "Apply the bounded fixture recovery policy");
    }
}
