package dev.stevecreate.agent.core.verification;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Optional bounded machine-readable diagnostic attached to one evidence observation. */
public record EvidenceDiagnostic(ResourceId code, String detail) {
    public static final int MAX_DETAIL_LENGTH = 512;

    public EvidenceDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "detail must contain 1 to " + MAX_DETAIL_LENGTH + " characters");
        }
    }
}
