package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Optional;

public record FormalSurveyCommandPlan(
        FormalSurveyCommand command,
        Optional<String> worldIdentity,
        Optional<ResourceId> target,
        long quantity,
        String reportRelativePath,
        boolean formalReadRequested,
        boolean arbitraryPathAccepted,
        boolean processStarted,
        boolean sessionCreated,
        boolean formalWorldWrite,
        boolean inventoryContentsRead,
        boolean approvalCreated,
        boolean executionAllowed) {
    public FormalSurveyCommandPlan {
        java.util.Objects.requireNonNull(command, "command");
        worldIdentity = java.util.Objects.requireNonNull(worldIdentity, "worldIdentity");
        target = java.util.Objects.requireNonNull(target, "target");
        reportRelativePath = SurveyModelValues.text(reportRelativePath, "reportRelativePath", 1_024);
        if (!reportRelativePath.matches("formal-survey/(?:world-index|[0-9a-f]{64})/[a-z-]+\\.json")
                || arbitraryPathAccepted || processStarted || sessionCreated || formalWorldWrite
                || inventoryContentsRead || approvalCreated || executionAllowed
                || command == FormalSurveyCommand.LIST_WORLDS
                    && (worldIdentity.isPresent() || target.isPresent() || quantity != 0 || formalReadRequested)
                || command != FormalSurveyCommand.LIST_WORLDS
                    && (worldIdentity.isEmpty() || !formalReadRequested)
                || command == FormalSurveyCommand.PREVIEW
                    && (target.isEmpty() || quantity < 1)
                || command != FormalSurveyCommand.PREVIEW
                    && (target.isPresent() || quantity != 0)) {
            throw new IllegalArgumentException("formal survey command plan is invalid or authoritative");
        }
    }
}
