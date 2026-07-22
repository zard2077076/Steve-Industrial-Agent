package dev.stevecreate.agent.core.survey;

import java.util.Comparator;
import java.util.List;

public record OfflineChunkSnapshot(
        int dataVersion,
        int chunkX,
        int chunkZ,
        int compressionType,
        int compressedBytes,
        int uncompressedNbtBytes,
        long parseGeneration,
        List<OfflineChunkSection> sections,
        List<OfflineBlockEntity> blockEntities) {
    public OfflineChunkSnapshot {
        if (dataVersion <= 0 || compressionType < 1 || compressionType > 3
                || compressedBytes <= 0 || uncompressedNbtBytes <= 0 || parseGeneration < 0) {
            throw new IllegalArgumentException("chunk metadata is invalid");
        }
        sections = List.copyOf(sections);
        blockEntities = List.copyOf(blockEntities);
        if (sections.size() > 256 || blockEntities.size() > 65_536) {
            throw new IllegalArgumentException("chunk snapshot is too large");
        }
        List<OfflineChunkSection> sorted = sections.stream()
                .sorted(Comparator.comparingInt(OfflineChunkSection::sectionY)).toList();
        if (!sections.equals(sorted)) throw new IllegalArgumentException("sections are not sorted");
        if (sections.stream().map(OfflineChunkSection::sectionY).distinct().count() != sections.size()) {
            throw new IllegalArgumentException("section Y values are not unique");
        }
    }
}
