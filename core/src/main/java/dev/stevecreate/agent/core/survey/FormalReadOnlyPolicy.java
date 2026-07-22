package dev.stevecreate.agent.core.survey;

import java.nio.file.Path;
import java.util.Objects;

/** Exact approved roots; the audit destination must be outside the complete formal instance. */
public record FormalReadOnlyPolicy(
        String policyVersion,
        String worldIdentity,
        Path formalInstanceRoot,
        Path approvedSaveRoot,
        Path auditRoot) {
    public FormalReadOnlyPolicy {
        policyVersion = text(policyVersion, "policyVersion");
        worldIdentity = text(worldIdentity, "worldIdentity");
        Objects.requireNonNull(formalInstanceRoot, "formalInstanceRoot");
        Objects.requireNonNull(approvedSaveRoot, "approvedSaveRoot");
        Objects.requireNonNull(auditRoot, "auditRoot");
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 4_096) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
