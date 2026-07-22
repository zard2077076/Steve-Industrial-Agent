package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

/** Complete acceptance evidence for one unchanged guarded formal-world survey pass. */
public record FormalWorldSurveyRun(
        FormalWorldSurvey survey,
        FormalWorldFingerprint preFingerprint,
        FormalWorldFingerprint postFingerprint,
        OfflineTopologyGraph topology,
        SurveyBudget budget,
        List<FormalSurveyFailure> failures,
        boolean savesOrFormalWorldRead,
        boolean formalWorldWrite,
        boolean sessionLockCreatedOrModified,
        boolean processStarted,
        boolean inventoryContentsRead,
        boolean executionAllowed) {
    public FormalWorldSurveyRun {
        Objects.requireNonNull(survey, "survey");
        Objects.requireNonNull(preFingerprint, "preFingerprint");
        Objects.requireNonNull(postFingerprint, "postFingerprint");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(budget, "budget");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (!preFingerprint.exactlyMatches(postFingerprint)
                || !savesOrFormalWorldRead || formalWorldWrite || sessionLockCreatedOrModified
                || processStarted || inventoryContentsRead || executionAllowed) {
            throw new IllegalArgumentException("formal survey run violates its acceptance boundary");
        }
    }
}
