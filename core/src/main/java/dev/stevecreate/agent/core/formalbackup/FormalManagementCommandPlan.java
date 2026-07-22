package dev.stevecreate.agent.core.formalbackup;

import java.util.Objects;
import java.util.Optional;

public record FormalManagementCommandPlan(
        FormalManagementCommandType type,
        Optional<String> worldIdentity,
        Optional<String> backupIdentity,
        Optional<String> candidateIdentity,
        String auditRelativeKey,
        boolean auditRequired,
        boolean arbitraryPathAccepted,
        boolean sessionCreated,
        boolean formalWorldWrite,
        boolean automaticallyApproved,
        boolean inventoryContentsRead,
        boolean executionAllowed) {
    public FormalManagementCommandPlan {
        Objects.requireNonNull(type, "type");
        worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
        backupIdentity = Objects.requireNonNull(backupIdentity, "backupIdentity");
        candidateIdentity = Objects.requireNonNull(candidateIdentity, "candidateIdentity");
        Objects.requireNonNull(auditRelativeKey, "auditRelativeKey");
        if (!auditRelativeKey.matches("[a-z0-9_-]+/[a-z0-9_-]+/[a-f0-9]{64}\\.json")
                || !auditRequired || arbitraryPathAccepted || sessionCreated || formalWorldWrite
                || automaticallyApproved || inventoryContentsRead || executionAllowed) {
            throw new IllegalArgumentException("formal management command plan carries unsafe authority");
        }
    }
}
