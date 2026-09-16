package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.BoundedExecutionStep;
import dev.stevecreate.agent.core.execution.BoundedStepRunner;
import dev.stevecreate.agent.core.execution.ConditionEvaluation;
import dev.stevecreate.agent.core.execution.GenericExecutionPhase;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepCondition;
import dev.stevecreate.agent.core.execution.StepRunOutcome;
import dev.stevecreate.agent.core.execution.StepRunResult;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.RescanRequired;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.Resumable;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSession;
import dev.stevecreate.agent.core.recovery.SessionRecoveryReconciler.StaleSessionReason;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import dev.stevecreate.agent.forge1201.recovery.ForgeRecoveryWorldScanner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/** Two-process isolated acceptance for persisted discovery, rescan, resume and stale refusal. */
public final class RecoveryReloadAcceptanceFixture {
    private static final ResourceId PLAN_ID = id("steve_industrial:acceptance/recovery_reload_plan");
    private static final ResourceId GRAPH_ID = id("steve_industrial:acceptance/recovery_reload_graph");
    private static final ResourceId SESSION_ID = id("steve_industrial:acceptance/recovery_reload_session");
    private static final ResourceId PREPARE_STEP = id("steve_industrial:acceptance/recovery_prepare");
    private static final ResourceId RESUME_STEP = id("steve_industrial:acceptance/recovery_resume");
    private static final ResourceId HANDLER_ID = id("steve_industrial:acceptance/recovery_handler");
    private static final ResourceId EVALUATOR_ID = id("steve_industrial:acceptance/recovery_evaluator");
    private static final ResourceId PREPARE_OPERATION = id("steve_industrial:acceptance/prepare");
    private static final ResourceId RESUME_OPERATION = id("steve_industrial:acceptance/resume");
    private static final ResourceId PREPARE_EVIDENCE = id("steve_industrial:acceptance/evidence_prepare");
    private static final ResourceId RESUME_EVIDENCE = id("steve_industrial:acceptance/evidence_resume");

    private RecoveryReloadAcceptanceFixture() {
    }

    public static void run(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("RecoveryReloadAcceptanceFixture");
        try {
            if ("write".equals(phase)) {
                write(server, logger);
            } else if ("read".equals(phase)) {
                read(server, logger);
            } else {
                throw new IllegalArgumentException("Unknown recovery reload phase: " + phase);
            }
        } catch (RuntimeException exception) {
            logger.error(
                    "RECOVERY_RELOAD_ACCEPTANCE FAIL phase={} detail=\"{}\"",
                    phase,
                    exception.getMessage());
            server.halt(false);
            throw exception;
        }
    }

