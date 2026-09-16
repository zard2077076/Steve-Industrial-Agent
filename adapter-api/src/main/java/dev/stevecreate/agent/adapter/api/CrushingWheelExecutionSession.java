package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One server-authoritative C-05 execution; callers tick it without blocking. */
public interface CrushingWheelExecutionSession {
    AdapterResult<CrushingWheelExecutionUpdate> tick();

    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);

    WorldChangeJournal worldChangeJournal();
}
