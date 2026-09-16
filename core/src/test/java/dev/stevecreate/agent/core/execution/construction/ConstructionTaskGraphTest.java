package dev.stevecreate.agent.core.execution.construction;

import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.PLAN_ID;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.RUNTIME;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.SNAPSHOT;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.descriptor;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.id;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.assignment;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.passingEvidence;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.post;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.simpleGraph;
import static dev.stevecreate.agent.core.execution.construction.ConstructionContractFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstructionTaskGraphTest {
    @Test
    void graphIsDeterministicDAGAndReadyTasksFollowDependencies() {
        ConstructionTaskGraph graph = simpleGraph();

        assertThat(graph.topologicalOrder())
                .extracting(ConstructionTask::taskId)
                .containsExactly(id("task:fetch"), id("task:place"), id("task:verify"));
        assertThat(graph.readyTasks(Map.of()))
                .extracting(ConstructionTask::taskId)
                .containsExactly(id("task:fetch"));
        TaskExecutionResult fetchResult = success(graph, graph.task(id("task:fetch")), 10);
        assertThat(graph.readyTasks(Map.of(id("task:fetch"), fetchResult)))
                .extracting(ConstructionTask::taskId)
                .containsExactly(id("task:place"));
        TaskExecutionResult placeResult = success(graph, graph.task(id("task:place")), 20);
        assertThat(graph.readyTasks(Map.of(
                id("task:fetch"), fetchResult,
                id("task:place"), placeResult)))
                .extracting(ConstructionTask::taskId)
                .containsExactly(id("task:verify"));
    }

    @Test
    void worldMutationRequiresVerifiedPhysicalSourceAndReservations() {
        assertThatThrownBy(() -> task(
                "task:bad_source",
                TaskKind.CONNECT_COMPONENTS,
                ConstructionTaskClass.CREATE_MACHINE,
                TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                Set.of(id("reservation:position")),
                Set.of(),
                post("condition:connected", ExecutionEvidenceKind.CONNECTION_VERIFIED,
                        "subject:connection", "connected")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placement, route or port provenance");

        assertThatThrownBy(() -> task(
                "task:no_position_lease",
                TaskKind.CONNECT_COMPONENTS,
                ConstructionTaskClass.CREATE_MACHINE,
                TaskSourceKind.VERIFIED_ROUTE,
                Set.of(),
                Set.of(),
                post("condition:connected", ExecutionEvidenceKind.CONNECTION_VERIFIED,
                        "subject:connection", "connected")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placement reservation");
    }

    @Test
    void graphRejectsCyclesDanglingEvidenceAndPhysicalPlanDrift() {
        ConstructionTask first = task(
                "task:first",
                TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                Set.of(),
                Set.of(),
                post("condition:first", ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "subject:first", "ok"));
        ConstructionTask second = task(
                "task:second",
                TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                Set.of(),
                Set.of(),
                post("condition:second", ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "subject:second", "ok"));
        List<ConstructionTask> tasks = List.of(first, second);

        assertThatThrownBy(() -> graph(tasks, List.of(
                dependency(first, second, first.postconditions().get(0).conditionId()),
                dependency(second, first, second.postconditions().get(0).conditionId()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acyclic");

        assertThatThrownBy(() -> graph(tasks, List.of(
                dependency(first, second, id("condition:not_produced")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not produced");

        ConstructionTask drifted = new ConstructionTask(
                second.taskId(),
                new VerifiedPlanTaskSource(
                        id("test:other_plan"),
                        second.source().sourceKind(),
                        second.source().physicalElementId(),
                        0),
                second.capabilityId(),
                second.kind(),
                second.taskClass(),
                second.allowedModes(),
                second.preconditions(),
                second.postconditions(),
                second.retryPolicy(),
                second.cancellable(),
                second.recoveryPolicy(),
                second.cleanupPolicy(),
                second.requiredPlacementReservationIds(),
                second.requiredMaterialReservationIds(),
                second.requiredSharedInfrastructureReservationIds(),
                second.maximumTicks(),
                second.parameters());
        assertThatThrownBy(() -> graph(List.of(first, drifted), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to verified plan");
    }

    private static ConstructionTaskGraph graph(
            List<ConstructionTask> tasks,
            List<TaskDependency> dependencies) {
        return new ConstructionTaskGraph(
                id("graph:custom"),
                PLAN_ID,
                RUNTIME,
                SNAPSHOT,
                List.of(descriptor()),
                tasks,
                dependencies);
    }

    private static TaskDependency dependency(
            ConstructionTask predecessor,
            ConstructionTask successor,
            ResourceId evidence) {
        return new TaskDependency(
                predecessor.taskId(),
                successor.taskId(),
                TaskDependencyKind.VERIFICATION_GATE,
                Set.of(evidence));
    }

    private static TaskExecutionResult success(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            long tick) {
        TaskAssignment assignment = assignment(graph, task, ExecutionMode.DIRECT, 1, tick);
        return TaskExecutionResult.success(
                task,
                assignment,
                tick + 1,
                List.of(passingEvidence(assignment, task.postconditions().get(0), tick + 1)),
                task.kind().mutatesWorld() ? 1 : 0,
                task.kind().operatesOnMaterial() ? 1 : 0,
                "fixture success");
    }
}
