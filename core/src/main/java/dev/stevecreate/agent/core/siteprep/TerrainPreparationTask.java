package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.List;
import java.util.Set;

public record TerrainPreparationTask(
        String taskIdentity,
        TerrainPreparationTaskKind kind,
        List<BlockPos3i> positions,
        Set<String> predecessorTaskIdentities,
        int maximumMutations,
        long maximumTicks) {
    public TerrainPreparationTask {
        taskIdentity = SitePreparationHashes.text(taskIdentity, "taskIdentity");
        if (kind == null) throw new NullPointerException("kind");
        positions = List.copyOf(positions);
        predecessorTaskIdentities = Set.copyOf(predecessorTaskIdentities);
        if (positions.size() > 4_096 || predecessorTaskIdentities.size() > 64
                || maximumMutations < 0 || maximumMutations > positions.size()
                || maximumTicks < 1 || maximumTicks > 72_000) {
            throw new IllegalArgumentException("terrain task is unbounded or inconsistent");
        }
    }
}
