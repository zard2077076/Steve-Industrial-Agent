package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;

/** Optional contract for sessions that can persist a bounded, safety-checked recovery point. */
public interface RecoverableExecutionSession {
    AdapterResult<RecoveryCheckpoint> recoveryCheckpoint(long savedTick);
}
