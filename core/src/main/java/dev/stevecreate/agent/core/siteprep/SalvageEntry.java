package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.ResourceId;

public record SalvageEntry(
        String obstacleIdentity,
        ResourceId resourceId,
        int expectedCount,
        int collectedCount,
        int deliveredCount,
        String executorIdentity) {
    public SalvageEntry {
        obstacleIdentity = SitePreparationHashes.text(obstacleIdentity, "obstacleIdentity");
        if (resourceId == null) throw new NullPointerException("resourceId");
        executorIdentity = SitePreparationHashes.text(executorIdentity, "executorIdentity");
        if (expectedCount < 0 || collectedCount < 0 || deliveredCount < 0
                || deliveredCount > collectedCount) {
            throw new IllegalArgumentException("salvage quantities are invalid");
        }
    }
}
