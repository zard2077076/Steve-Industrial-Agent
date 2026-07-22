package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.ResourceId;

import java.util.List;
import java.util.Objects;

public record InfrastructureClassificationContext(
        String worldIdentity,
        ResourceId dimension,
        int regionX,
        int regionZ,
        String relativeSourcePath,
        String fileFingerprint,
        long parseGeneration,
        List<DeploymentBoundingBox> candidateBounds) {
    public InfrastructureClassificationContext {
        worldIdentity = SurveyModelValues.text(worldIdentity, "worldIdentity", 4_096);
        Objects.requireNonNull(dimension, "dimension");
        relativeSourcePath = SurveyModelValues.text(relativeSourcePath, "relativeSourcePath", 16_384);
        if (relativeSourcePath.startsWith("/") || relativeSourcePath.contains("..")
                || relativeSourcePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relativeSourcePath is unsafe");
        }
        fileFingerprint = SurveyModelValues.hash(fileFingerprint, "fileFingerprint");
        if (parseGeneration < 0) throw new IllegalArgumentException("parseGeneration is negative");
        candidateBounds = List.copyOf(Objects.requireNonNull(candidateBounds, "candidateBounds"));
        if (candidateBounds.size() > 256) throw new IllegalArgumentException("too many candidate bounds");
    }
}
