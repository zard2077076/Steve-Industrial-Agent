package dev.stevecreate.agent.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.execution.BoundedExecutionStep;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericStepRunState;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.RescanRequired;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSession;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSessionReason;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCheckpointTest {
    private static final ResourceId PLAN_ID = id("test:recovery/plan");
    private static final ResourceId GRAPH_ID = id("test:recovery/graph");
    private static final ResourceId PREPARE_STEP = id("test:recovery/prepare");
    private static final ResourceId RESUME_STEP = id("test:recovery/resume");
    private static final ResourceId SESSION_ID = id("test:recovery/session");
    private static final BlockPos3i POSITION = new BlockPos3i(4, 70, 6);

    @Test
    void roundTripsTheCompleteCheckpointCanonically() {
        Fixture fixture = fixture(plan(0, 40));

        byte[] encoded = RecoveryCheckpointCodec.encode(fixture.checkpoint());
        RecoveryCheckpoint decoded = RecoveryCheckpointCodec.decode(encoded);

        assertThat(decoded).isEqualTo(fixture.checkpoint());
        assertThat(RecoveryCheckpointCodec.encode(decoded)).containsExactly(encoded);
        assertThat(decoded.plan().planId()).isEqualTo(PLAN_ID);
        assertThat(decoded.plan().graph().graphId()).isEqualTo(GRAPH_ID);
        assertThat(decoded.session().currentStepId()).contains(RESUME_STEP);
        assertThat(decoded.session().completedStepIds()).containsExactly(PREPARE_STEP);
        assertThat(decoded.modifiedPositions()).containsExactly(POSITION);
        assertThat(decoded.session().evidence()).hasSize(1);
        assertThat(decoded.journal().entries()).hasSize(1);

        BlockChange original = (BlockChange) fixture.journal().entries().get(0);
        Map<String, String> reorderedProperties = new LinkedHashMap<>();
        reorderedProperties.put("waterlogged", "false");
        reorderedProperties.put("axis", "x");
        WorldBlockSnapshot reorderedAfter = new WorldBlockSnapshot(
                original.after().blockId(), reorderedProperties, Optional.empty());
        BlockChange reorderedChange = new BlockChange(
                original.changeId(),
                original.sessionId(),
                original.sourceStepId(),
                original.recordedTick(),
                original.position(),
                original.before(),
                reorderedAfter);
        WorldChangeJournal reorderedJournal = new WorldChangeJournal(
                SESSION_ID, List.of(reorderedChange));
        RecoveryCheckpoint reorderedCheckpoint = new RecoveryCheckpoint(
                fixture.checkpoint().plan(),
                fixture.checkpoint().session(),
                reorderedJournal,
                fixture.checkpoint().modifiedPositions(),
                fixture.checkpoint().savedTick());
        assertThat(reorderedCheckpoint).isEqualTo(fixture.checkpoint());
        assertThat(RecoveryCheckpointCodec.encode(reorderedCheckpoint))
                .containsExactly(encoded);
    }

    @Test
    void planAndGraphFingerprintsDetectTrustedDefinitionDrift() {
        RecoveryPlanSnapshot original = RecoveryPlanSnapshot.capture(plan(0, 40));
        RecoveryPlanSnapshot graphChanged = RecoveryPlanSnapshot.capture(plan(1, 40));
        RecoveryPlanSnapshot processChanged = RecoveryPlanSnapshot.capture(plan(0, 41));

        assertThat(graphChanged.graph().graphId()).isEqualTo(original.graph().graphId());
        assertThat(graphChanged.graph().fingerprint()).isNotEqualTo(original.graph().fingerprint());
        assertThat(graphChanged.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(processChanged.graph()).isEqualTo(original.graph());
        assertThat(processChanged.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(RecoveryPlanSnapshot.capture(plan(0, 40))).isEqualTo(original);
    }

    @Test
    void capturesCanonicalProductionCreatePlanTopologiesWithoutAnAbsoluteAnchor() {
        RecoveryPlanSnapshot milling = RecoveryPlanSnapshot.capture(
                WaterWheelMillstoneGenericExecutionPlan.from(
                        WaterWheelMillstonePlan.at(new BlockPos3i(0, 64, 0))));
        RecoveryPlanSnapshot shiftedMilling = RecoveryPlanSnapshot.capture(
                WaterWheelMillstoneGenericExecutionPlan.from(
                        WaterWheelMillstonePlan.at(new BlockPos3i(40, 80, -20))));
        RecoveryPlanSnapshot pressing = RecoveryPlanSnapshot.capture(
                BeltPressGenericExecutionPlan.from(
                        BeltPressPlan.at(new BlockPos3i(0, 64, 0))));
        RecoveryPlanSnapshot shiftedPressing = RecoveryPlanSnapshot.capture(
                BeltPressGenericExecutionPlan.from(
                        BeltPressPlan.at(new BlockPos3i(-30, 70, 25))));

        assertThat(milling.graph().nodeIds()).hasSize(6);
        assertThat(milling.graph().portIds()).hasSize(10);
        assertThat(milling.graph().edgeIds()).hasSize(5);
        assertThat(pressing.graph().nodeIds()).hasSize(9);
        assertThat(pressing.graph().portIds()).hasSize(20);
        assertThat(pressing.graph().edgeIds()).hasSize(10);
        assertThat(shiftedMilling).isEqualTo(milling);
        assertThat(shiftedPressing).isEqualTo(pressing);
        assertThat(pressing.fingerprint()).isNotEqualTo(milling.fingerprint());
    }

    @Test
    void rejectsCorruptionUnknownVersionTrailingBytesAndOversizedFrames() {
        byte[] encoded = RecoveryCheckpointCodec.encode(fixture(plan(0, 40)).checkpoint());
        byte[] corrupt = encoded.clone();
        corrupt[12] ^= 0x01;
        byte[] wrongVersion = encoded.clone();
        wrongVersion[4] = 0;
        wrongVersion[5] = 2;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecoveryCheckpointCodec.decode(corrupt))
                .withMessage("Invalid recovery checkpoint");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecoveryCheckpointCodec.decode(wrongVersion))
                .withMessage("Invalid recovery checkpoint");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecoveryCheckpointCodec.decode(
                        Arrays.copyOf(encoded, encoded.length + 1)))
                .withMessage("Invalid recovery checkpoint");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RecoveryCheckpointCodec.decode(new byte[
                        RecoveryCheckpointCodec.MAX_ENCODED_BYTES + 32]))
                .withMessage("Recovery checkpoint frame length is invalid");
    }

    @Test
    void discoveryRequiresAnExactRescanBeforeItExposesAResumableSession() {
        Fixture fixture = fixture(plan(0, 40));

        var discovery = SessionRecoveryReconciler.discover(
                fixture.checkpoint(), Map.of(PLAN_ID, fixture.plan()));

        assertThat(discovery).isInstanceOf(RescanRequired.class);
        RescanRequired candidate = (RescanRequired) discovery;
        assertThat(candidate.sessionId()).isEqualTo(SESSION_ID);
        assertThat(candidate.planId()).isEqualTo(PLAN_ID);
        assertThat(candidate.requiredPositions()).containsExactly(POSITION);

        var reconciled = candidate.reconcile(Map.of(POSITION, fixture.after()));
        assertThat(reconciled).isInstanceOf(Resumable.class);
        Resumable resumable = (Resumable) reconciled;
        assertThat(resumable.session()).isEqualTo(fixture.session());
        assertThat(resumable.session().stepRunState()).isEqualTo(GenericStepRunState.ACTION);
        assertThat(resumable.journal()).isEqualTo(fixture.journal());
        assertThat(resumable.verifiedPositions()).containsExactly(POSITION);
    }

    @Test
    void missingAndChangedWorldStateReturnTypedStaleSessionFailures() {
        Fixture fixture = fixture(plan(0, 40));
        RescanRequired candidate = (RescanRequired) SessionRecoveryReconciler.discover(
                fixture.checkpoint(), Map.of(PLAN_ID, fixture.plan()));

        StaleSession missing = (StaleSession) candidate.reconcile(Map.of());
        StaleSession changed = (StaleSession) candidate.reconcile(
                Map.of(POSITION, block("minecraft:dirt")));

        assertThat(missing.failureCode())
                .isEqualTo(SessionRecoveryReconciler.STALE_SESSION_FAILURE);
        assertThat(missing.reason()).isEqualTo(StaleSessionReason.WORLD_STATE_MISSING);
        assertThat(missing.position()).contains(POSITION);
        assertThat(changed.failureCode())
                .isEqualTo(SessionRecoveryReconciler.STALE_SESSION_FAILURE);
        assertThat(changed.reason()).isEqualTo(StaleSessionReason.WORLD_STATE_CHANGED);
        assertThat(changed.position()).contains(POSITION);
    }

    @Test
    void unknownAndChangedTrustedPlansAreStaleBeforeAnyWorldScan() {
        Fixture fixture = fixture(plan(0, 40));

        StaleSession unknown = (StaleSession) SessionRecoveryReconciler.discover(
                fixture.checkpoint(), Map.of());
        GenericExecutionPlan changedPlan = plan(1, 40);
        StaleSession changed = (StaleSession) SessionRecoveryReconciler.discover(
                fixture.checkpoint(), Map.of(PLAN_ID, changedPlan));

        assertThat(unknown.reason()).isEqualTo(StaleSessionReason.UNKNOWN_PLAN);
        assertThat(changed.reason()).isEqualTo(StaleSessionReason.GRAPH_CHANGED);
        assertThat(unknown.failureCode())
                .isEqualTo(SessionRecoveryReconciler.STALE_SESSION_FAILURE);
        assertThat(changed.failureCode())
                .isEqualTo(SessionRecoveryReconciler.STALE_SESSION_FAILURE);
    }

    @Test
    void terminalSessionsAndResourceHistoryNeverBecomeAutomaticResumeCandidates() {
        Fixture fixture = fixture(plan(0, 40));
        GenericExecutionSession cancelled = fixture.session().cancel(
                103, id("test:recovery/cancelled"));
        RecoveryCheckpoint terminal = RecoveryCheckpoint.capture(
                cancelled, fixture.journal(), 103);

        ProcessResource injected = resource("minecraft:cobblestone");
        InjectedResourceChange input = new InjectedResourceChange(
                id("test:recovery/change/input"),
                SESSION_ID,
                RESUME_STEP,
                102,
                POSITION,
                injected);
        WorldChangeJournal unsafeJournal = fixture.journal().append(input);
        GenericExecutionSession unsafeSession = fixture.session().recordWorldChange(
                input.reference());
        RecoveryCheckpoint unsafe = RecoveryCheckpoint.capture(
                unsafeSession, unsafeJournal, 102);

        StaleSession terminalResult = (StaleSession) SessionRecoveryReconciler.discover(
                terminal, Map.of(PLAN_ID, fixture.plan()));
        StaleSession unsafeResult = (StaleSession) SessionRecoveryReconciler.discover(
                unsafe, Map.of(PLAN_ID, fixture.plan()));

        assertThat(terminalResult.reason()).isEqualTo(StaleSessionReason.SESSION_TERMINAL);
        assertThat(unsafeResult.reason())
                .isEqualTo(StaleSessionReason.RESOURCE_HISTORY_UNSAFE);
    }

    private static Fixture fixture(GenericExecutionPlan plan) {
        GenericExecutionSession session = GenericExecutionSession.start(SESSION_ID, plan, 100)
                .beginAction(100)
                .recordEvidence(evidence(PREPARE_STEP, id("test:evidence/prepare"), 100))
                .beginVerification(100)
                .completeCurrentStep(100)
                .beginAction(101);
        WorldBlockSnapshot before = block("minecraft:air");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("axis", "x");
        properties.put("waterlogged", "false");
        WorldBlockSnapshot after = new WorldBlockSnapshot(
                id("minecraft:stone"), properties, Optional.empty());
        BlockChange change = new BlockChange(
                id("test:recovery/change/block"),
                SESSION_ID,
                RESUME_STEP,
                101,
                POSITION,
                before,
                after);
        WorldChangeJournal journal = WorldChangeJournal.empty(SESSION_ID).append(change);
        session = session.recordWorldChange(change.reference());
        RecoveryCheckpoint checkpoint = RecoveryCheckpoint.capture(session, journal, 102);
        return new Fixture(plan, session, journal, checkpoint, before, after);
    }

    private static GenericExecutionPlan plan(int nodeX, int maximumWaitTicks) {
        MachineNode node = new MachineNode(
                id("test:machine/node"),
                id("test:machine/role"),
                id("test:machine/implementation"),
                new BlockPos3i(nodeX, 0, 0),
                MachineOrientation.NONE,
                Set.of(id("test:capability/process")),
                Map.of("mode", "fixture"));
        UnifiedMachineGraph graph = new UnifiedMachineGraph(
                GRAPH_ID, List.of(node), List.of(), List.of());
        GenericProcessSpec process = new GenericProcessSpec(
                id("test:recipe/recovery"),
                id("test:recipe_type/fixture"),
                List.of(resource("test:input")),
                List.of(resource("test:output")),
                List.of(),
                Set.of(id("test:capability/process")),
                Set.of(id("test:evidence/resume")),
                maximumWaitTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(id("test:extension/mode"), "recovery"));
        return new GenericExecutionPlan(
                PLAN_ID,
                graph,
                process,
                List.of(
                        step(PREPARE_STEP, "prepare", "test:evidence/prepare"),
                        step(RESUME_STEP, "resume", "test:evidence/resume")));
    }

    private static BoundedExecutionStep step(
            ResourceId stepId,
            String name,
            String evidenceId) {
        return new BoundedExecutionStep(
                stepId,
                name.equals("prepare")
                        ? GenericExecutionPhase.PREPARE
                        : GenericExecutionPhase.BUILD,
                List.of(),
                new StepActionDescriptor(
                        id("test:handler/recovery"),
                        id("test:operation/" + name),
                        Map.of()),
                List.of(condition(name, "success")),
                List.of(condition(name, "failure")),
                200,
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                Set.of(id(evidenceId)));
    }

    private static StepCondition condition(String step, String kind) {
        return new StepCondition(
                id("test:condition/" + step + "_" + kind),
                id("test:evaluator/recovery"),
                id("test:condition_type/" + kind),
                Map.of());
    }

    private static VerificationEvidence evidence(
            ResourceId sourceStep,
            ResourceId requirement,
            long tick) {
        EvidenceValue value = new EvidenceValue(id("test:value/integer"), "1");
        return new VerificationEvidence(
                id("test:evidence/observation_" + tick),
                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                requirement,
                sourceStep,
                id("test:source/recovery_fixture"),
                id("test:machine/node"),
                value,
                value,
                tick,
                true,
                Optional.empty());
    }

    private static ProcessResource resource(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static WorldBlockSnapshot block(String value) {
        return new WorldBlockSnapshot(id(value), Map.of(), Optional.empty());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private record Fixture(
            GenericExecutionPlan plan,
            GenericExecutionSession session,
            WorldChangeJournal journal,
            RecoveryCheckpoint checkpoint,
            WorldBlockSnapshot before,
            WorldBlockSnapshot after) {
    }
}
