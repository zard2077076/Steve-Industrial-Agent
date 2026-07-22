package dev.stevecreate.agent.core.survey;

public record SurveyBudgetUsage(
        int regions,
        int chunks,
        long readBytes,
        long elapsedMillis,
        long retainedBytes,
        int candidateZones,
        int maximumDeepScanRadiusObserved) {
    public SurveyBudgetUsage {
        if (regions < 0 || chunks < 0 || readBytes < 0 || elapsedMillis < 0
                || retainedBytes < 0 || candidateZones < 0 || maximumDeepScanRadiusObserved < 0) {
            throw new IllegalArgumentException("survey usage is negative");
        }
    }

    public String canonical() {
        return "regions=" + regions
                + ",chunks=" + chunks
                + ",readBytes=" + readBytes
                + ",elapsedMillis=" + elapsedMillis
                + ",retainedBytes=" + retainedBytes
                + ",candidateZones=" + candidateZones
                + ",deepScanRadius=" + maximumDeepScanRadiusObserved;
    }
}
