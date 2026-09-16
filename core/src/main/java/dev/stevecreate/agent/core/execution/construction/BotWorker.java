package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.execution.fleet.FleetWorker;

/** Loader-neutral controlled-worker boundary; implementations own any game entity internally. */
public interface BotWorker extends FleetWorker {
    @Override
    ResourceId workerId();

    @Override
    BotWorkerSnapshot snapshot(long currentTick);

    TaskExecutionResult execute(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context);
}
