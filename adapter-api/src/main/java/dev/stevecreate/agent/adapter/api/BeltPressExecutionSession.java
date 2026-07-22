package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One server-authoritative C-04 execution; callers tick it without waiting or blocking. */
public interface BeltPressExecutionSession {
    AdapterResult<BeltPressExecutionUpdate> tick();

    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);

    WorldChangeJournal worldChangeJournal();
}
