package dev.stevecreate.agent.core.survey;

import java.util.Comparator;
import java.util.List;

public record OfflineRegionSnapshot(
        int regionX,
        int regionZ,
        String relativePath,
        String fileFingerprint,
        long parseGeneration,
        List<OfflineChunkSnapshot> chunks,
        List<FormalSurveyFailure> failures) {
    public OfflineRegionSnapshot {
        relativePath = SurveyModelValues.text(relativePath, "relativePath", 16_384);
        fileFingerprint = SurveyModelValues.hash(fileFingerprint, "fileFingerprint");
        if (parseGeneration < 0) throw new IllegalArgumentException("parseGeneration is negative");
        chunks = List.copyOf(chunks);
        failures = List.copyOf(failures);
        if (chunks.size() > 1_024 || failures.size() > 1_024) {
            throw new IllegalArgumentException("region snapshot is too large");
        }
        List<OfflineChunkSnapshot> sorted = chunks.stream()
                .sorted(Comparator.comparingInt(OfflineChunkSnapshot::chunkX)
                        .thenComparingInt(OfflineChunkSnapshot::chunkZ)).toList();
        if (!chunks.equals(sorted)) throw new IllegalArgumentException("chunks are not sorted");
    }
}
