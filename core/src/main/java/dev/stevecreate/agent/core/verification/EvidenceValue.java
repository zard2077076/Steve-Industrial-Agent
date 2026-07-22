package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** A bounded value plus schema identity, suitable for deterministic observed/expected comparison. */
public record EvidenceValue(ResourceId schemaId, String value) {
    public static final int MAX_VALUE_LENGTH = 1_024;

    public EvidenceValue {
        Objects.requireNonNull(schemaId, "schemaId");
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "value must contain 1 to " + MAX_VALUE_LENGTH + " characters");
        }
    }
}
