package dev.stevecreate.agent.core.execution.construction;

/** Separate bounded worker-reassignment budget; task action retries remain in RetryPolicy. */
public record RetryBudget(int maximumReassignments, int reassignmentBackoffTicks) {
    public static final int MAX_REASSIGNMENTS = 8;

    public RetryBudget {
        if (maximumReassignments < 0 || maximumReassignments > MAX_REASSIGNMENTS
                || reassignmentBackoffTicks < 0 || reassignmentBackoffTicks > 1_200) {
            throw new IllegalArgumentException("Retry budget is outside the bounded fleet limits");
        }
        if (maximumReassignments == 0 && reassignmentBackoffTicks != 0) {
            throw new IllegalArgumentException("A zero reassignment budget cannot have backoff");
        }
    }

    public boolean allows(int completedReassignments) {
        return completedReassignments >= 0 && completedReassignments < maximumReassignments;
    }
}
