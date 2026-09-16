package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Exact item-stack identity without retaining a loader or game object. */
public record MaterialIdentity(ResourceId itemId, String payloadSha256) {
    public static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public MaterialIdentity {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        if (!payloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("material payload hash must be lowercase SHA-256");
        }
    }
}
