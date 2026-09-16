package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.time.Instant;

public record TerrainMutationEvidence(
        BlockPos3i position,
        String beforeStateFingerprint,
        String afterStateFingerprint,
        String executorIdentity,
        Instant observedAt) {
    public TerrainMutationEvidence {
        if (position == null || observedAt == null) throw new NullPointerException();
        beforeStateFingerprint = SitePreparationHashes.hash(
                beforeStateFingerprint, "beforeStateFingerprint");
        afterStateFingerprint = SitePreparationHashes.hash(
                afterStateFingerprint, "afterStateFingerprint");
        executorIdentity = SitePreparationHashes.text(executorIdentity, "executorIdentity");
    }
}
