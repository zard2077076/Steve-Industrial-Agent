package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.GenericStepRunState;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.RecoveryPlanSnapshot;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Shared exact BUILD-prefix recovery validation for Create 6.0.6 physical handlers. */
final class Create606PreResourceRecovery {
    private Create606PreResourceRecovery() {}

    static String validate(
            String capability,
            GenericExecutionPlan trustedPlan,
            GenericExecutionSession session,
            WorldChangeJournal journal,
            List<BlockPos3i> expectedPlacements,
            ResourceId buildStepId,
            ResourceId powerStepId) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(trustedPlan, "trustedPlan");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(journal, "journal");
        expectedPlacements = List.copyOf(expectedPlacements);
        Objects.requireNonNull(buildStepId, "buildStepId");
        Objects.requireNonNull(powerStepId, "powerStepId");

        if (!RecoveryPlanSnapshot.capture(session.plan()).equals(
                RecoveryPlanSnapshot.capture(trustedPlan))) {
            return "Recovered " + capability
                    + " generic plan does not match the trusted physical plan";
        }
        if (session.status() != GenericExecutionSessionStatus.RUNNING
                || session.currentStep().isEmpty()) {
            return capability
                    + " recovery requires one running BUILD or pre-resource POWER step";
        }
        ResourceId currentStep = session.currentStep().orElseThrow().stepId();
        boolean partialBuild = currentStep.equals(buildStepId)
                && session.completedStepIds().isEmpty();
        boolean buildComplete = currentStep.equals(powerStepId)
                && session.stepRunState() == GenericStepRunState.READY
                && session.completedStepIds().equals(List.of(buildStepId));
        if (!partialBuild && !buildComplete) {
            return capability
                    + " can be recovered only at an atomic BUILD boundary"
                    + " or BUILD-complete POWER/READY";
        }
        if (!session.sessionId().equals(journal.sessionId())
                || !session.worldChanges().equals(journal.references())) {
            return "Recovered " + capability + " session and world-change journal differ";
        }
        if (journal.entries().size() > expectedPlacements.size()
                || journal.entries().stream()
                        .anyMatch(entry -> !(entry instanceof BlockChange))) {
            return capability
                    + " recovery boundary must contain a bounded block-only build prefix";
        }
        List<BlockPos3i> actual = journal.entries().stream()
                .map(BlockChange.class::cast)
                .map(BlockChange::position)
                .toList();
        if (!actual.equals(expectedPlacements.subList(0, actual.size()))) {
            return "Recovered " + capability
                    + " block-change sequence does not match the physical build prefix";
        }
        if (!journal.modifiedPositions().equals(
                List.copyOf(new LinkedHashSet<>(actual)))) {
            return "Recovered " + capability
                    + " modified positions do not match the physical build prefix";
        }
        if (buildComplete && actual.size() != expectedPlacements.size()) {
            return "Recovered " + capability
                    + " BUILD-complete journal omits physical placements";
        }
        for (WorldChangeJournal.Entry entry : journal.entries()) {
            BlockChange block = (BlockChange) entry;
            if (!block.sourceStepId().equals(buildStepId)) {
                return "Recovered " + capability
                        + " block journal contains a non-BUILD change";
            }
        }
        return null;
    }

    static int recoveredPlacementCursor(
            String capability,
            int placementCount,
            WorldChangeJournal journal) {
        if (journal.entries().size() > placementCount) {
            throw new IllegalArgumentException(
                    "Recovered " + capability + " placement prefix is too large");
        }
        return journal.entries().size();
    }
}
