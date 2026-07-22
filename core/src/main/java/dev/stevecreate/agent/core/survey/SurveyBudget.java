package dev.stevecreate.agent.core.survey;

/** Explicit hard limits for one deterministic offline survey pass. */
public record SurveyBudget(
        int maximumRegions,
        int maximumChunks,
        long maximumReadBytes,
        long maximumDurationMillis,
        long maximumRetainedBytes,
        int maximumCandidateZones,
        int maximumDeepScanRadius,
        int parallelism) {
    private static final long MAXIMUM_BYTES = 8L * 1_024 * 1_024 * 1_024 * 1_024;
    private static final long MAXIMUM_RETAINED_BYTES = 16L * 1_024 * 1_024 * 1_024;

    public SurveyBudget {
        if (maximumRegions < 1 || maximumRegions > 16_384
                || maximumChunks < 1 || maximumChunks > 16_777_216
                || maximumReadBytes < 1 || maximumReadBytes > MAXIMUM_BYTES
                || maximumDurationMillis < 1 || maximumDurationMillis > 86_400_000
                || maximumRetainedBytes < 1 || maximumRetainedBytes > MAXIMUM_RETAINED_BYTES
                || maximumCandidateZones < 1 || maximumCandidateZones > 256
                || maximumDeepScanRadius < 0 || maximumDeepScanRadius > 128
                || parallelism != 1) {
            throw new IllegalArgumentException("survey budget is invalid or not single-threaded");
        }
    }

    public String canonical() {
        return "regions=" + maximumRegions
                + ",chunks=" + maximumChunks
                + ",readBytes=" + maximumReadBytes
                + ",durationMillis=" + maximumDurationMillis
                + ",retainedBytes=" + maximumRetainedBytes
                + ",candidateZones=" + maximumCandidateZones
                + ",deepScanRadius=" + maximumDeepScanRadius
                + ",parallelism=" + parallelism;
    }
}
