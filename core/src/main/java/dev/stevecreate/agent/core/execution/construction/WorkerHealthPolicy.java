package dev.stevecreate.agent.core.execution.construction;

/** Exact health/load gate for assignment; dead/offline workers are never selected. */
public record WorkerHealthPolicy(int minimumHealth, boolean requireLoaded) {
    public WorkerHealthPolicy {
        if (minimumHealth < 1 || minimumHealth > BotWorkerSnapshot.MAX_HEALTH) {
            throw new IllegalArgumentException("minimumHealth is outside the Bot health bound");
        }
    }

    public boolean eligible(BotWorkerSnapshot worker) {
        return worker.status() == BotWorkerStatus.IDLE
                && worker.health() >= minimumHealth
                && (!requireLoaded || worker.loaded());
    }
}
