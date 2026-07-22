package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import java.util.List;
import java.util.Objects;

public record ClaimPermissionDiscoveryResult(
        List<ClaimModDiscovery> detectedMods,
        PermissionDecision permissionDecision,
        List<String> requiredAdapterTasks,
        List<SurveyLimitation> limitations,
        boolean credentialsRead,
        boolean privateDatabaseRead,
        boolean remoteAccessPerformed,
        boolean claimModified,
        boolean formalExecutionAllowed) {
    public ClaimPermissionDiscoveryResult {
        detectedMods = List.copyOf(detectedMods);
        Objects.requireNonNull(permissionDecision, "permissionDecision");
        requiredAdapterTasks = SurveyModelValues.texts(requiredAdapterTasks, "requiredAdapterTasks", 64);
        limitations = List.copyOf(limitations);
        if (permissionDecision != PermissionDecision.UNKNOWN || limitations.isEmpty()
                || credentialsRead || privateDatabaseRead || remoteAccessPerformed
                || claimModified || formalExecutionAllowed) {
            throw new IllegalArgumentException("formal claim discovery must remain unknown and non-authoritative");
        }
    }
}
