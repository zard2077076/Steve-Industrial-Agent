package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

public record ChunkSurvey(
        int chunkX,
        int chunkZ,
        SurveyStatus status,
        long compressedBytesRead,
        long uncompressedNbtBytes,
        List<InfrastructureFinding> findings,
        List<SurveyEvidence> evidence,
        List<SurveyLimitation> limitations) {
    public ChunkSurvey {
        Objects.requireNonNull(status, "status");
        if (compressedBytesRead < 0 || uncompressedNbtBytes < 0) throw new IllegalArgumentException("bytes are negative");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (findings.size() > 65_536 || evidence.size() > 4_096 || limitations.size() > 4_096) {
            throw new IllegalArgumentException("chunk survey is too large");
        }
        if (status == SurveyStatus.COMPLETE && !limitations.isEmpty()) {
            throw new IllegalArgumentException("complete chunk cannot retain limitations");
        }
    }
}
