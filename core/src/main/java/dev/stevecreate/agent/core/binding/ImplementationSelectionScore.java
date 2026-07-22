package dev.stevecreate.agent.core.binding;

/** Explicit lower-is-better score; canonical implementation ID is the final stable tie-break. */
public record ImplementationSelectionScore(
        int descriptorPriority,
        int adapterPreferencePenalty,
        int limitationPenalty,
        int total) implements Comparable<ImplementationSelectionScore> {
    public ImplementationSelectionScore {
        if (descriptorPriority < 0 || adapterPreferencePenalty < 0 || limitationPenalty < 0) {
            throw new IllegalArgumentException("Selection score contributions cannot be negative");
        }
        int expected = Math.addExact(
                descriptorPriority,
                Math.addExact(adapterPreferencePenalty, limitationPenalty));
        if (total != expected) {
            throw new IllegalArgumentException("Selection score total does not match contributions");
        }
    }

    @Override
    public int compareTo(ImplementationSelectionScore other) {
        return Integer.compare(total, other.total);
    }
}
