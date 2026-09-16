package dev.stevecreate.agent.core.execution.fleet;

import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;

/** Loader-neutral identity and state surface shared by typed fleet dispatchers. */
public interface FleetWorker {
    ResourceId workerId();

    BotWorkerSnapshot snapshot(long currentTick);
}
