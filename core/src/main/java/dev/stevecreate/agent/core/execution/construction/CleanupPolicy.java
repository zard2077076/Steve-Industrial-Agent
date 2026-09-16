package dev.stevecreate.agent.core.execution.construction;

import java.util.Objects;

/** Exact bounded cleanup and leftover-material behavior for one construction task. */
public record CleanupPolicy(
        CleanupScope scope,
        boolean returnUnusedMaterials,
        boolean exactStateVerificationRequired,
        int maximumCleanupMutations) {
    public static final int MAX_CLEANUP_MUTATIONS = 256;
    public static final CleanupPolicy NONE = new CleanupPolicy(
            CleanupScope.NONE, false, false, 0);

    public CleanupPolicy {
        Objects.requireNonNull(scope, "scope");
        int minimum = scope == CleanupScope.NONE ? 0 : 1;
        int maximum = scope == CleanupScope.NONE ? 0 : MAX_CLEANUP_MUTATIONS;
        if (maximumCleanupMutations < minimum || maximumCleanupMutations > maximum) {
            throw new IllegalArgumentException("maximumCleanupMutations must be between "
                    + minimum + " and " + maximum + " for " + scope);
        }
        if (scope != CleanupScope.NONE && !exactStateVerificationRequired) {
            throw new IllegalArgumentException("World cleanup requires exact current-state verification");
        }
    }
}
