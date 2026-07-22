package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;

public record SurveyLocation(
        String worldIdentity,
        ResourceId dimension,
        int regionX,
        int regionZ,
        int chunkX,
        int chunkZ,
        BlockPos3i position,
        String fileFingerprint,
        long parseGeneration,
        SurveyEvidenceSource evidenceSource,
        List<String> warningsAndLimitations) {
    public SurveyLocation {
        worldIdentity = SurveyModelValues.text(worldIdentity, "worldIdentity", 4_096);
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        fileFingerprint = SurveyModelValues.hash(fileFingerprint, "fileFingerprint");
        if (parseGeneration < 0) throw new IllegalArgumentException("parseGeneration is negative");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        warningsAndLimitations = SurveyModelValues.texts(
                warningsAndLimitations, "warningsAndLimitations", 64);
        if (Math.floorDiv(chunkX, 32) != regionX || Math.floorDiv(chunkZ, 32) != regionZ) {
            throw new IllegalArgumentException("chunk coordinates do not belong to the region");
        }
    }
}
