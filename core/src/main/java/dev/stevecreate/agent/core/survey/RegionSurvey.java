package dev.stevecreate.agent.core.survey;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record RegionSurvey(
        int regionX,
        int regionZ,
        String relativeRegionPath,
        String fileFingerprint,
        SurveyStatus status,
        List<ChunkSurvey> chunks,
        List<SurveyEvidence> evidence,
        List<SurveyLimitation> limitations) {
    public RegionSurvey {
        relativeRegionPath = SurveyModelValues.text(relativeRegionPath, "relativeRegionPath", 16_384);
        fileFingerprint = SurveyModelValues.hash(fileFingerprint, "fileFingerprint");
        Objects.requireNonNull(status, "status");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (chunks.size() > 1_024 || evidence.size() > 4_096 || limitations.size() > 4_096) {
            throw new IllegalArgumentException("region survey is too large");
        }
        List<ChunkSurvey> sorted = chunks.stream().sorted(Comparator.comparingInt(ChunkSurvey::chunkX)
                .thenComparingInt(ChunkSurvey::chunkZ)).toList();
        if (!chunks.equals(sorted) || chunks.stream().anyMatch(chunk ->
                Math.floorDiv(chunk.chunkX(), 32) != regionX || Math.floorDiv(chunk.chunkZ(), 32) != regionZ)) {
            throw new IllegalArgumentException("chunks are unsorted or outside region");
        }
    }
}