    private static void write(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(!savedData.hasCheckpoint(), "Write phase found an existing checkpoint");
        BlockPos3i position = fixturePosition(level);
        WorldBlockSnapshot initial = scanOne(level, position);
        check(!initial.blockId().equals(id("minecraft:stone")),
                "Recovery fixture target already contains stone");
        check(level.getBlockState(blockPos(position)).canBeReplaced(),
                "Recovery fixture target is not replaceable");

        GenericExecutionPlan plan = plan();
        AtomicReference<WorldChangeJournal> journal = new AtomicReference<>(
                WorldChangeJournal.empty(SESSION_ID));
        StepActionHandler handler = (action, context) -> {
            if (action.operationId().equals(PREPARE_OPERATION)) {
                return ActionHandlerResult.succeeded(
                        List.of(evidence(context, PREPARE_EVIDENCE, "prepare")),
                        List.of());
            }
            if (!action.operationId().equals(RESUME_OPERATION)) {
                return ActionHandlerResult.failed(
                        id("steve_industrial:acceptance/unknown_operation"),
                        "Unexpected recovery acceptance operation");
            }
            WorldBlockSnapshot before = scanOne(level, position);
            check(level.setBlockAndUpdate(
                            blockPos(position), Blocks.STONE.defaultBlockState()),
                    "World rejected recovery acceptance stone placement");
            WorldBlockSnapshot after = scanOne(level, position);
            BlockChange change = new BlockChange(
                    id("steve_industrial:acceptance/recovery_change_0001"),
                    context.sessionId(),
                    context.stepId(),
                    context.gameTick(),
                    position,
                    before,
                    after);
            journal.set(journal.get().append(change));
            return ActionHandlerResult.inProgress(true, List.of(change.reference()));
        };
        BoundedStepRunner runner = runner(handler);
        long startTick = level.getGameTime();
        GenericExecutionSession session = GenericExecutionSession.start(
                SESSION_ID, plan, startTick);
        StepRunResult prepared = runner.tick(session, startTick);
        check(prepared.outcome() == StepRunOutcome.STEP_COMPLETED,
                "Recovery prepare step did not complete: " + prepared.outcome());
        StepRunResult changed = runner.tick(prepared.session(), startTick + 1);
        check(changed.outcome() == StepRunOutcome.ACTION_IN_PROGRESS,
                "Recovery build action did not pause in progress: " + changed.outcome());
        check(changed.actionInvocations() == 1,
                "Recovery build action invocation count changed");
        check(changed.session().completedStepIds().equals(List.of(PREPARE_STEP)),
                "Recovery completed-step prefix changed");
        check(changed.session().currentStep().orElseThrow().stepId().equals(RESUME_STEP),
                "Recovery current step changed");
        check(changed.session().worldChanges().equals(journal.get().references()),
                "Recovery session and journal references differ");

        RecoveryCheckpoint checkpoint = RecoveryCheckpoint.capture(
                changed.session(), journal.get(), startTick + 1);
        byte[] encoded = RecoveryCheckpointCodec.encode(checkpoint);
        savedData.checkpoint(encoded);
        logger.info(
                "RECOVERY_RELOAD_WRITE PASS session={} plan={} graph={} currentStep={} completedSteps={} modifiedPositions={} bytes={} block={}",
                checkpoint.session().sessionId(),
                checkpoint.plan().planId(),
                checkpoint.plan().graph().graphId(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().completedStepIds().size(),
                checkpoint.modifiedPositions().size(),
                encoded.length,
                scanOne(level, position).blockId());
        server.halt(false);
    }

    private static void read(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        RecoveryReloadAcceptanceSavedData savedData = savedData(level);
        check(savedData.hasCheckpoint(), "Read phase did not discover SavedData checkpoint");
        byte[] encoded = savedData.checkpoint();
        RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(encoded);
        GenericExecutionPlan trustedPlan = plan();
        SessionRecoveryReconciler.Discovery discovery =
                SessionRecoveryReconciler.discover(
                        checkpoint, Map.of(PLAN_ID, trustedPlan));
        check(discovery instanceof RescanRequired,
                "Reload discovery did not require rescan: " + discovery);
        RescanRequired candidate = (RescanRequired) discovery;
        check(candidate.requiredPositions().equals(checkpoint.modifiedPositions()),
                "Reload rescan positions changed");
        logger.info(
                "RECOVERY_RELOAD_DISCOVERED PASS session={} currentStep={} completedSteps={} modifiedPositions={} blindResume=false codecBytes={}",
                candidate.sessionId(),
                checkpoint.session().currentStepId().orElseThrow(),
                checkpoint.session().completedStepIds().size(),
                candidate.requiredPositions().size(),
                encoded.length);

        Map<BlockPos3i, WorldBlockSnapshot> scanned = scan(
                level, candidate.requiredPositions());
        SessionRecoveryReconciler.Reconciliation reconciliation =
                candidate.reconcile(scanned);
        check(reconciliation instanceof Resumable,
                "Exact reload rescan was not resumable: " + reconciliation);
        Resumable resumable = (Resumable) reconciliation;
        check(resumable.session().currentStep().orElseThrow().stepId().equals(RESUME_STEP),
                "Recovered current step changed");
        check(resumable.session().completedStepIds().equals(List.of(PREPARE_STEP)),
                "Recovered completed steps changed");

        BlockPos3i position = candidate.requiredPositions().get(0);
        WorldBlockSnapshot beforeResume = scanOne(level, position);
        BoundedStepRunner runner = runner((action, context) -> {
            if (!action.operationId().equals(RESUME_OPERATION)) {
                return ActionHandlerResult.failed(
                        id("steve_industrial:acceptance/recovery_resume_wrong_operation"),
                        "Reload resume reached an unexpected operation");
            }
            WorldBlockSnapshot observed = scanOne(level, position);
            if (!observed.equals(beforeResume)) {
                return ActionHandlerResult.failed(
                        id("steve_industrial:acceptance/recovery_resume_state_changed"),
                        "Reload resume state changed after reconciliation");
            }
            return ActionHandlerResult.succeeded(
                    List.of(evidence(context, RESUME_EVIDENCE, "resume")),
                    List.of());
        });
        long resumeTick = Math.max(level.getGameTime(), resumable.session().lastProgressTick());
        StepRunResult resumed = runner.tick(resumable.session(), resumeTick);
        check(resumed.outcome() == StepRunOutcome.SESSION_COMPLETED,
                "Reconciled session did not complete: " + resumed.outcome());
        check(resumed.actionInvocations() == 1,
                "Reconciled session action invocation count changed");
        check(resumed.session().status() == GenericExecutionSessionStatus.COMPLETED,
                "Reconciled session did not reach completed status");
        check(scanOne(level, position).equals(beforeResume),
                "Resume handler mutated the verified world state");
        logger.info(
                "RECOVERY_RELOAD_RESUME PASS session={} currentStep={} completedSteps={} verifiedPositions={} actionInvocations={} worldMutation=false",
                resumable.session().sessionId(),
                RESUME_STEP,
                resumable.session().completedStepIds().size(),
                resumable.verifiedPositions().size(),
                resumed.actionInvocations());

        check(level.setBlockAndUpdate(blockPos(position), Blocks.DIRT.defaultBlockState()),
                "World rejected stale-session fault injection");
        WorldBlockSnapshot injectedState = scanOne(level, position);
        SessionRecoveryReconciler.Reconciliation staleResult = candidate.reconcile(
                Map.of(position, injectedState));
        check(staleResult instanceof StaleSession,
                "Changed world state did not return stale session: " + staleResult);
        StaleSession stale = (StaleSession) staleResult;
        check(stale.failureCode().equals(SessionRecoveryReconciler.STALE_SESSION_FAILURE),
                "Stale-session failure code changed");
        check(stale.reason() == StaleSessionReason.WORLD_STATE_CHANGED,
                "Stale-session reason changed: " + stale.reason());
        check(scanOne(level, position).equals(injectedState),
                "Stale-session reconciliation mutated the fault-injected block");
        logger.info(
                "RECOVERY_RELOAD_STALE PASS code={} reason={} position={} noMutation=true",
                stale.failureCode(),
                stale.reason(),
                stale.position().orElseThrow());
        logger.info(
                "RECOVERY_RELOAD_ACCEPTANCE PASS session={} savedData=true worldReloaded=true rescanRequired=true consistentResume=true staleRejected=true noBlindResume=true",
                checkpoint.session().sessionId());
        server.halt(false);
    }

    private static RecoveryReloadAcceptanceSavedData savedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RecoveryReloadAcceptanceSavedData::load,
                RecoveryReloadAcceptanceSavedData::new,
                RecoveryReloadAcceptanceSavedData.DATA_NAME);
    }

    private static BoundedStepRunner runner(StepActionHandler handler) {
        return new BoundedStepRunner(
                Map.of(HANDLER_ID, handler),
                Map.of(EVALUATOR_ID, (condition, context) ->
                        condition.conditionType().equals(
                                id("steve_industrial:acceptance/condition_failure"))
                                ? ConditionEvaluation.unsatisfied()
                                : ConditionEvaluation.satisfied()));
    }

    private static GenericExecutionPlan plan() {
        MachineNode node = new MachineNode(
                id("steve_industrial:acceptance/recovery_node"),
                id("steve_industrial:acceptance/recovery_role"),
                id("minecraft:stone"),
                new BlockPos3i(0, 0, 0),
                MachineOrientation.NONE,
                Set.of(id("steve_industrial:acceptance/recovery_capability")),
                Map.of("fixture", "save_reload"));
        UnifiedMachineGraph graph = new UnifiedMachineGraph(
                GRAPH_ID, List.of(node), List.of(), List.of());
        GenericProcessSpec process = new GenericProcessSpec(
                id("steve_industrial:acceptance/recovery_recipe"),
                id("steve_industrial:acceptance/recovery_recipe_type"),
                List.of(resource("steve_industrial:acceptance/recovery_input")),
                List.of(resource("steve_industrial:acceptance/recovery_output")),
                List.of(),
                Set.of(id("steve_industrial:acceptance/recovery_capability")),
                Set.of(RESUME_EVIDENCE),
                200,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of(id("steve_industrial:acceptance/recovery_extension"), "v1"));
        return new GenericExecutionPlan(
                PLAN_ID,
                graph,
                process,
                List.of(
                        step(PREPARE_STEP, PREPARE_OPERATION, PREPARE_EVIDENCE,
                                GenericExecutionPhase.PREPARE, "prepare"),
                        step(RESUME_STEP, RESUME_OPERATION, RESUME_EVIDENCE,
                                GenericExecutionPhase.BUILD, "resume")));
    }

    private static BoundedExecutionStep step(
            ResourceId stepId,
            ResourceId operationId,
            ResourceId evidenceId,
            GenericExecutionPhase phase,
            String name) {
        return new BoundedExecutionStep(
                stepId,
                phase,
                List.of(),
                new StepActionDescriptor(HANDLER_ID, operationId, Map.of()),
                List.of(new StepCondition(
                        id("steve_industrial:acceptance/" + name + "_success"),
                        EVALUATOR_ID,
                        id("steve_industrial:acceptance/condition_success"),
                        Map.of())),
                List.of(new StepCondition(
                        id("steve_industrial:acceptance/" + name + "_failure"),
                        EVALUATOR_ID,
                        id("steve_industrial:acceptance/condition_failure"),
                        Map.of())),
                200,
                RetryPolicy.NO_RETRY,
                true,
                Optional.empty(),
                Set.of(evidenceId));
    }

    private static VerificationEvidence evidence(
            StepRunnerContext context,
            ResourceId requirement,
            String name) {
        EvidenceValue value = new EvidenceValue(
                id("steve_industrial:acceptance/recovery_boolean"), "true");
        return new VerificationEvidence(
                id("steve_industrial:acceptance/recovery_evidence/"
                        + name + "_" + context.gameTick()),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                requirement,
                context.stepId(),
                id("steve_industrial:acceptance/recovery_fixture"),
                id("steve_industrial:acceptance/recovery_node"),
                value,
                value,
                context.gameTick(),
                true,
                Optional.empty());
    }

    private static ProcessResource resource(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static BlockPos3i fixturePosition(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 2;
        int z = spawn.getZ() + 2;
        check(level.hasChunk(x >> 4, z >> 4),
                "Recovery fixture spawn chunk is not loaded");
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = Math.min(level.getMaxBuildHeight() - 2, surface + 2);
        return new BlockPos3i(x, y, z);
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> scan(
            ServerLevel level,
            List<BlockPos3i> positions) {
        AdapterResult<Map<BlockPos3i, WorldBlockSnapshot>> result =
                ForgeRecoveryWorldScanner.scan(level, positions);
        if (result instanceof AdapterResult.Success<Map<BlockPos3i, WorldBlockSnapshot>> success) {
            return success.value();
        }
        throw new IllegalStateException("Recovery world scan failed: " + result);
    }

    private static WorldBlockSnapshot scanOne(ServerLevel level, BlockPos3i position) {
        return scan(level, List.of(position)).get(position);
    }

    private static BlockPos blockPos(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
