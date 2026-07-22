package dev.stevecreate.agent.core.survey;

import java.time.Duration;
import java.util.Objects;

public record SurveyCoverage(
        int regionsEnumerated,
        int regionsScanned,
        int chunksScanned,
        int chunksParsed,
        long bytesRead,
        Duration elapsed,
        boolean budgetExhausted) {
    public SurveyCoverage {
        Objects.requireNonNull(elapsed, "elapsed");
        if (regionsEnumerated < 0 || regionsScanned < 0 || chunksScanned < 0 || chunksParsed < 0
                || bytesRead < 0 || elapsed.isNegative()
                || regionsScanned > regionsEnumerated || chunksParsed > chunksScanned) {
            throw new IllegalArgumentException("coverage counters are inconsistent");
        }
    }
}
