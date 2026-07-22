package dev.stevecreate.agent.core.planning;

/** Explicit bounded weights for every initial deterministic scoring factor. */
public record PlanScoringWeights(
        long stepCountWeight,
        long machineKindWeight,
        long rawMaterialKindWeight,
        long unavailableCapabilityWeight,
        long modPreferenceRankWeight,
        long unownedBasisPointWeight,
        long processingTickWeight,
        long unknownProcessingTimePenalty,
        long goalPreferenceMultiplier) {
    public static final long MAX_WEIGHT = 1_000_000_000L;
    public static final long MAX_PREFERENCE_MULTIPLIER = 10L;

    public PlanScoringWeights {
        requireWeight(stepCountWeight, "stepCountWeight");
        requireWeight(machineKindWeight, "machineKindWeight");
        requireWeight(rawMaterialKindWeight, "rawMaterialKindWeight");
        requireWeight(unavailableCapabilityWeight, "unavailableCapabilityWeight");
        requireWeight(modPreferenceRankWeight, "modPreferenceRankWeight");
        requireWeight(unownedBasisPointWeight, "unownedBasisPointWeight");
        requireWeight(processingTickWeight, "processingTickWeight");
        requireWeight(unknownProcessingTimePenalty, "unknownProcessingTimePenalty");
        if (goalPreferenceMultiplier < 1
                || goalPreferenceMultiplier > MAX_PREFERENCE_MULTIPLIER) {
            throw new IllegalArgumentException("goalPreferenceMultiplier must be between 1 and 10");
        }
        if (stepCountWeight == 0
                && machineKindWeight == 0
                && rawMaterialKindWeight == 0
                && unavailableCapabilityWeight == 0
                && modPreferenceRankWeight == 0
                && unownedBasisPointWeight == 0
                && processingTickWeight == 0
                && unknownProcessingTimePenalty == 0) {
            throw new IllegalArgumentException("At least one scoring weight must be positive");
        }
    }

    private static void requireWeight(long value, String name) {
        if (value < 0 || value > MAX_WEIGHT) {
            throw new IllegalArgumentException(name + " must be nonnegative and bounded");
        }
    }
}
