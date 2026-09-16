package dev.stevecreate.agent.core.execution.construction;

import java.util.Comparator;

/** Deterministic worker selection; neither policy can override capability or health gates. */
public enum AssignmentPolicy {
    LOWEST_WORKER_ID,
    LEAST_CARRIED_THEN_ID;

    public Comparator<BotWorkerSnapshot> comparator() {
        Comparator<BotWorkerSnapshot> byId = Comparator.comparing(
                value -> value.workerId().toString());
        if (this == LOWEST_WORKER_ID) return byId;
        return Comparator.comparingLong((BotWorkerSnapshot value) -> value.inventory().usedCapacity())
                .thenComparing(byId);
    }
}
