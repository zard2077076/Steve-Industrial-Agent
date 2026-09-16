package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.deployment.BackupApprovalRequirement;
import dev.stevecreate.agent.core.deployment.BackupApprovalState;
import dev.stevecreate.agent.core.deployment.BackupAtomicityStrategy;
import dev.stevecreate.agent.core.deployment.BackupConsistencyStrategy;
import dev.stevecreate.agent.core.deployment.BackupFailureHandling;
import dev.stevecreate.agent.core.deployment.BackupFileEntry;
import dev.stevecreate.agent.core.deployment.BackupManifest;
import dev.stevecreate.agent.core.deployment.BackupManifestBuilder;
import dev.stevecreate.agent.core.deployment.BackupOperationStatus;
import dev.stevecreate.agent.core.deployment.BackupPathPolicy;
import dev.stevecreate.agent.core.deployment.BackupPlan;
import dev.stevecreate.agent.core.deployment.BackupRestoreDrillPlan;
import dev.stevecreate.agent.core.deployment.BackupRootRelationship;
import dev.stevecreate.agent.core.deployment.BackupRetentionPolicy;
import dev.stevecreate.agent.core.deployment.BackupTargetStrategy;
import dev.stevecreate.agent.core.deployment.BackupVerification;
import dev.stevecreate.agent.core.deployment.BackupVerifier;
import dev.stevecreate.agent.core.deployment.DeploymentBlockObservation;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.DeploymentBudget;
import dev.stevecreate.agent.core.deployment.DeploymentPolicy;
import dev.stevecreate.agent.core.deployment.DeploymentPreview;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewContext;
import dev.stevecreate.agent.core.deployment.DeploymentPreviewService;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessContext;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessRefusal;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessResult;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessSuccess;
import dev.stevecreate.agent.core.deployment.DeploymentReadinessVerifier;
import dev.stevecreate.agent.core.deployment.DeploymentReadyPlan;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessment;
import dev.stevecreate.agent.core.deployment.DryRunCompletion;
import dev.stevecreate.agent.core.deployment.HumanApprovalAuthorizerType;
import dev.stevecreate.agent.core.deployment.HumanApprovalDecision;
import dev.stevecreate.agent.core.deployment.HumanApprovalGate;
import dev.stevecreate.agent.core.deployment.HumanApprovalRequest;
import dev.stevecreate.agent.core.deployment.HumanApprovalToken;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.PermissionEvidence;
import dev.stevecreate.agent.core.deployment.PermissionEvidenceState;
import dev.stevecreate.agent.core.deployment.PermissionQuery;
import dev.stevecreate.agent.core.deployment.RegionApprovalState;
import dev.stevecreate.agent.core.deployment.RegionAuthorization;
import dev.stevecreate.agent.core.deployment.RegionAuthorizationRequest;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.RegionRevocationState;
import dev.stevecreate.agent.core.deployment.RegionUseState;
import dev.stevecreate.agent.core.deployment.ResourceSourcePolicy;
import dev.stevecreate.agent.core.deployment.RiskSeverity;
import dev.stevecreate.agent.core.deployment.RollbackClassification;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentDescriptor;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.deployment.WritableTestWorldFailureCode;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpoint;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionAuthorization;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionGate;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenExecution;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606PilotCleanupService;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606ThreeModeExecution;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** IWP-04 typed readiness and zero-mutation dry-run bound to a confirmed player region. */
public final class PilotDeploymentCommand {
    /**
     * Whether the single-machine order path would accept this goal as it stands.
     *
     * <p>Exposed so a survey can report reachability honestly. Classifying a goal as
     * single-machine says nothing about whether anything can execute it: the accepted
     * set is eleven hard-coded (target, quantity) pairs, not a general capability.</p>
     */
    public static boolean acceptsSingleMachineGoal(
            Level level, ResourceId target, long quantity) {
        return TargetSpec.accepts(level, target, quantity);
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BACKUP_EVIDENCE = "steve_industrial.iwp.backupEvidenceRoot";
    private static final String BACKUP_ROOT = "steve_industrial.iwp.backupRoot";
    private static final String BACKUP_TARGET = "steve_industrial.iwp.backupTarget";
    private static final String BACKUP_IDENTITY = "steve_industrial.iwp.backupIdentity";
    private static final String BACKUP_MANIFEST = "steve_industrial.iwp.backupManifest";
    private static final ResourceId TEST_PERMISSION_ADAPTER =
            ResourceId.parse("steve_industrial:iwp_test_only_permission");
    private static final ResourceId ROUTE_MATERIAL = ResourceId.parse("create:andesite_casing");
    private static final Map<UUID, PreparedPilot> PREPARED = new HashMap<>();
    private static final Map<UUID, HumanApprovalGate> APPROVAL_GATES = new HashMap<>();
    private static final Map<UUID, ActivePilot> ACTIVE = new HashMap<>();
    private static final Map<UUID, PilotHistory> HISTORY = new HashMap<>();
    private static final Map<UUID, CreateV606GoalDrivenExecution.Phase> HOLD_TARGETS = new HashMap<>();

    private PilotDeploymentCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(goal("readiness", false));
        root.then(goal("dry-run", true));
        root.then(siteAnchorGoal());
        root.then(startGoal());
        root.then(Commands.literal("status").executes(context -> status(context.getSource())));
        root.then(Commands.literal("cancel").executes(context -> cancel(context.getSource())));
        root.then(Commands.literal("cleanup")
                .then(Commands.literal("preview").executes(
                        context -> cleanup(context.getSource(), true)))
                .executes(context -> cleanup(context.getSource(), false)));
        root.then(Commands.literal("recovery-status").executes(
                context -> recoveryStatus(context.getSource())));
        root.then(Commands.literal("resume").executes(context -> resume(context.getSource())));
        root.then(Commands.literal("hold")
                .then(holdPhase("build", CreateV606GoalDrivenExecution.Phase.BUILD))
                .then(holdPhase("connect", CreateV606GoalDrivenExecution.Phase.CONNECT))
                .then(holdPhase("process", CreateV606GoalDrivenExecution.Phase.PROCESS))
                .then(holdPhase("verify", CreateV606GoalDrivenExecution.Phase.VERIFY)));
        root.then(Commands.literal("continue").executes(context -> continuePilot(context.getSource())));
    }

    static void clearPlayer(UUID player) {
        PREPARED.remove(player);
        APPROVAL_GATES.remove(player);
    }

    static boolean hasActiveSession(UUID player) {
        return ACTIVE.containsKey(player);
    }

    static boolean hasAnyActiveSession() {
        return !ACTIVE.isEmpty();
    }

