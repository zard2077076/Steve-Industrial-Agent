package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record BotClearingUpdate(
        BotClearingState state,
        int completedTasks,
        int actualMutations,
        SalvageLedger salvageLedger,
        List<TerrainMutationEvidence> mutationEvidence,
        Optional<ResourceId> failureCode,
        Set<ResourceId> evidenceIds,
        Map<ResourceId, String> evidenceFields,
        String detail) {
    public BotClearingUpdate {
        if (state == null || salvageLedger == null) throw new NullPointerException();
        mutationEvidence = List.copyOf(mutationEvidence);
        failureCode = Optional.ofNullable(failureCode).orElseThrow();
        evidenceIds = Set.copyOf(evidenceIds);
        evidenceFields = Map.copyOf(evidenceFields);
        if (completedTasks < 0 || actualMutations < 0 || actualMutations > 4_096
                || mutationEvidence.size() > 4_096 || evidenceIds.size() > 256
                || evidenceFields.size() > 256) {
            throw new IllegalArgumentException("Bot clearing update is unbounded");
        }
        if ((state == BotClearingState.FAILED) != failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a failed Bot clearing update carries a typed failure code");
        }
        detail = SitePreparationHashes.text(detail, "detail");
    }
}
