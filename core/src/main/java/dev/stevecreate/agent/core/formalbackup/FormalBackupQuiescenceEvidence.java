package dev.stevecreate.agent.core.formalbackup;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Structured observation required immediately before any formal source backup. */
public record FormalBackupQuiescenceEvidence(
        Instant observedAt,
        String observerVersion,
        List<Long> formalWorldJavaProcessIds,
        List<String> formalSourceHandleOwners,
        boolean processScanComplete,
        boolean sourceHandleScanComplete) {
    public FormalBackupQuiescenceEvidence {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(observerVersion, "observerVersion");
        if (observerVersion.isBlank() || observerVersion.length() > 256) {
            throw new IllegalArgumentException("observerVersion is invalid");
        }
        formalWorldJavaProcessIds = List.copyOf(Objects.requireNonNull(
                formalWorldJavaProcessIds, "formalWorldJavaProcessIds"));
        formalSourceHandleOwners = List.copyOf(Objects.requireNonNull(formalSourceHandleOwners, "formalSourceHandleOwners"));
        if (formalWorldJavaProcessIds.size() > 1_024
                || formalWorldJavaProcessIds.stream().anyMatch(pid -> pid == null || pid <= 0)
                || formalSourceHandleOwners.size() > 1_024
                || formalSourceHandleOwners.stream().anyMatch(value ->
                        value == null || value.isBlank() || value.length() > 1_024)) {
            throw new IllegalArgumentException("quiescence observations are invalid");
        }
    }

    public boolean quiescent() {
        return processScanComplete && sourceHandleScanComplete
                && formalWorldJavaProcessIds.isEmpty() && formalSourceHandleOwners.isEmpty();
    }

    public boolean freshAt(Instant operationTime, Duration maximumAge) {
        Objects.requireNonNull(operationTime, "operationTime");
        Objects.requireNonNull(maximumAge, "maximumAge");
        if (maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
        return !observedAt.isAfter(operationTime) && !observedAt.isBefore(operationTime.minus(maximumAge));
    }
}
