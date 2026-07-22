package dev.stevecreate.agent.core.survey;

import java.util.Objects;

/** Explicit limits for one guarded formal-world acceptance pass. */
public record FormalSurveyRunConfiguration(
        SurveyBudget budget,
        int maximumFingerprintFiles,
        int maximumMetadataRegions,
        int hotSampleRegions,
        long parseGeneration,
        NbtReadLimits nbtReadLimits) {
    public FormalSurveyRunConfiguration {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(nbtReadLimits, "nbtReadLimits");
        if (maximumFingerprintFiles < 1 || maximumFingerprintFiles > 1_000_000
                || maximumMetadataRegions < 1 || maximumMetadataRegions > 16_384
                || hotSampleRegions < 1 || hotSampleRegions > maximumMetadataRegions
                || hotSampleRegions > budget.maximumRegions()
                || budget.maximumCandidateZones() > 16 || parseGeneration < 0) {
            throw new IllegalArgumentException("formal survey run configuration is invalid");
        }
    }
}
