package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One authoritative non-blocking C-08 execution. */
public interface BasinMixerExecutionSession
        extends RecoverableExecutionSession {
    AdapterResult<BasinMixerExecutionUpdate> tick();
    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);
    WorldChangeJournal worldChangeJournal();
}