    static void clearServerState() {
        PREPARED.clear();
        APPROVAL_GATES.clear();
        ACTIVE.clear();
        HISTORY.clear();
        HOLD_TARGETS.clear();
        BuildModeCommand.clearServerState();
        CreateV606GoalDrivenExecution.clearServerState();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> holdPhase(
            String name, CreateV606GoalDrivenExecution.Phase phase) {
        return Commands.literal(name).executes(context -> hold(context.getSource(), phase));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> goal(String name, boolean dryRun) {
        return Commands.literal(name).then(Commands.argument(
                        "target_resource", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .then(Commands.argument("quantity", LongArgumentType.longArg(1, 3))
                        .executes(context -> execute(context.getSource(),
                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                                        context, "target_resource"),
                                LongArgumentType.getLong(context, "quantity"),
                                QuarterTurn.ZERO, dryRun))
                        .then(orientation("zero", QuarterTurn.ZERO, dryRun))
                        .then(orientation("clockwise_90", QuarterTurn.CLOCKWISE_90, dryRun))
                        .then(orientation("clockwise_270", QuarterTurn.CLOCKWISE_270, dryRun))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> startGoal() {
        return Commands.literal("start").then(Commands.argument(
                        "target_resource", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .then(Commands.argument("quantity", LongArgumentType.longArg(1, 3))
                        .executes(context -> start(context.getSource(),
                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                                        context, "target_resource"),
                                LongArgumentType.getLong(context, "quantity"),
                                BuildModeCommand.modeFor(context.getSource())))
                        .then(Commands.literal("--mode")
                                .then(startMode("direct", ExecutionMode.DIRECT))
                                .then(startMode("bots", ExecutionMode.BOTS))
                                .then(startMode("hybrid", ExecutionMode.HYBRID)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> siteAnchorGoal() {
        return Commands.literal("site-anchor").then(Commands.argument(
                        "target_resource",
                        net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .then(Commands.argument("quantity", LongArgumentType.longArg(1, 3))
                        .executes(context -> siteAnchor(context.getSource(),
                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                                        context, "target_resource"),
                                LongArgumentType.getLong(context, "quantity"),
                                QuarterTurn.ZERO))
                        .then(siteAnchorOrientation("zero", QuarterTurn.ZERO))
                        .then(siteAnchorOrientation(
                                "clockwise_90", QuarterTurn.CLOCKWISE_90))
                        .then(siteAnchorOrientation(
                                "clockwise_270", QuarterTurn.CLOCKWISE_270))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> siteAnchorOrientation(
            String literal, QuarterTurn orientation) {
        return Commands.literal(literal).executes(context -> siteAnchor(
                context.getSource(),
                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                        context, "target_resource"),
                LongArgumentType.getLong(context, "quantity"), orientation));
    }

    private static int siteAnchor(
            CommandSourceStack source,
            ResourceLocation targetLocation,
            long quantity,
            QuarterTurn orientation) {
        PilotRegionCommand.ConfirmedPilotContext context =
                PilotRegionCommand.confirmedContext(source).orElse(null);
        if (context == null) return 0;
        ResourceId target = ResourceId.parse(targetLocation.toString());
        TargetSpec spec = TargetSpec.supported(source.getLevel(), target, quantity);
        if (spec == null || !spec.preparedSiteRequired) {
            return refuse(source, context,
                    WritableTestWorldFailureCode.PILOT_TARGET_UNSUPPORTED,
                    "Site anchor is available only for exact Phase IV targets");
        }
        BlockPos3i anchor = pilotAnchor(
                context.confirmation().bounds(), spec.physicalModuleCount, orientation);
        source.sendSuccess(() -> Component.literal(
                "Phase IV site anchor=" + anchor + " orientation=" + orientation
                        + " target=" + target + " quantity=" + quantity
                        + " worldMutation=false; stand on this block and run "
                        + "/industrialagent site anchor here before site region confirmation"),
                false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> startMode(
            String literal,
            ExecutionMode mode) {
        return Commands.literal(literal).executes(context -> start(
                context.getSource(),
                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                        context, "target_resource"),
                LongArgumentType.getLong(context, "quantity"), mode));
    }

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, ActivePilot> entry : List.copyOf(ACTIVE.entrySet())) {
            UUID playerId = entry.getKey();
            ActivePilot active = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (active.held) continue;
            if (player == null) {
                if (!active.started()) {
                    ACTIVE.remove(playerId);
                    HISTORY.put(playerId, PilotHistory.pendingCancelled(active.prepared));
                    LOGGER.info("IWP_EXECUTION_CANCELLED disconnected_during_countdown player={} session={}",
                            playerId, active.prepared.execution.executionReadyPlan().sessionId());
                    continue;
                }
            }
            if (!active.started()) {
                if (active.countdownTicks > 0) {
                    active.countdownTicks--;
                    if (active.countdownTicks == 40 || active.countdownTicks == 20
                            || active.countdownTicks == 1) {
                        int seconds = Math.max(1, (active.countdownTicks + 19) / 20);
                        if (player != null) {
                            player.displayClientMessage(Component.literal(
                                    "Pilot starts in " + seconds + "s — "
                                            + active.prepared.target + " x" + active.prepared.quantity), true);
                        }
                    }
                    continue;
                }
                try {
                    begin(player.serverLevel(), active);
                    if (active.mode == ExecutionMode.DIRECT) {
                        persistActive(player.serverLevel(), playerId, active,
                                CreateV606GoalDrivenExecution.Phase.BUILD, "execution started");
                    } else {
                        persistTerminal(player.serverLevel(), playerId, active.prepared,
                                PilotRecoverySavedData.Stage.FAILED,
                                active.threeModeSession.journalsSnapshot(),
                                "three-mode active; cleanup-only recovery until terminal evidence");
                    }
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                "Pilot execution STARTED session="
                                        + active.prepared.execution.executionReadyPlan().sessionId()
                                        + " mode=" + active.mode.serializedName()
                                        + " stage=BUILD buffer=" + active.prepared.resourceBuffer
                                        + " delivery=" + active.prepared.deliveryBuffer
                                        + " playerInventoryRead=false"));
                    }
                } catch (Exception failure) {
                    if (active.threeModeSession != null) {
                        try {
                            int discarded = active.threeModeSession.abortBeforeFirstTick();
                            LOGGER.info(
                                    "IWP_THREE_MODE_START_ABORT workersDiscarded={} player={} session={}",
                                    discarded, playerId,
                                    active.prepared.execution.executionReadyPlan().sessionId());
                        } catch (RuntimeException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                            LOGGER.error(
                                    "IWP_THREE_MODE_START_ABORT_FAILED player={} session={}",
                                    playerId,
                                    active.prepared.execution.executionReadyPlan().sessionId(),
                                    cleanupFailure);
                        }
                        active.threeModeSession = null;
                    }
                    cleanupBuffer(player.serverLevel(), active.prepared.resourceBuffer);
                    cleanupBuffer(player.serverLevel(), active.prepared.deliveryBuffer);
                    ACTIVE.remove(playerId);
                    HISTORY.put(playerId, PilotHistory.failed(
                            active.prepared, List.of(), "start failed: " + failure.getMessage()));
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(
                                "Pilot execution FAILED before session: " + failure.getMessage()));
                    }
                }
                continue;
            }
            if (active.threeModeSession != null) {
                tickThreeMode(playerId, active, player);
                continue;
            }
            CreateV606GoalDrivenExecution.TickResult result = active.session.tick();
            if (result instanceof CreateV606GoalDrivenExecution.Progress progress) {
                if (active.lastPhase != progress.phase()) {
                    active.lastPhase = progress.phase();
                    String line = "Pilot stage=" + progress.phase() + " node="
                            + (progress.processNodeIndex() + 1) + "/" + progress.processNodeCount()
                            + " session=" + progress.rootSessionId();
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(line));
                        player.displayClientMessage(Component.literal(line), true);
                    }
                    LOGGER.info("IWP_EXECUTION_PROGRESS {}", line);
                }
                if (player != null) {
                    persistActive(player.serverLevel(), playerId, active, progress.phase(),
                            "bounded progress tick=" + progress.gameTick());
                }
                CreateV606GoalDrivenExecution.Phase hold = HOLD_TARGETS.get(playerId);
                if (hold == progress.phase()) {
                    active.held = true;
                    HOLD_TARGETS.remove(playerId);
                    if (player != null) {
                        player.sendSystemMessage(Component.literal("Pilot HOLD reached phase="
                                + progress.phase() + " persisted=true; save and exit is now safe for the requested recovery test"));
                    }
                }
                continue;
            }
            ACTIVE.remove(playerId);
            if (result instanceof CreateV606GoalDrivenExecution.Completed completed) {
                PilotHistory history = PilotHistory.completed(active.prepared, completed);
                HISTORY.put(playerId, history);
                if (player != null) persistCompleted(player.serverLevel(), playerId,
                        active.prepared, completed, "normal completion");
                String line = "Pilot COMPLETE target=" + completed.target() + " required="
                        + completed.requiredQuantity() + " observed=" + completed.observedQuantity()
                        + " stages=BUILD,CONNECT,FEED,PROCESS,VERIFY journals="
                        + completed.journals().size() + " cleanupRequired=true";
                if (player != null) {
                    player.sendSystemMessage(Component.literal(line));
                    player.displayClientMessage(Component.literal("Pilot COMPLETE — inspect output, then cleanup"), true);
                }
                LOGGER.info("IWP_EXECUTION_COMPLETE {}", line);
            } else {
                CreateV606GoalDrivenExecution.Failed failed =
                        (CreateV606GoalDrivenExecution.Failed) result;
                List<WorldChangeJournal> failureJournals = active.session.journalsSnapshot();
                HISTORY.put(playerId, PilotHistory.failed(
                        active.prepared, failureJournals,
                        failed.code() + ": " + failed.detail()));
                if (player != null) persistTerminal(player.serverLevel(), playerId,
                        active.prepared, PilotRecoverySavedData.Stage.FAILED,
                        failureJournals, failed.code() + ": " + failed.detail());
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "Pilot FAILED code=" + failed.code() + " detail=" + failed.detail()));
                }
            }
        }
    }

    private static void tickThreeMode(
            UUID playerId,
            ActivePilot active,
            ServerPlayer player) {
        CreateV606ThreeModeExecution.TickResult result = active.threeModeSession.tick();
        if (result instanceof CreateV606ThreeModeExecution.Progress progress) {
            CreateV606GoalDrivenExecution.Phase phase = progress.createPhase()
                    .orElse(CreateV606GoalDrivenExecution.Phase.BUILD);
            if (active.lastPhase != phase) {
                active.lastPhase = phase;
                String line = "Pilot stage=" + phase + " task=" + progress.taskId()
                        + " mode=" + active.mode.serializedName()
                        + " elapsedTicks=" + progress.elapsedTicks();
                if (player != null) {
                    player.sendSystemMessage(Component.literal(line));
                    player.displayClientMessage(Component.literal(line), true);
                }
                LOGGER.info("IWP_EXECUTION_PROGRESS {}", line);
            }
            if (player != null) {
                persistTerminal(player.serverLevel(), playerId, active.prepared,
                        PilotRecoverySavedData.Stage.FAILED,
                        active.threeModeSession.journalsSnapshot(),
                        "three-mode bounded progress; cleanup-only recovery mode="
                                + active.mode.serializedName());
            }
            CreateV606GoalDrivenExecution.Phase hold = HOLD_TARGETS.get(playerId);
            if (hold == phase) {
                active.held = true;
                HOLD_TARGETS.remove(playerId);
            }
            return;
        }
        ACTIVE.remove(playerId);
        if (result instanceof CreateV606ThreeModeExecution.Completed completed) {
            PilotHistory history = PilotHistory.completed(active.prepared, completed.process());
            history.threeModeSession = active.threeModeSession;
            history.mode = active.mode;
            HISTORY.put(playerId, history);
            if (player != null) {
                persistCompleted(player.serverLevel(), playerId, active.prepared,
                        completed.process(), "three-mode completion mode="
                                + active.mode.serializedName());
            }
            String line = "Pilot COMPLETE target=" + completed.process().target()
                    + " mode=" + active.mode.serializedName()
                    + " required=" + completed.process().requiredQuantity()
                    + " observed=" + completed.process().observedQuantity()
                    + " tasks=" + completed.taskResults().size()
                    + " workers=" + completed.workerIds().size()
                    + " botRoles=" + (completed.workerActivities().isEmpty()
                    ? "none"
                    : completed.workerActivities().stream()
                            .map(value -> value.role() + ":assignments="
                                    + value.assignmentsStarted() + ":final="
                                    + value.finalPosition())
                            .collect(java.util.stream.Collectors.joining(",")))
                    + " botOverlap=" + (completed.workerActivities().stream()
                    .map(CreateV606ThreeModeExecution.WorkerActivity::finalPosition)
                    .distinct().count() != completed.workerActivities().size())
                    + " reload=" + completed.reloadReconciled()
                    + " cleanupRequired=true";
            if (player != null) {
                player.sendSystemMessage(Component.literal(line));
                player.displayClientMessage(Component.literal(
                        "Pilot COMPLETE — inspect output, then cleanup"), true);
            }
            LOGGER.info("IWP_EXECUTION_COMPLETE {}", line);
            return;
        }
        CreateV606ThreeModeExecution.Failed failed =
                (CreateV606ThreeModeExecution.Failed) result;
        List<WorldChangeJournal> journals = active.threeModeSession.journalsSnapshot();
        PilotHistory history = PilotHistory.failed(
                active.prepared, journals, failed.detail());
        history.mode = active.mode;
        HISTORY.put(playerId, history);
        if (player != null) {
            persistTerminal(player.serverLevel(), playerId, active.prepared,
                    PilotRecoverySavedData.Stage.FAILED, journals,
                    "three-mode failed mode=" + active.mode.serializedName()
                            + ": " + failed.detail());
            player.sendSystemMessage(Component.literal(
                    "Pilot FAILED mode=" + active.mode.serializedName()
                            + " detail=" + failed.detail()));
        }
    }

    private static ArgumentBuilder<CommandSourceStack, ?> orientation(
            String literal, QuarterTurn orientation, boolean dryRun) {
        return Commands.literal(literal).executes(context -> execute(context.getSource(),
                net.minecraft.commands.arguments.ResourceLocationArgument.getId(
                        context, "target_resource"),
                LongArgumentType.getLong(context, "quantity"), orientation, dryRun));
    }

    private static int start(
            CommandSourceStack source,
            ResourceLocation targetLocation,
            long quantity,
            ExecutionMode mode) {
        PilotRegionCommand.ConfirmedPilotContext context =
                PilotRegionCommand.confirmedContext(source).orElse(null);
        if (context == null) return 0;
        ResourceId target = ResourceId.parse(targetLocation.toString());
        TargetSpec targetSpec = TargetSpec.supported(source.getLevel(), target, quantity);
        if (targetSpec == null) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_TARGET_UNSUPPORTED,
                    "Target/quantity is outside the bounded pilot capability allowlist");
        }
        UUID player = context.player().getUUID();
        if (ACTIVE.containsKey(player)) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_DUPLICATE_SESSION,
                    "A pilot session or countdown is already active");
        }
        if (PilotRecoverySavedData.forLevel(source.getLevel()).entry(player).isPresent()) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_DUPLICATE_SESSION,
                    "Persisted pilot evidence must be resumed, inspected, and cleaned first");
        }
        PilotHistory previous = HISTORY.get(player);
        if (previous != null && !previous.cleaned) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_DUPLICATE_SESSION,
                    "Previous pilot evidence must be inspected and cleaned first");
        }
        PreparedPilot prepared = PREPARED.get(player);
        if (prepared == null || !prepared.target.equals(target) || prepared.quantity != quantity
                || !prepared.confirmationIdentity.equals(
                        context.confirmation().confirmationIdentity())) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                    "Run readiness and dry-run for this exact target after region confirmation");
        }
        if (!prepared.dryRunAcknowledged) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                    "The exact readiness preview has not been shown with pilot dry-run");
        }
        try {
            revalidateUnmodified(source.getLevel(), prepared);
        } catch (PilotRefusal refusal) {
            return refuse(source, context, refusal.code, refusal.getMessage());
        }
        PREPARED.remove(player);
        HISTORY.remove(player);
        ACTIVE.put(player, new ActivePilot(prepared, 60, mode));
        String line = "Pilot start accepted countdown=3s target=" + target + " quantity="
                + quantity + " mode=" + mode.serializedName()
                + " orientation=" + prepared.orientation + " previewHash="
                + prepared.previewHash + " mutationBudget=" + prepared.mutationBudget
                + " materials=" + prepared.targetSpec.inputs + " backup="
                + prepared.backupIdentity + " region="
                + format(prepared.affectedBounds)
                + " playerInventoryRead=false formalWorldExecutable=false";
        source.sendSuccess(() -> Component.literal(line), false);
        source.getPlayer().displayClientMessage(Component.literal(
                "Pilot starts in 3s — use /industrialagent pilot cancel to stop"), true);
        LOGGER.info("IWP_EXECUTION_COUNTDOWN {}", line);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        ActivePilot active = ACTIVE.get(player);
        if (active != null) {
            if (!active.prepared.matchesCurrent(context, source.getLevel())) {
                return refuseCurrent(source, context, active.prepared,
                        WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                        "Active pilot belongs to another world or dimension");
            }
            String phase = !active.started()
                    ? "COUNTDOWN" : String.valueOf(active.lastPhase == null
                            ? CreateV606GoalDrivenExecution.Phase.BUILD : active.lastPhase);
            source.sendSuccess(() -> Component.literal("Pilot status state=RUNNING phase=" + phase
                    + " countdownTicks=" + active.countdownTicks + " target="
                    + active.prepared.target + " quantity=" + active.prepared.quantity
                    + " mode=" + active.mode.serializedName()
                    + " previewHash=" + active.prepared.previewHash
                    + " backup=" + active.prepared.backupIdentity
                    + " buffer=" + active.prepared.resourceBuffer
                    + " mutationBudget=" + active.prepared.mutationBudget), false);
            return 1;
        }
        PilotHistory history = HISTORY.get(player);
        if (history != null) {
            if (!history.prepared.matchesCurrent(context, source.getLevel())) {
                return refuseCurrent(source, context, history.prepared,
                        WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                        "Pilot history belongs to another world or dimension");
            }
            source.sendSuccess(() -> Component.literal("Pilot status state=" + history.status
                    + " target=" + history.prepared.target + " quantity="
                    + history.prepared.quantity + " journals=" + history.journals.size()
                    + " cleanupPreview=" + history.cleanupPreview
                    + " cleaned=" + history.cleaned + " detail=" + history.detail), false);
            return 1;
        }
        PreparedPilot prepared = PREPARED.get(player);
        if (prepared != null && !prepared.matchesCurrent(context, source.getLevel())) {
            return refuseCurrent(source, context, prepared,
                    WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    "Prepared pilot belongs to another world or dimension");
        }
        PilotRecoverySavedData.RecoveryEntry persisted =
                PilotRecoverySavedData.forLevel(source.getLevel()).entry(player).orElse(null);
        if (prepared == null && persisted != null) {
            String mismatch = recoveryMismatch(context, source.getLevel(), persisted, false);
            if (mismatch != null) {
                return refuseCurrent(source, context, null,
                        WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH, mismatch);
            }
            source.sendSuccess(() -> Component.literal("Pilot status state=PERSISTED stage="
                    + persisted.stage() + " mode=" + persisted.mode() + " target="
                    + persisted.target() + " quantity=" + persisted.quantity()
                    + " cleanupAvailable=true detail=" + persisted.detail()), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(prepared == null
                ? "Pilot status state=IDLE confirmedRegion=true"
                : "Pilot status state=READY target=" + prepared.target + " quantity="
                        + prepared.quantity + " previewHash=" + prepared.previewHash
                        + " dryRunAcknowledged=" + prepared.dryRunAcknowledged), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        ActivePilot active = ACTIVE.remove(player);
        if (active == null) {
            return refuseCurrent(source, context, null, WritableTestWorldFailureCode.PILOT_CANCELLED,
                    "No active pilot session exists");
        }
        if (!active.prepared.matchesCurrent(context, source.getLevel())) {
            ACTIVE.put(player, active);
            return refuseCurrent(source, context, active.prepared,
                    WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    "Active pilot belongs to another world or dimension");
        }
        PilotHistory history;
        if (!active.started()) {
            history = PilotHistory.pendingCancelled(active.prepared);
        } else if (active.threeModeSession != null) {
            history = PilotHistory.cancelled(active.prepared,
                    active.threeModeSession.cancel(
                            ResourceId.parse("steve_industrial:pilot/user_cancelled")));
        } else {
            CreateV606GoalDrivenExecution.Cancellation cancellation = active.session.cancel(
                    ResourceId.parse("steve_industrial:pilot/user_cancelled"));
            history = PilotHistory.cancelled(active.prepared, cancellation.journals());
        }
        history.mode = active.mode;
        HISTORY.put(player, history);
        persistTerminal(source.getLevel(), player, active.prepared,
                PilotRecoverySavedData.Stage.CANCELLED, history.journals,
                active.mode == ExecutionMode.DIRECT ? "user cancellation"
                        : "user cancellation three-mode mode="
                                + active.mode.serializedName());
        String line = "Pilot CANCELLED session="
                + active.prepared.execution.executionReadyPlan().sessionId()
                + " journals=" + history.journals.size()
                + " cleanupRequired=" + !history.cleaned;
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info("IWP_EXECUTION_CANCELLED {}", line);
        return 1;
    }

    private static int cleanup(CommandSourceStack source, boolean previewOnly) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        if (ACTIVE.containsKey(player)) {
            return refuseCurrent(source, context, ACTIVE.get(player).prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    "Cancel or wait for the active session before cleanup");
        }
        PilotHistory history = HISTORY.get(player);
        if (history == null) {
            PilotRecoverySavedData.RecoveryEntry persisted =
                    PilotRecoverySavedData.forLevel(source.getLevel()).entry(player).orElse(null);
            if (persisted != null) {
                String mismatch = recoveryMismatch(context, source.getLevel(), persisted, false);
                if (mismatch != null) {
                    return refuseCurrent(source, context, null,
                            WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE, mismatch);
                }
                try {
                    PreparedPilot recovered = recoveryPrepared(source.getLevel(), persisted,
                            journalPositions(persisted.journals()));
                    history = new PilotHistory(recovered, persisted.journals(),
                            persisted.stage().name(), persisted.detail(), false);
                    history.mode = persistedMode(persisted.detail());
                    HISTORY.put(player, history);
                } catch (PilotRefusal refusal) {
                    return refuseCurrent(source, context, null,
                            WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                            "Persisted cleanup ownership could not be reconstructed: "
                                    + refusal.getMessage());
                }
            }
        }
        if (history == null || history.cleaned) {
            return refuseCurrent(source, context, history == null ? null : history.prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    "No uncleared session journal is available");
        }
        if (!history.prepared.matchesCurrent(context, source.getLevel())) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    "Cleanup history belongs to another world or dimension");
        }
        Set<BlockPos3i> owned = history.prepared.ownedPositions;
        BlockPos deliveryBlock = block(history.prepared.deliveryBuffer);
        if (history.mode != ExecutionMode.DIRECT
                && (!source.getLevel().hasChunkAt(deliveryBlock)
                || (!source.getLevel().getBlockState(deliveryBlock).isAir()
                && !source.getLevel().getBlockState(deliveryBlock).is(Blocks.CHEST)))) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    "Delivery buffer is no longer the session-owned chest or air");
        }
        Optional<String> unsafeDelivery = history.mode == ExecutionMode.DIRECT
                ? Optional.empty()
                : unsafeDeliveryContents(source.getLevel(), history.prepared);
        if (unsafeDelivery.isPresent()) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    unsafeDelivery.orElseThrow());
        }
        var plan = CreateV606PilotCleanupService.preview(
                source.getLevel(), history.journals, owned, history.prepared.resourceBuffer);
        if (!plan.safe()) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    plan.detail());
        }
        if (previewOnly) {
            history.cleanupPreview = true;
            int deliveryRemoval = history.mode != ExecutionMode.DIRECT
                    && source.getLevel().getBlockState(deliveryBlock).is(Blocks.CHEST)
                    ? 1 : 0;
            String line = "Pilot cleanup preview PASS session="
                    + history.prepared.execution.executionReadyPlan().sessionId()
                    + " remove=" + (plan.toRemove().size() + deliveryRemoval)
                    + " alreadyClean="
                    + plan.alreadyClean().size() + " journalOwnedOnly=true worldMutation=false";
            source.sendSuccess(() -> Component.literal(line), false);
            return 1;
        }
        if (!history.cleanupPreview) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                    "Run pilot cleanup preview immediately before cleanup");
        }
        if (history.threeModeSession != null) {
            CreateV606ThreeModeExecution.CleanupReport threeCleanup =
                    history.threeModeSession.cleanup();
            if (threeCleanup.remainingPositions() != 0) {
                return refuseCurrent(source, context, history.prepared,
                        WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                        "Three-mode final snapshot changed before cleanup: " + threeCleanup);
            }
        }
        var result = CreateV606PilotCleanupService.execute(
                source.getLevel(), history.journals, owned, history.prepared.resourceBuffer);
        if (!result.success()) {
            return refuseCurrent(source, context, history.prepared,
                    WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                    result.detail());
        }
        int deliveryRemoved = 0;
        if (history.mode != ExecutionMode.DIRECT
                && source.getLevel().getBlockState(deliveryBlock).is(Blocks.CHEST)) {
            source.getLevel().setBlockAndUpdate(deliveryBlock, Blocks.AIR.defaultBlockState());
            if (!source.getLevel().getBlockState(deliveryBlock).isAir()) {
                return refuseCurrent(source, context, history.prepared,
                        WritableTestWorldFailureCode.PILOT_CLEANUP_UNSAFE,
                        "Delivery buffer cleanup readback is not air");
            }
            deliveryRemoved = 1;
        }
        int workersRemoved = history.mode == ExecutionMode.DIRECT ? 0
                : CreateV606ThreeModeExecution.cleanupWorkers(
                        source.getLevel(), new CreateV606ThreeModeExecution.TestRegion(
                                history.prepared.authorizedRegion.minimum(),
                                history.prepared.authorizedRegion.maximum()));
        history.cleaned = true;
        history.status = "CLEANED";
        int totalRemoved = result.removed() + deliveryRemoved;
        history.detail = "removed=" + totalRemoved + " workers=" + workersRemoved;
        PilotRecoverySavedData.forLevel(source.getLevel()).remove(player);
        source.sendSuccess(() -> Component.literal("Pilot cleanup COMPLETE removed="
                + totalRemoved + " workers=" + workersRemoved
                + " journalOwnedOnly=true unknownBlocksRemoved=0"), false);
        LOGGER.info("IWP_CLEANUP_COMPLETE removed={} workers={} session={}", totalRemoved,
                workersRemoved,
                history.prepared.execution.executionReadyPlan().sessionId());
        return 1;
    }

    private static int hold(
            CommandSourceStack source,
            CreateV606GoalDrivenExecution.Phase phase) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        ActivePilot active = ACTIVE.get(player);
        if (active != null && !active.prepared.matchesCurrent(context, source.getLevel())) {
            return refuseCurrent(source, context, active.prepared,
                    WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    "Active pilot belongs to another world or dimension");
        }
        if (active != null && active.lastPhase == phase) active.held = true;
        else HOLD_TARGETS.put(player, phase);
        source.sendSuccess(() -> Component.literal("Pilot hold armed phase=" + phase
                + " reached=" + (active != null && active.held)
                + " boundedTickPause=true"), false);
        return 1;
    }

    private static int continuePilot(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        HOLD_TARGETS.remove(player);
        ActivePilot active = ACTIVE.get(player);
        if (active == null) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    "No active held pilot exists; use pilot resume for a persisted checkpoint");
        }
        active.held = false;
        source.sendSuccess(() -> Component.literal("Pilot CONTINUE phase=" + active.lastPhase
                + " persistedBoundaryRetained=true"), false);
        return 1;
    }

    private static void persistActive(
            ServerLevel level,
            UUID player,
            ActivePilot active,
            CreateV606GoalDrivenExecution.Phase phase,
            String detail) {
        PilotRecoverySavedData.Stage stage = stage(phase);
        byte[] checkpoint = new byte[0];
        PilotRecoverySavedData.RecoveryMode mode;
        List<WorldChangeJournal> journals = active.session.journalsSnapshot();
        if (phase == CreateV606GoalDrivenExecution.Phase.BUILD
                || phase == CreateV606GoalDrivenExecution.Phase.CONNECT) {
            CreateV606GoalDrivenExecution.ReloadResult reload =
                    active.session.captureReloadCheckpoint();
            if (reload instanceof CreateV606GoalDrivenExecution.ReloadReady ready) {
                checkpoint = RecoveryCheckpointCodec.encode(ready.checkpoint());
                mode = PilotRecoverySavedData.RecoveryMode.SAFE_CHECKPOINT;
            } else {
                mode = PilotRecoverySavedData.RecoveryMode.UNSAFE_RESOURCE_HISTORY;
                detail += "; checkpoint refused="
                        + ((CreateV606GoalDrivenExecution.ReloadRefused) reload).detail();
            }
        } else if (phase == CreateV606GoalDrivenExecution.Phase.VERIFY) {
            mode = PilotRecoverySavedData.RecoveryMode.VERIFY_PENDING;
        } else {
            mode = PilotRecoverySavedData.RecoveryMode.UNSAFE_RESOURCE_HISTORY;
        }
        PilotRecoverySavedData.RecoveryEntry current = recoveryEntry(level, player, active.prepared);
        PilotRecoverySavedData.forLevel(level).put(current.state(
                stage, mode, checkpoint, journals, 0, detail, level.getGameTime()));
    }

    private static void persistCompleted(
            ServerLevel level,
            UUID player,
            PreparedPilot prepared,
            CreateV606GoalDrivenExecution.Completed completed,
            String detail) {
        PilotRecoverySavedData.RecoveryEntry current = recoveryEntry(level, player, prepared);
        PilotRecoverySavedData.forLevel(level).put(current.state(
                PilotRecoverySavedData.Stage.COMPLETE,
                PilotRecoverySavedData.RecoveryMode.COMPLETE,
                new byte[0], completed.journals(), completed.observedQuantity(), detail,
                level.getGameTime()));
    }

    private static void persistTerminal(
            ServerLevel level,
            UUID player,
            PreparedPilot prepared,
            PilotRecoverySavedData.Stage stage,
            List<WorldChangeJournal> journals,
            String detail) {
        PilotRecoverySavedData.RecoveryEntry current = recoveryEntry(level, player, prepared);
        PilotRecoverySavedData.forLevel(level).put(current.state(
                stage, PilotRecoverySavedData.RecoveryMode.TERMINAL,
                new byte[0], journals, 0, detail, level.getGameTime()));
    }

    private static PilotRecoverySavedData.RecoveryEntry recoveryEntry(
            ServerLevel level, UUID player, PreparedPilot prepared) {
        return PilotRecoverySavedData.forLevel(level).entry(player).orElseGet(() ->
                new PilotRecoverySavedData.RecoveryEntry(
                        player, prepared.worldIdentity, prepared.worldFingerprint,
                        prepared.dimension, prepared.authorizedRegion, prepared.regionHash,
                        prepared.regionFingerprint, prepared.target, prepared.quantity,
                        prepared.orientation,
                        prepared.execution.executionReadyPlan().sessionId(),
                        prepared.resourceBuffer, prepared.previewHash,
                        prepared.backupIdentity, prepared.mutationBudget,
                        prepared.authorityExpiresAt, PilotRecoverySavedData.Stage.BUILD,
                        PilotRecoverySavedData.RecoveryMode.TERMINAL, new byte[0],
                        List.of(), 0, "initialized", level.getGameTime()));
    }

    private static PilotRecoverySavedData.Stage stage(
            CreateV606GoalDrivenExecution.Phase phase) {
        return switch (phase) {
            case BUILD -> PilotRecoverySavedData.Stage.BUILD;
            case CONNECT -> PilotRecoverySavedData.Stage.CONNECT;
            case FEED -> PilotRecoverySavedData.Stage.FEED;
            case PROCESS -> PilotRecoverySavedData.Stage.PROCESS;
            case VERIFY -> PilotRecoverySavedData.Stage.VERIFY;
            case COMPLETED -> PilotRecoverySavedData.Stage.COMPLETE;
        };
    }

    private static int recoveryStatus(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        ActivePilot active = ACTIVE.get(player);
        if (active != null && !active.prepared.matchesCurrent(context, source.getLevel())) {
            return refuseCurrent(source, context, active.prepared,
                    WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    "Recovery state belongs to another world or dimension");
        }
        PilotRecoverySavedData.RecoveryEntry persisted =
                PilotRecoverySavedData.forLevel(source.getLevel()).entry(player).orElse(null);
        if (persisted == null) {
            String state = active == null ? "NONE"
                    : active.session == null ? "COUNTDOWN_NO_CHECKPOINT" : "ACTIVE_NOT_YET_PERSISTED";
            source.sendSuccess(() -> Component.literal("Pilot recovery-status state=" + state
                    + " automaticResume=false processStageResume=false"), false);
            return 1;
        }
        String mismatch = recoveryMismatch(context, source.getLevel(), persisted, true);
        if (mismatch != null) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE, mismatch);
        }
        boolean automatic = persisted.mode() == PilotRecoverySavedData.RecoveryMode.SAFE_CHECKPOINT;
        boolean verify = persisted.mode() == PilotRecoverySavedData.RecoveryMode.VERIFY_PENDING;
        source.sendSuccess(() -> Component.literal("Pilot recovery-status state=PERSISTED stage="
                + persisted.stage() + " mode=" + persisted.mode() + " target="
                + persisted.target() + " quantity=" + persisted.quantity()
                + " rootSession=" + persisted.rootSessionId()
                + " automaticResume=" + automatic
                + " verifyFinalize=" + verify
                + " processStageResume=false duplicateConsumption=false duplicateOutput=false"
                + " detail=" + persisted.detail()), false);
        return 1;
    }

    private static int resume(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context =
                PilotRegionCommand.currentContext(source).orElse(null);
        if (context == null) return 0;
        UUID player = context.player().getUUID();
        if (ACTIVE.containsKey(player)) {
            return refuseCurrent(source, context, ACTIVE.get(player).prepared,
                    WritableTestWorldFailureCode.PILOT_DUPLICATE_SESSION,
                    "An in-memory pilot is already active");
        }
        PilotRecoverySavedData.RecoveryEntry persisted =
                PilotRecoverySavedData.forLevel(source.getLevel()).entry(player).orElse(null);
        if (persisted == null) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    "No persisted pilot recovery record exists");
        }
        String mismatch = recoveryMismatch(context, source.getLevel(), persisted, true);
        if (mismatch != null) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE, mismatch);
        }
        if (persisted.mode() == PilotRecoverySavedData.RecoveryMode.UNSAFE_RESOURCE_HISTORY) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    "Persisted " + persisted.stage()
                            + " has resource/processing history; conservative recovery refuses rerun. Use cleanup preview and cleanup");
        }
        if (persisted.mode() == PilotRecoverySavedData.RecoveryMode.TERMINAL
                || persisted.mode() == PilotRecoverySavedData.RecoveryMode.COMPLETE) {
            try {
                PreparedPilot recovered = recoveryPrepared(source.getLevel(), persisted,
                        journalPositions(persisted.journals()));
                HISTORY.put(player, new PilotHistory(recovered, persisted.journals(),
                        persisted.stage().name(), persisted.detail(), false));
            } catch (PilotRefusal refusal) {
                return refuseCurrent(source, context, null, refusal.code, refusal.getMessage());
            }
            source.sendSuccess(() -> Component.literal("Pilot resume state=" + persisted.stage()
                    + " executionRestarted=false cleanupAvailable=true"), false);
            return 1;
        }
        TargetSpec persistedTarget = TargetSpec.supported(
                source.getLevel(), persisted.target(), persisted.quantity());
        if (persistedTarget != null && persistedTarget.preparedSiteRequired) {
            return refuseCurrent(source, context, null,
                    WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    "Prepared-site production recovery requires a fresh exact site authorization; cleanup remains available");
        }
        try {
            if (persisted.mode() == PilotRecoverySavedData.RecoveryMode.SAFE_CHECKPOINT) {
                RecoveryCheckpoint checkpoint = RecoveryCheckpointCodec.decode(persisted.checkpoint());
                PreparedPilot recovered = recoveryPrepared(source.getLevel(), persisted,
                        Set.copyOf(checkpoint.journal().modifiedPositions()));
                CreateV606GoalDrivenExecution.ReconcileResult reconciled =
                        CreateV606GoalDrivenExecution.reconcileReload(source.getLevel(),
                                recovered.execution.executionReadyPlan(), checkpoint,
                                recovered.execution.executionMetadata());
                if (!(reconciled instanceof CreateV606GoalDrivenExecution.ReconcileReady ready)) {
                    var refused = (CreateV606GoalDrivenExecution.ReconcileRefused) reconciled;
                    return refuseCurrent(source, context, recovered,
                            WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                            "Exact checkpoint rescan refused: " + refused.code() + ": " + refused.detail());
                }
                var started = CreateV606GoalDrivenExecution.resume(
                        source.getLevel(), recovered.execution.executionReadyPlan(),
                        recovered.execution.runtime(), recovered.resourceBuffer,
                        ready.resumable(), recovered.execution.executionMetadata());
                if (!(started instanceof CreateV606GoalDrivenExecution.Started success)) {
                    var rejected = (CreateV606GoalDrivenExecution.Rejected) started;
                    return refuseCurrent(source, context, recovered,
                            WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                            rejected.code() + ": " + rejected.detail());
                }
                ActivePilot active = new ActivePilot(recovered, 0);
                active.session = success.session();
                active.lastPhase = persisted.stage() == PilotRecoverySavedData.Stage.CONNECT
                        ? CreateV606GoalDrivenExecution.Phase.CONNECT
                        : CreateV606GoalDrivenExecution.Phase.BUILD;
                ACTIVE.put(player, active);
                HISTORY.remove(player);
                PilotRecoverySavedData.forLevel(source.getLevel()).put(persisted.state(
                        persisted.stage(), persisted.mode(), persisted.checkpoint(),
                        persisted.journals(), persisted.outputObserved(),
                        "exact checkpoint reconciled and resumed", source.getLevel().getGameTime()));
                source.sendSuccess(() -> Component.literal("Pilot resume PASS stage="
                        + persisted.stage() + " exactRescan=true duplicatePlacement=false"
                        + " duplicateConsumption=false rootSession=" + persisted.rootSessionId()), false);
                return 1;
            }
            if (persisted.mode() == PilotRecoverySavedData.RecoveryMode.VERIFY_PENDING) {
                PreparedPilot recovered = recoveryPrepared(source.getLevel(), persisted,
                        journalPositions(persisted.journals()));
                var verified = CreateV606GoalDrivenExecution.recoverVerify(
                        source.getLevel(), recovered.execution.executionReadyPlan(),
                        recovered.execution.runtime(), recovered.resourceBuffer,
                        persisted.journals(), recovered.execution.executionMetadata());
                if (!(verified instanceof CreateV606GoalDrivenExecution.VerifyRecovered success)) {
                    var refused = (CreateV606GoalDrivenExecution.VerifyRecoveryRefused) verified;
                    return refuseCurrent(source, context, recovered,
                            WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                            refused.code() + ": " + refused.detail());
                }
                PilotHistory history = PilotHistory.completed(recovered, success.completed());
                HISTORY.put(player, history);
                persistCompleted(source.getLevel(), player, recovered, success.completed(),
                        "VERIFY recovery completed; alreadyCollected=" + success.alreadyCollected());
                source.sendSuccess(() -> Component.literal("Pilot resume PASS stage=VERIFY"
                        + " alreadyCollected=" + success.alreadyCollected()
                        + " duplicateOutput=false observed="
                        + success.completed().observedQuantity() + " cleanupAvailable=true"), false);
                return 1;
            }
        } catch (RuntimeException | PilotRefusal failure) {
            WritableTestWorldFailureCode code = failure instanceof PilotRefusal refusal
                    ? refusal.code : WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE;
            return refuseCurrent(source, context, null, code,
                    "Persisted recovery failed closed: " + failure.getMessage());
        }
        return refuseCurrent(source, context, null,
                WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                "Persisted recovery mode is unsupported: " + persisted.mode());
    }

    private static String recoveryMismatch(
            PilotRegionCommand.CurrentPilotContext context,
            ServerLevel level,
            PilotRecoverySavedData.RecoveryEntry persisted,
            boolean requireFreshAuthority) {
        if (!persisted.playerId().equals(context.player().getUUID())) return "Recovery owner mismatch";
        if (!persisted.worldIdentity().equals(context.identity().value())) return "Recovery world identity mismatch";
        if (!persisted.worldFingerprint().equals(context.identity().worldFingerprint())) return "Recovery world fingerprint mismatch";
        if (!persisted.dimension().equals(ResourceId.parse(level.dimension().location().toString()))) return "Recovery dimension mismatch";
        if (requireFreshAuthority && Instant.now().toEpochMilli() >= persisted.authorityExpiresAt()) return "Recovery region authority expired; cleanup remains available, but resume requires a fresh session after cleanup";
        return null;
    }

    private static PreparedPilot recoveryPrepared(
            ServerLevel level,
            PilotRecoverySavedData.RecoveryEntry persisted,
            Set<BlockPos3i> journalOwned) throws PilotRefusal {
        TargetSpec target = TargetSpec.supported(level, persisted.target(), persisted.quantity());
        if (target == null) {
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_TARGET_UNSUPPORTED,
                    "Persisted target is not enabled");
        }
        Map<ResourceId, Long> inputs = target.inputs;
        var planned = CreateV606GoalDrivenPlanner.planForRecovery(
                level, persisted.target(), persisted.quantity(), inputs,
                pilotAnchor(persisted.region(), target.physicalModuleCount,
                        persisted.orientation()),
                persisted.orientation(), persisted.rootSessionId(),
                inputs, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST, journalOwned,
                target.materialConstraints);
        if (!(planned instanceof CreateV606GoalDrivenPlanner.Ready ready)) {
            var failure = (CreateV606GoalDrivenPlanner.Failure) planned;
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    failure.code() + ": " + failure.detail());
        }
        PreparedPilot recovered = new PreparedPilot(persisted, target, ready);
        if (!contains(persisted.region(), recovered.affectedBounds)
                || !persisted.rootSessionId().equals(ready.executionReadyPlan().sessionId())) {
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_RECOVERY_UNSAFE,
                    "Recovered physical plan or root session escaped persisted authority");
        }
        return recovered;
    }

    private static Set<BlockPos3i> journalPositions(List<WorldChangeJournal> journals) {
        Set<BlockPos3i> result = new LinkedHashSet<>();
        journals.forEach(journal -> result.addAll(journal.modifiedPositions()));
        return Set.copyOf(result);
    }

    private static void begin(ServerLevel level, ActivePilot active) throws Exception {
        revalidateUnmodified(level, active.prepared);
        seedBuffer(level, active.prepared);
        if (active.mode != ExecutionMode.DIRECT) {
            seedEmptyBuffer(level, active.prepared.deliveryBuffer);
            Set<BlockPos3i> excluded = new LinkedHashSet<>(active.prepared.ownedPositions);
            excluded.add(active.prepared.resourceBuffer);
            excluded.add(active.prepared.deliveryBuffer);
            List<BlockPos3i> workerStarts = findWorkerStarts(
                    level, active.prepared.authorizedRegion, excluded);
            CreateV606ThreeModeExecution.TestRegion region =
                    new CreateV606ThreeModeExecution.TestRegion(
                            active.prepared.authorizedRegion.minimum(),
                            active.prepared.authorizedRegion.maximum());
            CreateV606ThreeModeExecution.StartResult started =
                    active.prepared.siteAuthorization == null
                            ? CreateV606ThreeModeExecution.start(
                                    level, active.prepared.execution.executionReadyPlan(),
                                    active.prepared.execution.runtime(),
                                    active.prepared.resourceBuffer,
                                    active.prepared.deliveryBuffer, active.mode, region,
                                    workerStarts,
                                    active.prepared.execution.executionMetadata())
                            : CreateV606ThreeModeExecution.start(
                                    level, active.prepared.siteAuthorization,
                                    active.prepared.worldIdentity,
                                    active.prepared.execution.runtime(),
                                    active.prepared.resourceBuffer,
                                    active.prepared.deliveryBuffer, active.mode, region,
                                    workerStarts,
                                    active.prepared.execution.executionMetadata());
            if (!(started instanceof CreateV606ThreeModeExecution.Started success)) {
                var rejected = (CreateV606ThreeModeExecution.Rejected) started;
                cleanupBuffer(level, active.prepared.deliveryBuffer);
                cleanupBuffer(level, active.prepared.resourceBuffer);
                throw new IllegalStateException(rejected.code() + ": " + rejected.detail());
            }
            active.threeModeSession = success.session();
            active.lastPhase = CreateV606GoalDrivenExecution.Phase.BUILD;
            return;
        }
        CreateV606GoalDrivenExecution.StartResult started =
                active.prepared.siteAuthorization == null
                        ? CreateV606GoalDrivenExecution.begin(
                                level, active.prepared.execution.executionReadyPlan(),
                                active.prepared.execution.runtime(),
                                active.prepared.resourceBuffer,
                                active.prepared.execution.executionMetadata())
                        : CreateV606GoalDrivenExecution.begin(
                                level, active.prepared.siteAuthorization,
                                active.prepared.worldIdentity,
                                active.prepared.execution.runtime(),
                                active.prepared.resourceBuffer,
                                active.prepared.execution.executionMetadata());
        if (!(started instanceof CreateV606GoalDrivenExecution.Started success)) {
            var rejected = (CreateV606GoalDrivenExecution.Rejected) started;
            throw new IllegalStateException(rejected.code() + ": " + rejected.detail());
        }
        active.session = success.session();
        active.lastPhase = CreateV606GoalDrivenExecution.Phase.BUILD;
    }

    private static void seedEmptyBuffer(ServerLevel level, BlockPos3i position) {
        BlockPos block = block(position);
        if (!level.setBlockAndUpdate(block, Blocks.CHEST.defaultBlockState())
                || !(level.getBlockEntity(block) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException(
                    "PILOT_MATERIAL_SOURCE_UNAVAILABLE: delivery chest creation failed");
        }
        chest.clearContent();
        chest.setChanged();
    }

    private static List<BlockPos3i> findWorkerStarts(
            ServerLevel level,
            DeploymentBoundingBox bounds,
            Set<BlockPos3i> excluded) {
        List<BlockPos3i> starts = new ArrayList<>();
        for (int y = bounds.minimum().y(); y <= bounds.maximum().y() && starts.size() < 2; y++) {
            for (int z = bounds.minimum().z(); z <= bounds.maximum().z() && starts.size() < 2; z++) {
                for (int x = bounds.minimum().x(); x <= bounds.maximum().x() && starts.size() < 2; x++) {
                    BlockPos3i candidate = new BlockPos3i(x, y, z);
                    if (excluded.contains(candidate)) continue;
                    BlockPos feet = block(candidate);
                    if (level.hasChunkAt(feet) && level.getBlockState(feet).isAir()
                            && level.getBlockState(feet.above()).isAir()
                            && !level.getBlockState(feet.below()).isAir()) {
                        starts.add(candidate);
                    }
                }
            }
        }
        if (starts.size() != 2) {
            throw new IllegalStateException(
                    "PILOT_BOT_START_UNAVAILABLE: two bounded walkable cells are required");
        }
        return List.copyOf(starts);
    }

    private static void seedBuffer(ServerLevel level, PreparedPilot prepared) {
        BlockPos position = block(prepared.resourceBuffer);
        if (!level.getBlockState(position).isAir() || level.getBlockEntity(position) != null) {
            throw new IllegalStateException("PILOT_MATERIAL_SOURCE_UNAVAILABLE: buffer position changed");
        }
        if (!level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
                || !(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("PILOT_MATERIAL_SOURCE_UNAVAILABLE: chest creation failed");
        }
        int slot = 0;
        for (Map.Entry<ResourceId, Long> input : prepared.targetSpec.inputs.entrySet().stream()
                .sorted(java.util.Comparator.comparing(value -> value.getKey().toString()))
                .toList()) {
            Item item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath(
                            input.getKey().namespace(), input.getKey().path()));
            if (item == null || input.getValue() > item.getDefaultInstance().getMaxStackSize()) {
                cleanupBuffer(level, prepared.resourceBuffer);
                throw new IllegalStateException(
                        "PILOT_MATERIAL_SOURCE_UNAVAILABLE: bounded input is unavailable "
                                + input.getKey());
            }
            chest.setItem(slot++, new ItemStack(item, Math.toIntExact(input.getValue())));
        }
        chest.setChanged();
    }

    private static void cleanupBuffer(ServerLevel level, BlockPos3i position) {
        BlockPos block = block(position);
        if (level.getBlockState(block).is(Blocks.CHEST)) {
            level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState());
        }
    }

    private static Optional<String> unsafeDeliveryContents(
            ServerLevel level,
            PreparedPilot prepared) {
        if (level.getBlockState(block(prepared.deliveryBuffer)).isAir()) {
            return Optional.empty();
        }
        if (!(level.getBlockEntity(block(prepared.deliveryBuffer))
                instanceof ChestBlockEntity chest)) {
            return Optional.of("Delivery buffer chest entity is unavailable");
        }
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation item = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (item == null) return Optional.of("Delivery buffer contains an unregistered item");
            ResourceId identity = ResourceId.parse(item.toString());
            if (!identity.equals(prepared.target)
                    && !prepared.targetSpec.inputs.containsKey(identity)) {
                return Optional.of("Delivery buffer contains non-session item " + identity
                        + "; remove it before cleanup");
            }
            total = Math.addExact(total, stack.getCount());
        }
        return total <= 64 ? Optional.empty()
                : Optional.of("Delivery buffer item count exceeds the bounded session stack");
    }

    private static void revalidateUnmodified(ServerLevel level, PreparedPilot prepared)
            throws PilotRefusal {
        for (BlockPos3i position : prepared.ownedPositions) {
            BlockPos block = block(position);
            if (!level.hasChunkAt(block) || !level.getBlockState(block).isAir()
                    || level.getBlockEntity(block) != null) {
                throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_STALE,
                        "Preview position changed before start at " + position);
            }
        }
        BlockPos buffer = block(prepared.resourceBuffer);
        if (!level.hasChunkAt(buffer) || !level.getBlockState(buffer).isAir()
                || level.getBlockEntity(buffer) != null) {
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_MATERIAL_SOURCE_UNAVAILABLE,
                    "TEST_ONLY resource buffer position changed");
        }
        BlockPos delivery = block(prepared.deliveryBuffer);
        if (!level.hasChunkAt(delivery) || !level.getBlockState(delivery).isAir()
                || level.getBlockEntity(delivery) != null) {
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_MATERIAL_SOURCE_UNAVAILABLE,
                    "TEST_ONLY delivery buffer position changed");
        }
    }

    private static Set<BlockPos3i> ownedPositions(DeploymentPreview preview) {
        Set<BlockPos3i> result = new LinkedHashSet<>();
        preview.plannedPlacements().forEach(value -> result.add(value.position()));
        preview.itemRoutes().forEach(value -> result.addAll(value.positions()));
        preview.rotationalPowerRoutes().forEach(value -> result.addAll(value.positions()));
        return Set.copyOf(result);
    }

    private static Set<BlockPos3i> ownedPositions(
            dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan physical) {
        Set<BlockPos3i> result = new LinkedHashSet<>();
        physical.placements().forEach(placement -> {
            placement.components().forEach(component -> result.add(component.position()));
            result.addAll(placement.rotationalPowerRoute());
        });
        physical.routes().forEach(route -> result.addAll(route.positions()));
        return Set.copyOf(result);
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static QuarterTurn quarterTurn(
            dev.stevecreate.agent.core.siteprep.SiteFacing facing) {
        return switch (facing) {
            case NORTH -> QuarterTurn.ZERO;
            case EAST -> QuarterTurn.CLOCKWISE_90;
            case SOUTH -> QuarterTurn.CLOCKWISE_180;
            case WEST -> QuarterTurn.CLOCKWISE_270;
        };
    }

    private static int execute(
            CommandSourceStack source,
            ResourceLocation targetLocation,
            long quantity,
            QuarterTurn orientation,
            boolean dryRun) {
        PilotRegionCommand.ConfirmedPilotContext context =
                PilotRegionCommand.confirmedContext(source).orElse(null);
        if (context == null) return 0;
        ResourceId target = ResourceId.parse(targetLocation.toString());
        TargetSpec targetSpec = TargetSpec.supported(source.getLevel(), target, quantity);
        if (targetSpec == null) {
            return refuse(source, context, WritableTestWorldFailureCode.PILOT_TARGET_UNSUPPORTED,
                    "Target/quantity is outside the bounded pilot capability allowlist");
        }
        UUID playerId = context.player().getUUID();
        PreparedPilot prepared = PREPARED.get(playerId);
        if (prepared == null || !prepared.matches(
                context.confirmation().confirmationIdentity(), target, quantity, orientation)) {
            try {
                prepared = prepare(source.getLevel(), context, targetSpec, orientation,
                        APPROVAL_GATES.computeIfAbsent(playerId, ignored -> new HumanApprovalGate()));
                PREPARED.put(playerId, prepared);
            } catch (PilotRefusal refusal) {
                PREPARED.remove(playerId);
                return refuse(source, context, refusal.code, refusal.getMessage());
            } catch (Exception failure) {
                PREPARED.remove(playerId);
                return refuse(source, context, WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                        "Readiness failed closed: " + failure.getMessage());
            }
        }
        if (dryRun) prepared.dryRunAcknowledged = true;
        String line = (dryRun ? "Pilot dry-run PASS" : "Pilot readiness PASS")
                + " target=" + target + " quantity=" + quantity
                + " orientation=" + orientation + " previewHash="
                + prepared.previewHash + " bounds=" + format(prepared.affectedBounds)
                + " placements=" + prepared.placementCount
                + " inputs=" + targetSpec.inputs
                + " stress=" + prepared.stressDemand
                + " mutationBudget=" + prepared.mutationBudget
                + " backup=" + prepared.backupIdentity
                + " readinessChecks=" + prepared.deploymentReady.evidence().size()
                + " executionChecks=" + prepared.execution.executionReadyPlan().evidence().size()
                + " dryRun=" + dryRun + " worldMutation=false resourceConsumed=false"
                + " sessionCreated=false formalWorldExecutable=false";
        source.sendSuccess(() -> Component.literal(line), false);
        LOGGER.info("IWP_DEPLOYMENT_READINESS {}", line);
        return 1;
    }

    private static PreparedPilot prepare(
            ServerLevel level,
            PilotRegionCommand.ConfirmedPilotContext context,
            TargetSpec target,
            QuarterTurn orientation,
            HumanApprovalGate approvalGate) throws Exception {
        Instant now = Instant.now();
        BlockPos3i anchor = pilotAnchor(
                context.confirmation().bounds(), target.physicalModuleCount, orientation);
        SitePreparationCommand.PreparedExecutionContext site = null;
        if (target.preparedSiteRequired) {
            site = SitePreparationCommand.preparedExecutionContext(context.player()).orElse(null);
            if (site == null) {
                throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                        "Phase IV production requires a fresh PreparedConstructionSite in this world");
            }
            if (!site.prepared().anchor().position().equals(anchor)
                    || quarterTurn(site.prepared().facing()) != orientation) {
                throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                        "Prepared site anchor/facing must match the deterministic pilot plan anchor "
                                + anchor + " orientation=" + orientation);
            }
        }
        ResourceId sessionId = ResourceId.parse("steve_industrial:pilot/"
                + context.player().getUUID().toString().replace("-", "") + "/"
                + UUID.randomUUID().toString().replace("-", ""));
        Map<ResourceId, Long> inputs = target.inputs;
        var planned = CreateV606GoalDrivenPlanner.plan(
                level, target.target, target.quantity, inputs, anchor, orientation,
                sessionId, inputs, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                target.materialConstraints);
        if (!(planned instanceof CreateV606GoalDrivenPlanner.Ready execution)) {
            var failure = (CreateV606GoalDrivenPlanner.Failure) planned;
            throw new PilotRefusal(WritableTestWorldFailureCode.REGION_INSUFFICIENT_SPACE,
                    failure.code() + ": " + failure.detail());
        }
        PreparedSiteExecutionAuthorization siteAuthorization = null;
        if (site != null) {
            PreparedSiteExecutionGate.PlanningEvidence planning =
                    new PreparedSiteExecutionGate.PlanningEvidence(
                            site.prepared().worldIdentity(), site.prepared().dimension(),
                            site.prepared().preparedSiteIdentity(),
                            site.prepared().cleanSiteSnapshotHash(),
                            execution.executionReadyPlan().physicalPlan().id(),
                            execution.executionReadyPlan().physicalPlan().candidate()
                                    .snapshotFingerprint(),
                            now, true,
                            "forge1201:player-visible-authoritative-post-clearance-planning-snapshot");
            var gated = new PreparedSiteExecutionGate().authorize(
                    site.prepared(), site.selection(), execution.executionReadyPlan(), planning,
                    now);
            if (!(gated instanceof PreparedSiteExecutionGate.Authorized authorized)) {
                var refused = (PreparedSiteExecutionGate.Refused) gated;
                throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                        "Prepared-site execution gate refused " + refused.failure() + ": "
                                + refused.detail());
            }
            siteAuthorization = authorized.authorization();
        }

        Path game = Path.of(System.getProperty("user.dir")).toRealPath();
        Path world = level.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
        String runtime = execution.runtimeRecipeFingerprint();
        boolean publicRuntime = PublicAlphaRuntime.resolve(level, true)
                instanceof PublicAlphaRuntime.Success;
        WorldEnvironmentDescriptor environment = new WorldEnvironmentDescriptor(
                "iwp:" + context.identity().value(), WorldEnvironmentType.ISOLATED_TEST_WORLD,
                context.identity().value(), normalized(world), normalized(game), "1.20.1",
                "forge-47.4.0/create-6.0.6", runtime,
                context.confirmation().worldFingerprint(), "integrated-server", true,
                true, true, true, true,
                publicRuntime ? "production config, world marker and player confirmation"
                        : "legacy IWP exact instance/world marker and player confirmation",
                List.of(publicRuntime ? "portable disposable test world"
                                : "repository-owned disposable test world",
                        "configured important roots explicitly forbidden"),
                List.of());
        String forbidden = configuredForbiddenRoot(level);
        DeploymentPolicy policy = DeploymentPolicy.isolatedTestDefault(normalized(game), forbidden);
        Map<BlockPos3i, DeploymentBlockObservation> observations = observations(
                level, execution.executionReadyPlan().physicalPlan());
        DeploymentPreview preview = new DeploymentPreviewService().preview(
                execution.executionReadyPlan().physicalPlan(), new DeploymentPreviewContext(
                        environment, policy, context.confirmation().worldFingerprint(),
                        observations, Map.of(ROUTE_MATERIAL, 1L),
                        RollbackClassification.FULLY_REVERSIBLE,
                        List.of("TEST_ONLY bounded Create v606 power source")));
        if (!contains(context.confirmation().bounds(), preview.affectedBounds())) {
            throw new PilotRefusal(WritableTestWorldFailureCode.REGION_INSUFFICIENT_SPACE,
                    "Verified physical plan extends outside the confirmed region");
        }
        if (!preview.blockEntitiesEncountered().isEmpty()) {
            throw new PilotRefusal(WritableTestWorldFailureCode.REGION_CONTAINS_IMPORTANT_BLOCK_ENTITY,
                    "Verified physical plan intersects a BlockEntity");
        }
        if (!preview.protectedBlocksEncountered().isEmpty()
                || !preview.plannedReplacements().isEmpty()) {
            throw new PilotRefusal(WritableTestWorldFailureCode.REGION_CONTAINS_PROTECTED_BLOCK,
                    "Verified physical plan intersects a non-air or protected block");
        }
        if (!preview.policyViolations().isEmpty()) {
            throw new PilotRefusal(WritableTestWorldFailureCode.REGION_INSUFFICIENT_SPACE,
                    "Preview policy violations: " + preview.policyViolations());
        }

        AcceptedBackup backup = loadBackup(context, environment);
        int mutationBudget = Math.toIntExact(Math.min(256, Math.max(1, preview.journalEstimate() + 1)));
        Set<RegionAuthorizedOperation> operations = Set.of(
                RegionAuthorizedOperation.PLACE_BLOCK,
                RegionAuthorizedOperation.ACCESS_CONTAINER,
                RegionAuthorizedOperation.INSERT_ITEM,
                RegionAuthorizedOperation.CONNECT_POWER,
                RegionAuthorizedOperation.CONNECT_LOGISTICS,
                RegionAuthorizedOperation.START_MACHINE,
                RegionAuthorizedOperation.CLEANUP,
                RegionAuthorizedOperation.ROLLBACK);
        ResourceId dimension = ResourceId.parse(level.dimension().location().toString());
        String player = "player:" + context.player().getUUID();
        RegionAuthorizationRequest regionRequest = new RegionAuthorizationRequest(
                environment.worldIdentity(), environment.environmentType(), dimension,
                preview.affectedBounds(), Optional.of(player), operations, mutationBudget,
                preview.previewHash(), preview.worldSnapshotFingerprint(), runtime);
        RegionAuthorization authorization = new RegionAuthorization(
                "authorization:" + context.confirmation().confirmationIdentity(),
                environment.worldIdentity(), environment.environmentType(), dimension,
                context.confirmation().bounds(), Optional.of(player), player, operations, 256,
                context.confirmation().expiresAt(), preview.previewHash(),
                preview.worldSnapshotFingerprint(), runtime, true, RegionApprovalState.APPROVED,
                RegionRevocationState.ACTIVE, RegionUseState.UNUSED,
                "player-confirmed IWP region; test-only isolated authority");
        Map<RegionAuthorizedOperation, PermissionEvidence> permissions = permissions(
                operations, player, preview.affectedBounds(), environment, dimension, now);
        DeploymentRiskAssessment risks = new DeploymentRiskAssessment(
                preview.previewHash(), List.of(), RiskSeverity.INFO, false);
        long powerMargin = 4_096 - preview.stressDemand();
        if (powerMargin < 0) {
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_POWER_SOURCE_UNAVAILABLE,
                    "Verified stress exceeds the TEST_ONLY power bound");
        }
        DeploymentBudget budget = new DeploymentBudget(
                preview.previewHash(), preview.requiredInputResources(), Map.of(),
                preview.machineConstructionMaterials(), Map.of(), Map.of(),
                preview.requiredInputResources(), preview.expectedOutput(), preview.stressDemand(),
                powerMargin, preview.estimatedTicks(), 4_096, mutationBudget,
                1_048_576, Math.max(4_096, mutationBudget * 4_096L),
                ResourceSourcePolicy.TEST_FIXTURE_PROVIDED, List.of(), true);
        HumanApprovalRequest approvalRequest = new HumanApprovalRequest(
                environment.environmentType(), preview.previewHash(), environment.worldIdentity(),
                preview.worldSnapshotFingerprint(), runtime, execution.reloadGeneration(),
                preview.affectedBounds(), target.target, target.quantity, mutationBudget, policy);
        String tokenHash = sha256(context.confirmation().confirmationIdentity() + "\n"
                + preview.previewHash() + "\n" + target.target + "\n" + target.quantity);
        HumanApprovalToken approval = new HumanApprovalToken(
                tokenHash, HumanApprovalDecision.APPROVED, HumanApprovalAuthorizerType.HUMAN,
                player, environment.environmentType(), preview.previewHash(),
                environment.worldIdentity(), preview.worldSnapshotFingerprint(), runtime,
                execution.reloadGeneration(), preview.affectedBounds(), target.target,
                target.quantity, mutationBudget, policy, context.confirmation().expiresAt(),
                "explicit in-game pilot region confirm by the same player");
        DeploymentReadinessContext readiness = new DeploymentReadinessContext(
                execution.executionReadyPlan(), execution.executionReadyPlan().physicalPlan().id(),
                execution.executionReadyPlan().physicalPlan().unifiedGraph().id(), environment,
                Optional.of(policy), preview, DryRunCompletion.COMPLETE, now,
                Duration.ofMinutes(1), preview.worldSnapshotFingerprint(), runtime,
                authorization, regionRequest, permissions, risks, Set.of(), budget,
                Optional.of(backup.plan), Optional.of(backup.verification), approval,
                approvalRequest, now);
        DeploymentReadinessResult result = new DeploymentReadinessVerifier(approvalGate).verify(readiness);
        if (!(result instanceof DeploymentReadinessSuccess success)) {
            DeploymentReadinessRefusal refusal = (DeploymentReadinessRefusal) result;
            throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_STALE,
                    "25-check readiness refused: " + refusal.failures());
        }
        BlockPos3i resourceBuffer = findResourceBuffer(
                level, context.confirmation().bounds(), ownedPositions(preview), anchor);
        BlockPos3i deliveryBuffer = findDeliveryBuffer(
                level, context.confirmation().bounds(), ownedPositions(preview), resourceBuffer);
        return new PreparedPilot(
                context.confirmation().confirmationIdentity(), target.target, target.quantity,
                orientation, target, execution, success.plan(), preview, backup.plan,
                mutationBudget, resourceBuffer, deliveryBuffer,
                context.identity().value(), dimension,
                context.confirmation().bounds(), context.confirmation().regionHash(),
                context.identity().worldFingerprint(), context.confirmation().worldFingerprint(),
                context.confirmation().expiresAt().toEpochMilli(), false,
                siteAuthorization);
    }

    private static Map<RegionAuthorizedOperation, PermissionEvidence> permissions(
            Set<RegionAuthorizedOperation> operations,
            String player,
            DeploymentBoundingBox bounds,
            WorldEnvironmentDescriptor environment,
            ResourceId dimension,
            Instant now) {
        EnumMap<RegionAuthorizedOperation, PermissionEvidence> result =
                new EnumMap<>(RegionAuthorizedOperation.class);
        for (RegionAuthorizedOperation operation : operations) {
            PermissionQuery query = new PermissionQuery(
                    "query:iwp:" + operation.name().toLowerCase(), operation, player, bounds,
                    environment.worldIdentity(), environment.environmentType(), dimension,
                    "IWP exact isolated marker", now.minusMillis(1), 0,
                    environment.runtimeFingerprint());
            result.put(operation, new PermissionEvidence(
                    query, PermissionDecision.ALLOWED, TEST_PERMISSION_ADAPTER,
                    PermissionEvidenceState.VERIFIED, "isolated-test-only", now, 0,
                    environment.runtimeFingerprint(),
                    "TEST_ONLY capability unavailable outside exact isolated world"));
        }
        return Map.copyOf(result);
    }

    private static AcceptedBackup loadBackup(
            PilotRegionCommand.ConfirmedPilotContext context,
            WorldEnvironmentDescriptor environment) throws Exception {
        PublicAlphaRuntime.Result publicResult = PublicAlphaRuntime.resolve(
                context.player().serverLevel(), true);
        if (publicResult instanceof PublicAlphaRuntime.Success success) {
            return loadPortableBackup(context.player().serverLevel(),
                    environment.worldIdentity(), success.authorization());
        }
        return loadLegacyBackup(context, environment);
    }

    private static AcceptedBackup loadPortableBackup(
            ServerLevel level,
            String environmentWorldIdentity,
            PublicAlphaRuntime.Authorization authorization) throws Exception {
        PortableBackupService.Verified portable = PortableBackupService.verify(level);
        List<BackupFileEntry> entries = portable.entries().stream()
                .map(entry -> new BackupFileEntry(
                        entry.relativePath(), entry.sizeBytes(), entry.sha256()))
                .sorted(java.util.Comparator.comparing(BackupFileEntry::relativePath))
                .toList();
        BackupManifest manifest = BackupManifest.complete(entries);
        BackupPathPolicy policy = new BackupPathPolicy(
                authorization.gameDir(), authorization.backupRoot(),
                Set.copyOf(authorization.importantRoots()),
                BackupRootRelationship.EXTERNAL_DISJOINT);
        long required = Math.addExact(manifest.totalBytes(), Math.max(4_096L, manifest.totalBytes() / 10));
        BackupPlan plan = new BackupPlan(
                portable.backupIdentity(), environmentWorldIdentity,
                WorldEnvironmentType.ISOLATED_TEST_WORLD, authorization.worldRoot(),
                portable.backupDirectory(), policy, manifest.totalBytes(), required,
                Files.getFileStore(authorization.backupRoot()).getUsableSpace(), manifest,
                BackupTargetStrategy.ISOLATED_BACKUP_ROOT,
                BackupAtomicityStrategy.STAGING_THEN_ATOMIC_RENAME,
                BackupConsistencyStrategy.QUIESCED_WORLD_SNAPSHOT,
                new BackupRestoreDrillPlan(true, true, true),
                new BackupRetentionPolicy(3, Duration.ofDays(7), true),
                BackupFailureHandling.ABORT_KEEP_SOURCE_DELETE_STAGING,
                BackupApprovalRequirement.EXPLICIT_TEST_ONLY,
                BackupApprovalState.APPROVED_TEST_ONLY,
                portable.backupIdentity());
        BackupVerification verification = new BackupVerifier().verify(plan);
        if (!verification.valid()) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Portable backup verifier refused: " + verification.failures());
        }
        return new AcceptedBackup(plan, verification);
    }

    static void verifyPublicBackupCoreForAcceptance(ServerLevel level) throws Exception {
        PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(level, true);
        if (!(result instanceof PublicAlphaRuntime.Success success)) {
            throw new IllegalStateException("Public runtime is unavailable for backup acceptance");
        }
        loadPortableBackup(level, success.authorization().worldIdentity(), success.authorization());
    }

    private static AcceptedBackup loadLegacyBackup(
            PilotRegionCommand.ConfirmedPilotContext context,
            WorldEnvironmentDescriptor environment) throws Exception {
        Path evidence = Path.of(requiredProperty(BACKUP_EVIDENCE)).toRealPath();
        Path backupRoot = Path.of(requiredProperty(BACKUP_ROOT)).toRealPath();
        Path target = Path.of(requiredProperty(BACKUP_TARGET)).toRealPath();
        Path formal = Path.of(requiredProperty(PilotRegionCommand.FORBIDDEN_ROOT)).toRealPath();
        Path work = backupRoot.getParent().toRealPath();
        Path world = context.identity().canonicalWorldPath().toRealPath();
        if (!evidence.startsWith(backupRoot) || !target.startsWith(backupRoot)
                || target.startsWith(formal) || world.startsWith(formal)) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Accepted backup paths are outside the repository policy");
        }
        String acceptance = Files.readString(evidence.resolve("acceptance.json"), StandardCharsets.UTF_8);
        if (!acceptance.contains("\"backupValid\": true")
                || !acceptance.contains("\"restoreDrillPass\": true")
                || !acceptance.contains("\"sourceUnchanged\": true")
                || !acceptance.contains("\"formalWorldTouched\": false")) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Accepted backup readback lacks required completion evidence");
        }
        List<String> lines = Files.readAllLines(evidence.resolve("manifest.tsv"), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !"relativePath\tsizeBytes\tsha256".equals(lines.get(0))) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Accepted backup manifest header is invalid");
        }
        List<BackupFileEntry> entries = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            if (fields.length != 3) throw new IllegalStateException("Malformed backup manifest row");
            entries.add(new BackupFileEntry(fields[0], Long.parseLong(fields[1]), fields[2]));
        }
        BackupManifest manifest = BackupManifest.complete(entries);
        if (!manifest.manifestHash().equals(requiredProperty(BACKUP_MANIFEST))) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Accepted backup manifest identity changed");
        }
        BackupPathPolicy policy = new BackupPathPolicy(
                work, backupRoot, Set.of(formal, work.resolve("formal-backups")));
        BackupManifest targetManifest = new BackupManifestBuilder().buildBackupTarget(target, policy);
        BackupManifest restoreManifest = new BackupManifestBuilder().buildBackupTarget(
                evidence.resolve("restore-drill"), policy);
        if (!manifest.equals(targetManifest) || !manifest.equals(restoreManifest)) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Backup or disposable restore manifest changed");
        }
        long required = Math.addExact(manifest.totalBytes(), Math.max(4_096L, manifest.totalBytes() / 10));
        BackupPlan plan = new BackupPlan(
                requiredProperty(BACKUP_IDENTITY), environment.worldIdentity(),
                WorldEnvironmentType.ISOLATED_TEST_WORLD, world, target, policy,
                manifest.totalBytes(), required, Files.getFileStore(backupRoot).getUsableSpace(),
                manifest, BackupTargetStrategy.ISOLATED_BACKUP_ROOT,
                BackupAtomicityStrategy.STAGING_THEN_ATOMIC_RENAME,
                BackupConsistencyStrategy.QUIESCED_WORLD_SNAPSHOT,
                new BackupRestoreDrillPlan(true, true, true),
                new BackupRetentionPolicy(3, Duration.ofDays(7), true),
                BackupFailureHandling.ABORT_KEEP_SOURCE_DELETE_STAGING,
                BackupApprovalRequirement.EXPLICIT_TEST_ONLY,
                BackupApprovalState.APPROVED_TEST_ONLY,
                requiredProperty(BACKUP_IDENTITY));
        BackupVerification verification = new BackupVerifier().verify(plan);
        if (!verification.valid()) {
            throw new PilotRefusal(WritableTestWorldFailureCode.TEST_WORLD_BACKUP_INVALID,
                    "Backup verifier refused: " + verification.failures());
        }
        return new AcceptedBackup(plan, verification);
    }

    private static String configuredForbiddenRoot(ServerLevel level) {
        PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(level, true);
        if (result instanceof PublicAlphaRuntime.Success success) {
            return normalized(success.authorization().importantRoots().get(0));
        }
        return requiredProperty(PilotRegionCommand.FORBIDDEN_ROOT);
    }

    private static Map<BlockPos3i, DeploymentBlockObservation> observations(
            ServerLevel level,
            dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan physical) throws PilotRefusal {
        Set<BlockPos3i> positions = new LinkedHashSet<>();
        physical.placements().forEach(placement -> {
            placement.components().forEach(component -> positions.add(component.position()));
            positions.addAll(placement.rotationalPowerRoute());
        });
        physical.routes().forEach(route -> positions.addAll(route.positions()));
        Map<BlockPos3i, DeploymentBlockObservation> result = new LinkedHashMap<>();
        for (BlockPos3i position : positions) {
            BlockPos block = new BlockPos(position.x(), position.y(), position.z());
            if (!level.hasChunkAt(block)) {
                throw new PilotRefusal(WritableTestWorldFailureCode.REGION_INSUFFICIENT_SPACE,
                        "Required position is not loaded: " + position);
            }
            var state = level.getBlockState(block);
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            boolean blockEntity = level.getBlockEntity(block) != null;
            result.put(position, new DeploymentBlockObservation(
                    position, ResourceId.parse(key == null ? "minecraft:air" : key.toString()),
                    !state.isAir(), blockEntity, false));
        }
        return Map.copyOf(result);
    }

    static BlockPos3i pilotAnchor(
            DeploymentBoundingBox bounds,
            int physicalModuleCount,
            QuarterTurn orientation) {
        if (physicalModuleCount < 1 || physicalModuleCount > 2) {
            throw new IllegalArgumentException("physicalModuleCount must be between 1 and 2");
        }
        BlockPos3i center = new BlockPos3i(
                (bounds.minimum().x() + bounds.maximum().x()) / 2,
                bounds.minimum().y() + 2,
                (bounds.minimum().z() + bounds.maximum().z()) / 2);
        int centeringOffset = -((physicalModuleCount - 1) * 16) / 2;
        BlockPos3i rotated = new BlockPos3i(centeringOffset, 0, 0).rotateY(orientation);
        return center.translate(rotated.x(), 0, rotated.z());
    }

    private static BlockPos3i findResourceBuffer(
            ServerLevel level,
            DeploymentBoundingBox bounds,
            Set<BlockPos3i> owned,
            BlockPos3i anchor) throws PilotRefusal {
        int y = anchor.y();
        for (int z = bounds.maximum().z() - 1; z >= bounds.minimum().z() + 1; z--) {
            for (int x = bounds.maximum().x() - 1; x >= bounds.minimum().x() + 1; x--) {
                BlockPos3i candidate = new BlockPos3i(x, y, z);
                if (owned.contains(candidate)) continue;
                BlockPos block = block(candidate);
                if (level.hasChunkAt(block) && level.getBlockState(block).isAir()
                        && level.getBlockEntity(block) == null) return candidate;
            }
        }
        throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_MATERIAL_SOURCE_UNAVAILABLE,
                "No isolated air cell is available for the bounded TEST_ONLY resource chest");
    }

    private static BlockPos3i findDeliveryBuffer(
            ServerLevel level,
            DeploymentBoundingBox bounds,
            Set<BlockPos3i> owned,
            BlockPos3i source) throws PilotRefusal {
        BlockPos3i candidate = recoveredDeliveryBuffer(bounds, owned, source);
        BlockPos block = block(candidate);
        if (level.hasChunkAt(block) && level.getBlockState(block).isAir()
                && level.getBlockEntity(block) == null) return candidate;
        throw new PilotRefusal(WritableTestWorldFailureCode.PILOT_MATERIAL_SOURCE_UNAVAILABLE,
                "The deterministic bounded delivery chest cell is unavailable");
    }

    private static BlockPos3i recoveredDeliveryBuffer(
            DeploymentBoundingBox bounds,
            Set<BlockPos3i> owned,
            BlockPos3i source) {
        List<BlockPos3i> candidates = List.of(
                source.translate(-1, 0, 0), source.translate(1, 0, 0),
                source.translate(0, 0, -1), source.translate(0, 0, 1));
        for (BlockPos3i candidate : candidates) {
            if (bounds.contains(candidate) && !owned.contains(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Persisted pilot has no deterministic in-region delivery chest cell");
    }

    private static boolean contains(DeploymentBoundingBox outer, DeploymentBoundingBox inner) {
        return outer.contains(inner.minimum()) && outer.contains(inner.maximum());
    }

    private static int refuse(
            CommandSourceStack source,
            PilotRegionCommand.ConfirmedPilotContext context,
            WritableTestWorldFailureCode code,
            String reason) {
        String line = "Pilot refused code=" + code + " stage=READINESS testInstance="
                + context.identity().testInstanceIdentity() + " world=" + context.identity().value()
                + " fingerprint=" + context.identity().worldFingerprint() + " region="
                + format(context.confirmation().bounds()) + " previewHash="
                + context.confirmation().worldFingerprint() + " backup="
                + System.getProperty(BACKUP_IDENTITY, "unavailable") + " session="
                + context.sessionIdentity() + " reason=" + reason
                + " safeNextStep=Refresh preview, confirmation, and backup evidence"
                + " formalWorldExecutable=false worldMutation=false";
        source.sendFailure(Component.literal(line));
        LOGGER.info("IWP_TYPED_REFUSAL {}", line);
        return 0;
    }

    private static int refuseCurrent(
            CommandSourceStack source,
            PilotRegionCommand.CurrentPilotContext context,
            PreparedPilot prepared,
            WritableTestWorldFailureCode code,
            String reason) {
        String region = prepared == null ? "unavailable" : format(prepared.authorizedRegion);
        String preview = prepared == null ? "unavailable" : prepared.previewHash;
        String backup = prepared == null
                ? System.getProperty(BACKUP_IDENTITY, "unavailable")
                : prepared.backupIdentity;
        String session = prepared == null
                ? "unavailable"
                : prepared.execution.executionReadyPlan().sessionId().toString();
        String line = "Pilot refused code=" + code + " stage=LIFECYCLE testInstance="
                + context.identity().testInstanceIdentity() + " world=" + context.identity().value()
                + " fingerprint=" + context.identity().worldFingerprint() + " region=" + region
                + " previewHash=" + preview + " backup=" + backup + " session=" + session
                + " reason=" + reason
                + " safeNextStep=Inspect status and session journal; do not alter unknown blocks"
                + " formalWorldExecutable=false worldMutation=false";
        source.sendFailure(Component.literal(line));
        LOGGER.info("IWP_TYPED_REFUSAL {}", line);
        return 0;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing property " + name);
        return value;
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String format(DeploymentBoundingBox bounds) {
        return bounds.minimum().x() + "," + bounds.minimum().y() + "," + bounds.minimum().z()
                + ".." + bounds.maximum().x() + "," + bounds.maximum().y() + ","
                + bounds.maximum().z();
    }

    private static ExecutionMode persistedMode(String detail) {
        if (detail != null && detail.contains("three-mode")) {
            if (detail.contains("mode=bots")) return ExecutionMode.BOTS;
            return ExecutionMode.HYBRID;
        }
        return ExecutionMode.DIRECT;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record AcceptedBackup(BackupPlan plan, BackupVerification verification) {}

    private static final class ActivePilot {
        private final PreparedPilot prepared;
        private final ExecutionMode mode;
        private int countdownTicks;
        private CreateV606GoalDrivenExecution.Session session;
        private CreateV606ThreeModeExecution.Session threeModeSession;
        private CreateV606GoalDrivenExecution.Phase lastPhase;
        private boolean held;

        private ActivePilot(
                PreparedPilot prepared,
                int countdownTicks,
                ExecutionMode mode) {
            this.prepared = prepared;
            this.countdownTicks = countdownTicks;
            this.mode = mode;
        }

        private ActivePilot(PreparedPilot prepared, int countdownTicks) {
            this(prepared, countdownTicks, ExecutionMode.DIRECT);
        }

        private boolean started() { return session != null || threeModeSession != null; }
    }

    private static final class PilotHistory {
        private final PreparedPilot prepared;
        private final List<WorldChangeJournal> journals;
        private String status;
        private String detail;
        private boolean cleanupPreview;
        private boolean cleaned;
        private CreateV606ThreeModeExecution.Session threeModeSession;
        private ExecutionMode mode = ExecutionMode.DIRECT;

        private PilotHistory(
                PreparedPilot prepared,
                List<WorldChangeJournal> journals,
                String status,
                String detail,
                boolean cleaned) {
            this.prepared = prepared;
            this.journals = List.copyOf(journals);
            this.status = status;
            this.detail = detail;
            this.cleaned = cleaned;
        }

        private static PilotHistory completed(
                PreparedPilot prepared,
                CreateV606GoalDrivenExecution.Completed completed) {
            return new PilotHistory(prepared, completed.journals(), "COMPLETE",
                    "observed=" + completed.observedQuantity(), false);
        }

        private static PilotHistory cancelled(
                PreparedPilot prepared,
                List<WorldChangeJournal> journals) {
            return new PilotHistory(prepared, journals, "CANCELLED",
                    "bounded cancellation complete", false);
        }

        private static PilotHistory pendingCancelled(PreparedPilot prepared) {
            return new PilotHistory(prepared, List.of(), "CANCELLED",
                    "cancelled before first mutation", true);
        }

        private static PilotHistory failed(
                PreparedPilot prepared,
                List<WorldChangeJournal> journals,
                String detail) {
            return new PilotHistory(prepared, journals, "FAILED", detail, false);
        }
    }

    private static final class PreparedPilot {
        private final String confirmationIdentity;
        private final ResourceId target;
        private final long quantity;
        private final QuarterTurn orientation;
        private final TargetSpec targetSpec;
        private final CreateV606GoalDrivenPlanner.Ready execution;
        private final DeploymentReadyPlan deploymentReady;
        private final DeploymentPreview preview;
        private final BackupPlan backup;
        private final String previewHash;
        private final String backupIdentity;
        private final DeploymentBoundingBox affectedBounds;
        private final Set<BlockPos3i> ownedPositions;
        private final int placementCount;
        private final long stressDemand;
        private final int mutationBudget;
        private final BlockPos3i resourceBuffer;
        private final BlockPos3i deliveryBuffer;
        private final String worldIdentity;
        private final String worldFingerprint;
        private final ResourceId dimension;
        private final DeploymentBoundingBox authorizedRegion;
        private final String regionHash;
        private final String regionFingerprint;
        private final long authorityExpiresAt;
        private final PreparedSiteExecutionAuthorization siteAuthorization;
        private boolean dryRunAcknowledged;

        private PreparedPilot(
                String confirmationIdentity,
                ResourceId target,
                long quantity,
                QuarterTurn orientation,
                TargetSpec targetSpec,
                CreateV606GoalDrivenPlanner.Ready execution,
                DeploymentReadyPlan deploymentReady,
                DeploymentPreview preview,
                BackupPlan backup,
                int mutationBudget,
                BlockPos3i resourceBuffer,
                BlockPos3i deliveryBuffer,
                String worldIdentity,
                ResourceId dimension,
                DeploymentBoundingBox authorizedRegion,
                String regionHash,
                String worldFingerprint,
                String regionFingerprint,
                long authorityExpiresAt,
                boolean dryRunAcknowledged,
                PreparedSiteExecutionAuthorization siteAuthorization) {
            this.confirmationIdentity = confirmationIdentity;
            this.target = target;
            this.quantity = quantity;
            this.orientation = orientation;
            this.targetSpec = targetSpec;
            this.execution = execution;
            this.deploymentReady = deploymentReady;
            this.preview = preview;
            this.backup = backup;
            this.previewHash = preview.previewHash();
            this.backupIdentity = backup.backupIdentity();
            this.affectedBounds = preview.affectedBounds();
            this.ownedPositions = ownedPositions(preview);
            this.placementCount = preview.plannedPlacements().size();
            this.stressDemand = preview.stressDemand();
            this.mutationBudget = mutationBudget;
            this.resourceBuffer = resourceBuffer;
            this.deliveryBuffer = deliveryBuffer;
            this.worldIdentity = worldIdentity;
            this.worldFingerprint = worldFingerprint;
            this.dimension = dimension;
            this.authorizedRegion = authorizedRegion;
            this.regionHash = regionHash;
            this.regionFingerprint = regionFingerprint;
            this.authorityExpiresAt = authorityExpiresAt;
            this.dryRunAcknowledged = dryRunAcknowledged;
            this.siteAuthorization = siteAuthorization;
        }

        private PreparedPilot(
                PilotRecoverySavedData.RecoveryEntry persisted,
                TargetSpec targetSpec,
                CreateV606GoalDrivenPlanner.Ready execution) {
            this.confirmationIdentity = "recovered:" + persisted.rootSessionId();
            this.target = persisted.target();
            this.quantity = persisted.quantity();
            this.orientation = persisted.orientation();
            this.targetSpec = targetSpec;
            this.execution = execution;
            this.deploymentReady = null;
            this.preview = null;
            this.backup = null;
            this.previewHash = persisted.previewHash();
            this.backupIdentity = persisted.backupIdentity();
            this.ownedPositions = ownedPositions(execution.executionReadyPlan().physicalPlan());
            this.affectedBounds = DeploymentBoundingBox.enclosing(this.ownedPositions);
            this.placementCount = this.ownedPositions.size();
            this.stressDemand = 0;
            this.mutationBudget = persisted.mutationBudget();
            this.resourceBuffer = persisted.resourceBuffer();
            this.deliveryBuffer = recoveredDeliveryBuffer(
                    persisted.region(), this.ownedPositions, this.resourceBuffer);
            this.worldIdentity = persisted.worldIdentity();
            this.worldFingerprint = persisted.worldFingerprint();
            this.dimension = persisted.dimension();
            this.authorizedRegion = persisted.region();
            this.regionHash = persisted.regionHash();
            this.regionFingerprint = persisted.regionFingerprint();
            this.authorityExpiresAt = persisted.authorityExpiresAt();
            this.dryRunAcknowledged = true;
            this.siteAuthorization = null;
        }

        private boolean matchesCurrent(
                PilotRegionCommand.CurrentPilotContext context,
                ServerLevel level) {
            return worldIdentity.equals(context.identity().value())
                    && dimension.equals(ResourceId.parse(level.dimension().location().toString()));
        }

        private boolean matches(
                String confirmation, ResourceId requestedTarget,
                long requestedQuantity, QuarterTurn requestedOrientation) {
            return confirmationIdentity.equals(confirmation) && target.equals(requestedTarget)
                    && quantity == requestedQuantity && orientation == requestedOrientation;
        }
    }

    record TargetSpec(
            ResourceId target,
            long quantity,
            Map<ResourceId, Long> inputs,
            int physicalModuleCount,
            boolean preparedSiteRequired,
            MaterialConstraints materialConstraints) {
        TargetSpec {
            inputs = Map.copyOf(inputs);
            java.util.Objects.requireNonNull(materialConstraints, "materialConstraints");
            if (inputs.isEmpty() || inputs.size() > 9
                    || inputs.values().stream().anyMatch(value -> value == null
                            || value < 1 || value > 64)) {
                throw new IllegalArgumentException("target inputs exceed the bounded chest contract");
            }
        }

        /**
         * Whether the single-machine path would accept this goal as it stands today.
         *
         * <p>Exposed so a survey can report reachability honestly: classifying a goal as
         * single-machine says nothing about whether anything can run it, and the list
         * below is eleven hard-coded pairs rather than a general capability.</p>
         */
        static boolean accepts(Level level, ResourceId target, long quantity) {
            return supported(level, target, quantity) != null;
        }

        /**
         * The spec for a target, reviewed or derived from the live registry.
         *
         * <p>{@code level} may be null where only the reviewed eleven make sense — a
         * recovery path replaying an order that was placed against them. Everywhere a
         * player names a target it must be present, or a derivable goal is reported
         * unsupported for no reason the player can see.</p>
         */
        static TargetSpec supported(Level level, ResourceId target, long quantity) {
            GoalCatalogEntry entry = SingleMachineGoalResolver.resolve(level, target).orElse(null);
            if (entry == null || quantity < 1 || quantity > maximumQuantity(entry)) {
                return null;
            }
            if (entry.outputPerBatch() < 1 || quantity % entry.outputPerBatch() != 0) {
                return null;
            }
            long batches = quantity / entry.outputPerBatch();
            Map<ResourceId, Long> inputs = new java.util.LinkedHashMap<>();
            entry.inputsPerBatch().forEach((resource, perBatch) ->
                    inputs.put(resource, Math.multiplyExact(perBatch, batches)));
            for (Map.Entry<ResourceId, Long> fluid : entry.fluidInputsPerBatch().entrySet()) {
                long millibuckets = Math.multiplyExact(fluid.getValue(), batches);
                PlacementItemBinding.Bucket bucket = PlacementItemBinding
                        .bucketsFor(fluid.getKey(), millibuckets)
                        .orElse(null);
                if (bucket == null) return null;
                inputs.merge(bucket.item(), bucket.count(), Math::addExact);
            }
            return new TargetSpec(target, quantity, inputs, entry.physicalModuleCount(),
                    entry.preparedSiteRequired(), constraintsFor(target));
        }

        /**
         * Constraints the goal catalog cannot express.
         *
         * <p>{@link GoalCatalogEntry} carries no material constraints, so deriving a
         * target spec purely from it would silently drop this one — the sand goal
         * excludes sandstone, and losing that would let a crushing order consume the
         * wrong rock while every other field still looked right.</p>
         */
        /**
         * The largest order this target can be asked for.
         *
         * <p>What bounds an order is not which quantity somebody happened to verify but
         * how long the recipe takes: the executor gives one step {@code MAX_WAIT_TICKS}
         * to move all of the material, no matter how much there is.
         *
         * <p>The factor of two is measured, not chosen for comfort. Milling runs 200
         * ticks a batch; twelve batches came to exactly the 2400-tick ceiling and timed
         * out, six passed, and 2400 / (200 * 2) is six.
         *
         * <p>Reviewed entries are hand-written and state no duration, so they fall back
         * on 200 — the same default the runtime census uses. That is the slowest recipe
         * the live registry actually contains bar one, so six batches stays inside the
         * ceiling for every one of them. It does raise gravel's limit from the three
         * that was verified to the six that has now been verified, which is the point
         * of the measurement.</p>
         */
        private static final long UNKNOWN_TICKS = 200L;
        private static final long TIMEOUT_SAFETY_FACTOR = 2L;

        private static long maximumQuantity(GoalCatalogEntry entry) {
            // The current Basin/Mixer physical contract proves one exact pour and one
            // batch. Do not let the general duration formula expose an unverified bulk
            // fluid cycle merely because the recipe itself is fast.
            if (!entry.fluidInputsPerBatch().isEmpty()) {
                return entry.outputPerBatch();
            }
            long perBatch = Math.max(1, entry.processingTicks().orElse(UNKNOWN_TICKS));
            long batches = GenericProcessSpec.MAX_WAIT_TICKS / (perBatch * TIMEOUT_SAFETY_FACTOR);
            long quantity = Math.multiplyExact(Math.max(1, batches), entry.outputPerBatch());
            return Math.max(1, Math.min(quantity, GoalCatalogEntry.MAX_STACK_BUDGET));
        }

        private static MaterialConstraints constraintsFor(ResourceId target) {
            if (target.equals(ResourceId.parse("minecraft:sand"))) {
                return new MaterialConstraints(
                        Set.of(ResourceId.parse("minecraft:sandstone")), Map.of());
            }
            return MaterialConstraints.none();
        }

        private static TargetSpec phaseIv(
                ResourceId target, Map<ResourceId, Long> inputs) {
            return new TargetSpec(
                    target, 1, inputs, 1, true, MaterialConstraints.none());
        }
    }

    private static final class PilotRefusal extends Exception {
        private final WritableTestWorldFailureCode code;

        private PilotRefusal(WritableTestWorldFailureCode code, String detail) {
            super(detail);
            this.code = code;
        }
    }
}
