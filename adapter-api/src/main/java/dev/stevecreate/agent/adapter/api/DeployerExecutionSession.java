package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;

/** One authoritative non-blocking C-10 Deployer execution. */
public interface DeployerExecutionSession
        extends RecoverableExecutionSession {
    AdapterResult<DeployerExecutionUpdate> tick();
    AdapterResult<ExecutionCancellationResult> cancel(
            ResourceId reason);
    WorldChangeJournal worldChangeJournal();
}
