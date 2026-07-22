package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import java.util.EnumSet;
import java.util.List;

public record FormalWorldDryRunBatch(
        String worldIdentity,
        String worldSnapshotFingerprint,
        String runtimeFingerprint,
        List<FormalWorldDryRunCandidate> candidates,
        PermissionDecision claimPermission,
        boolean inventoryContentsRead,
        boolean resourceOperationPerformed,
        boolean sessionCreated,
        boolean approvalCreated,
        boolean executionAllowed) {
    public FormalWorldDryRunBatch {
        worldIdentity = SurveyModelValues.text(worldIdentity, "worldIdentity", 4_096);
        if (!worldIdentity.matches("world:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("worldIdentity is invalid");
        }
        worldSnapshotFingerprint = SurveyModelValues.text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint", 16_384);
        runtimeFingerprint = SurveyModelValues.text(runtimeFingerprint, "runtimeFingerprint", 16_384);
        candidates = candidates.stream().sorted(java.util.Comparator.comparing(FormalWorldDryRunCandidate::kind))
                .toList();
        String expectedSnapshot = worldSnapshotFingerprint;
        String expectedRuntime = runtimeFingerprint;
        if (candidates.size() != 4
                || !EnumSet.copyOf(candidates.stream().map(FormalWorldDryRunCandidate::kind).toList())
                        .equals(EnumSet.allOf(FormalDryRunKind.class))
                || candidates.stream().map(candidate -> candidate.report().preview().previewHash()).distinct().count()
                        != candidates.size()
                || candidates.stream().anyMatch(candidate ->
                        !candidate.report().preview().worldSnapshotFingerprint().equals(expectedSnapshot)
                                || !candidate.report().preview().runtimeFingerprint().equals(expectedRuntime))
                || claimPermission != PermissionDecision.UNKNOWN || inventoryContentsRead
                || resourceOperationPerformed || sessionCreated || approvalCreated || executionAllowed) {
            throw new IllegalArgumentException("formal dry-run batch is incomplete, inconsistent or authoritative");
        }
    }
}
