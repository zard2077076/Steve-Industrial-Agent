package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One server-authoritative non-blocking C-07 execution. */
public interface MechanicalSawExecutionSession extends RecoverableExecutionSession {
    AdapterResult<MechanicalSawExecutionUpdate> tick();
    AdapterResult<ExecutionCancellationResult> cancel(ResourceId reason);
    WorldChangeJournal worldChangeJournal();
}
