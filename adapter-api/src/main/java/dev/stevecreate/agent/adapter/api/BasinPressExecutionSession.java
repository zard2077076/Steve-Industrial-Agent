package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One authoritative non-blocking C-09 execution. */
public interface BasinPressExecutionSession extends RecoverableExecutionSession {
    AdapterResult<BasinPressExecutionUpdate> tick();
    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);
    WorldChangeJournal worldChangeJournal();
}
