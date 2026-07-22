package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import java.util.List;
import java.util.Objects;

public record FormalClaimPermissionReadiness(
        String worldIdentity,
        String candidatePackageIdentity,
        PermissionDecision permission,
        List<String> adapterBacklog,
        boolean privateDatabaseParsed,
        boolean claimModified,
        boolean permissionWritten,
        boolean readinessAllowed) {
    public FormalClaimPermissionReadiness {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(candidatePackageIdentity, "candidatePackageIdentity");
        Objects.requireNonNull(permission, "permission");
        adapterBacklog = List.copyOf(Objects.requireNonNull(adapterBacklog, "adapterBacklog"));
        if (permission != PermissionDecision.UNKNOWN || adapterBacklog.size() > 64
                || privateDatabaseParsed || claimModified || permissionWritten || readinessAllowed) {
            throw new IllegalArgumentException("UNKNOWN formal claim permission must fail closed");
        }
    }
}
