package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;

import java.util.Objects;

/** Read-only region-file metadata and bounded scan estimates; it contains no chunk data. */
public record SurveyRegionMetadata(
        ResourceId dimension,
        int regionX,
        int regionZ,
        String relativePath,
        long fileBytes,
        int populatedChunks,
        long retainedBytesEstimate,
        long lastModifiedMillis,
        long activityTimestamp,
        int infrastructureHintCount) {
    public SurveyRegionMetadata {
        Objects.requireNonNull(dimension, "dimension");
        relativePath = SurveyModelValues.text(relativePath, "relativePath", 16_384);
        if (fileBytes < 1 || populatedChunks < 0 || populatedChunks > 1_024
                || retainedBytesEstimate < 0 || lastModifiedMillis < 0 || activityTimestamp < 0
                || infrastructureHintCount < 0 || infrastructureHintCount > 1_000_000) {
            throw new IllegalArgumentException("region metadata is invalid");
        }
    }

    String key() {
        return dimension + ":" + regionX + ":" + regionZ;
    }
}
