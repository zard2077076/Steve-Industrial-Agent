package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One authoritative non-blocking C-06 airflow execution. */
public interface FanProcessingExecutionSession extends RecoverableExecutionSession {
    AdapterResult<FanProcessingExecutionUpdate> tick();
    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);
    WorldChangeJournal worldChangeJournal();
}
