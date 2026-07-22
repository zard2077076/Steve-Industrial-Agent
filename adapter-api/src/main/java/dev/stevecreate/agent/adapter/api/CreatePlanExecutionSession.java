package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One server-authoritative execution; callers tick it without waiting or blocking. */
public interface CreatePlanExecutionSession {
    AdapterResult<CreatePlanExecutionUpdate> tick();

    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);

    WorldChangeJournal worldChangeJournal();
}
