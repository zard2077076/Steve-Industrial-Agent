package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.execution.fleet.FleetTaskDispatcher;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Outcome;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Update;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Translates one terrain-owned executor update into the shared fleet state machine. */
public final class TerrainPreparationFleetDispatcher
        implements FleetTaskDispatcher<TerrainPreparationTaskGraph, TerrainPreparationTask> {
    public static final ResourceId DISPATCHER_ID =
            ResourceId.parse("site-prep:terrain_dispatcher_v1");

    private final TerrainPreparationPlan plan;
    private final BotClearingExecutor executor;
    private final TerrainPreparationFleetTaskAdapter adapter;

    public TerrainPreparationFleetDispatcher(
            TerrainPreparationPlan plan,
            BotClearingExecutor executor,
            TerrainPreparationFleetTaskAdapter adapter) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public Update execute(
            TerrainPreparationTaskGraph graph,
            TerrainPreparationTask task,
            Assignment assignment,
            ExecutionContext context) {
        if (!graph.equals(plan.taskGraph())) {
            throw new IllegalArgumentException("Terrain dispatcher received another plan graph");
        }
        BotClearingUpdate result = Objects.requireNonNull(
                executor.execute(plan, task, assignment, context),
                "Bot clearing executor update");
        Outcome outcome = switch (result.state()) {
            case READY, RUNNING -> Outcome.PENDING;
            case COMPLETED -> Outcome.SUCCEEDED;
            case CANCELLED -> Outcome.CANCELLED;
            case FAILED -> adapter.allowsReassignment(
                    graph, task, result.failureCode().orElseThrow())
                    ? Outcome.RETRYABLE_FAILURE : Outcome.TERMINAL_FAILURE;
        };
        return new Update(
                adapter.taskId(task), assignment.assignmentId(), assignment.workerId(),
                DISPATCHER_ID, context.currentTick(), outcome, result.failureCode(),
                result.evidenceIds(), result.evidenceFields(), result.detail());
    }
}
