package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Complete loader-neutral claim/permission scope query. */
public record PermissionQuery(
        String queryId,
        RegionAuthorizedOperation operation,
        String actorIdentity,
        DeploymentBoundingBox region,
        String worldIdentity,
        WorldEnvironmentType environmentClassification,
        ResourceId dimensionId,
        String source,
        Instant requestedAt,
        long generation,
        String fingerprint) {
    public PermissionQuery {
        queryId = text(queryId, "queryId");
        Objects.requireNonNull(operation, "operation");
        actorIdentity = text(actorIdentity, "actorIdentity");
        Objects.requireNonNull(region, "region");
        worldIdentity = text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        Objects.requireNonNull(dimensionId, "dimensionId");
        source = text(source, "source");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (generation < 0) throw new IllegalArgumentException("generation cannot be negative");
        fingerprint = text(fingerprint, "fingerprint");
    }

    static String text(String value, String name) {
        return WorldEnvironmentEvidence.text(value, name);
    }
}
