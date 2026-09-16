package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.construction.AssignmentPolicy;
import dev.stevecreate.agent.core.execution.construction.BotInventory;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.BotWorkerStatus;
import dev.stevecreate.agent.core.execution.construction.RetryBudget;
import dev.stevecreate.agent.core.execution.construction.WorkerHealthPolicy;
import dev.stevecreate.agent.core.execution.fleet.FleetWorker;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Assignment;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.ExecutionContext;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Outcome;
import dev.stevecreate.agent.core.execution.fleet.GraphNeutralFleetCoordinator.Update;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.AnchorSource;
import dev.stevecreate.agent.core.siteprep.ApprovedObstacle;
import dev.stevecreate.agent.core.siteprep.BotClearingExecutor;
import dev.stevecreate.agent.core.siteprep.BotClearingState;
import dev.stevecreate.agent.core.siteprep.BotClearingUpdate;
import dev.stevecreate.agent.core.siteprep.ConfirmedSiteSelection;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalRequest;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalContext;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalService;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalState;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalToken;
import dev.stevecreate.agent.core.siteprep.DemolitionPreview;
import dev.stevecreate.agent.core.siteprep.GroundLevelingPolicy;
import dev.stevecreate.agent.core.siteprep.ForcedTerrainRemoval;
import dev.stevecreate.agent.core.siteprep.ForcedTerrainRemovalAuthorization;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import dev.stevecreate.agent.core.siteprep.ObstacleClassifier;
import dev.stevecreate.agent.core.siteprep.ObstacleFinding;
import dev.stevecreate.agent.core.siteprep.ObstacleObservation;
import dev.stevecreate.agent.core.siteprep.PlacementAnchor;
import dev.stevecreate.agent.core.siteprep.PostClearanceRescan;
import dev.stevecreate.agent.core.siteprep.PreparedConstructionSite;
import dev.stevecreate.agent.core.siteprep.RegionCornerSelection;
import dev.stevecreate.agent.core.siteprep.SalvageEntry;
import dev.stevecreate.agent.core.siteprep.SalvageLedger;
import dev.stevecreate.agent.core.siteprep.SiteFacing;
import dev.stevecreate.agent.core.siteprep.SiteSelectionService;
import dev.stevecreate.agent.core.siteprep.SiteSurvey;
import dev.stevecreate.agent.core.siteprep.SiteSurveySnapshot;
import dev.stevecreate.agent.core.siteprep.TerrainMutationEvidence;
import dev.stevecreate.agent.core.siteprep.TerrainGradingSpecification;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationFleetDispatcher;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationFleetTaskAdapter;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationPlan;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationTask;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationTaskGraph;
import dev.stevecreate.agent.core.siteprep.TerrainPreparationTaskKind;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.forge1201.player.PlayerSalvageSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerSitePreparationSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.joml.Vector3f;
import org.slf4j.Logger;

/** Isolated-world-only SP-01..SP-08 command and bounded physical clearing runtime. */
public final class SitePreparationCommand {
    static final int CELLS_PER_TICK = 1_024;
    static final int MAX_VISIBLE_FINDINGS = 32;
    static final int GRADING_CLEARANCE_HEIGHT = 8;
    static final int GRADING_MAXIMUM_FILL_DEPTH = 16;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SiteSelectionService SELECTIONS = new SiteSelectionService();
    private static final DemolitionApprovalService APPROVALS = new DemolitionApprovalService();
    private static final Map<UUID, PlayerSiteState> STATES = new HashMap<>();
    private static final Map<UUID, PendingSurvey> SURVEYS = new HashMap<>();
    private static final Map<UUID, ClearingSession> CLEARING = new HashMap<>();

    private SitePreparationCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        LiteralArgumentBuilder<CommandSourceStack> site = Commands.literal("site");
        site.then(Commands.literal("anchor")
                        .then(Commands.literal("here").executes(context ->
                                anchor(context.getSource(), AnchorSource.PLAYER_FEET)))
                        .then(Commands.literal("look").executes(context ->
                                anchor(context.getSource(), AnchorSource.PLAYER_LOOK)))
                        .then(Commands.literal("status").executes(context ->
                                anchorStatus(context.getSource())))
                        .then(Commands.literal("clear").executes(context ->
                                clearAnchor(context.getSource()))))
                .then(Commands.literal("pos1").executes(context ->
                        corner(context.getSource(), true)))
                .then(Commands.literal("pos2").executes(context ->
                        corner(context.getSource(), false)))
                .then(Commands.literal("region")
                        .then(Commands.literal("preview").executes(context ->
                                regionPreview(context.getSource())))
                        .then(Commands.literal("confirm").executes(context ->
                                regionConfirm(context.getSource())))
                        .then(Commands.literal("clear").executes(context ->
                                clearRegion(context.getSource()))))
                .then(Commands.literal("facing")
                        .then(Commands.literal("north").executes(context ->
                                facing(context.getSource(), SiteFacing.NORTH)))
                        .then(Commands.literal("south").executes(context ->
                                facing(context.getSource(), SiteFacing.SOUTH)))
                        .then(Commands.literal("east").executes(context ->
                                facing(context.getSource(), SiteFacing.EAST)))
                        .then(Commands.literal("west").executes(context ->
                                facing(context.getSource(), SiteFacing.WEST)))
                        .then(Commands.literal("rotate-clockwise").executes(context ->
                                rotateFacing(context.getSource())))
                        .then(Commands.literal("status").executes(context ->
                                facingStatus(context.getSource()))))
                .then(Commands.literal("survey").executes(context ->
                        survey(context.getSource(), false)))
                .then(Commands.literal("obstacles").executes(context ->
                        obstacles(context.getSource())))
                .then(Commands.literal("demolition")
                        .then(Commands.literal("preview").executes(context ->
                                demolitionPreview(context.getSource())))
                        .then(Commands.literal("approve-safe").executes(context ->
                                approveSafe(context.getSource())))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("obstacle_id", StringArgumentType.word())
                                        .executes(context -> approveOne(context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "obstacle_id")))))
                        .then(Commands.literal("revoke").executes(context ->
                                revoke(context.getSource())))
                        .then(Commands.literal("status").executes(context ->
                                approvalStatus(context.getSource()))))
                .then(Commands.literal("salvage")
                        .then(Commands.literal("set").executes(context ->
                                salvageSet(context.getSource())))
                        .then(Commands.literal("status").executes(context ->
                                salvageStatus(context.getSource()))))
                .then(Commands.literal("clearing")
                        .then(Commands.literal("start")
                                .then(Commands.literal("direct").executes(context ->
                                        start(context.getSource(), ClearingMode.DIRECT)))
                                .then(Commands.literal("bots").executes(context ->
                                        start(context.getSource(), ClearingMode.BOTS)))
                                .then(Commands.literal("hybrid").executes(context ->
                                        start(context.getSource(), ClearingMode.HYBRID))))
                        .then(Commands.literal("cancel").executes(context ->
                                cancel(context.getSource())))
                        .then(Commands.literal("status").executes(context ->
                                clearingStatus(context.getSource()))))
                .then(Commands.literal("grade")
                        .then(Commands.literal("corner1")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> gradeCorner(
                                                        context.getSource(), true,
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))
                        .then(Commands.literal("corner2")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> gradeCorner(
                                                        context.getSource(), false,
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))
                        .then(Commands.literal("height")
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .executes(context -> gradeHeight(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "y")))))
                        .then(Commands.literal("supply").executes(context ->
                                gradingSupplySet(context.getSource())))
                        .then(Commands.literal("start")
                                .then(Commands.argument("block_id", StringArgumentType.word())
                                        .executes(context -> gradeStart(context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "block_id")))))
                        .then(Commands.literal("force-confirm")
                                .then(Commands.argument("warning_hash", StringArgumentType.word())
                                        .executes(context -> gradeForceConfirm(
                                                context.getSource(), StringArgumentType.getString(
                                                        context, "warning_hash")))))
                        .then(Commands.literal("status").executes(context ->
                                gradeStatus(context.getSource())))
                        .then(Commands.literal("cancel").executes(context ->
                                cancel(context.getSource()))))
                .then(Commands.literal("prepared").executes(context ->
                        preparedStatus(context.getSource())));
        return site;
    }

    public static void tick(MinecraftServer server) {
        List<UUID> completedSurveys = new ArrayList<>();
        for (Map.Entry<UUID, PendingSurvey> entry : List.copyOf(SURVEYS.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                completedSurveys.add(entry.getKey());
                continue;
            }
            try {
                if (entry.getValue().advance(player.serverLevel(), CELLS_PER_TICK)) {
                    finishSurvey(player, entry.getValue());
                    completedSurveys.add(entry.getKey());
                }
            } catch (RuntimeException failure) {
                refuse(player.createCommandSourceStack(), "SITE_SURVEY_STALE",
                        failure.getMessage(), "Run site survey again after the world is stable");
                completedSurveys.add(entry.getKey());
            }
        }
        completedSurveys.forEach(SURVEYS::remove);

        List<UUID> completedClearing = new ArrayList<>();
        for (Map.Entry<UUID, ClearingSession> entry : List.copyOf(CLEARING.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().pause("OWNER_DISCONNECTED");
                continue;
            }
            try {
                if (entry.getValue().tick(player.serverLevel(), player)) {
                    completedClearing.add(entry.getKey());
                    beginPostClearanceSurvey(player, entry.getValue());
                }
            } catch (RuntimeException failure) {
                if (clearingFailureCode(failure.getMessage())
                        .equals("SALVAGE_DESTINATION_UNAVAILABLE")) {
                    entry.getValue().pause("SALVAGE_CAPACITY_OR_DESTINATION_CHANGED");
                    refuse(player.createCommandSourceStack(),
                            "SALVAGE_DESTINATION_UNAVAILABLE", failure.getMessage(),
                            "Restore the dedicated salvage container, make room, then resume");
                    continue;
                }
                entry.getValue().fail(failure.getMessage());
                refuse(player.createCommandSourceStack(),
                        clearingFailureCode(failure.getMessage()),
                        failure.getMessage(), "Inspect the exact target and start a fresh survey");
                completedClearing.add(entry.getKey());
            }
        }
        completedClearing.forEach(CLEARING::remove);
    }

    public static void clearServerState() {
        CLEARING.values().forEach(value -> value.cancel("server stopped"));
        CLEARING.clear();
        SURVEYS.clear();
        STATES.clear();
    }

    /** Exact read-only bridge for the existing player-visible production command. */
    static Optional<PreparedExecutionContext> preparedExecutionContext(ServerPlayer player) {
        PlayerSiteState state = restoredPlayerWorkflowState(player);
        if (state == null || state.prepared == null || state.confirmed == null
                || state.salvage == null || CLEARING.containsKey(player.getUUID())
                || SURVEYS.containsKey(player.getUUID())
                || !salvageCurrent(player.serverLevel(), state.salvage)) {
            return Optional.empty();
        }
        return Optional.of(new PreparedExecutionContext(state.prepared, state.confirmed));
    }

    /** Player-UX bridge into the same exact Site Preparation executor used by commands. */
    static PlayerClearingAction startPlayerWorkflow(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            DemolitionApprovalToken token,
            PlayerPreviewService.PreviewResult preview,
            PlayerSalvageSavedData.Entry salvageEntry) {
        if (player == null || project == null || token == null || preview == null
                || salvageEntry == null) {
            return PlayerClearingAction.failure("CLEARING_CONTEXT_INCOMPLETE");
        }
        if (SURVEYS.containsKey(player.getUUID()) || CLEARING.containsKey(player.getUUID())) {
            return PlayerClearingAction.failure("CLEARING_ALREADY_ACTIVE");
        }
        if (token.state() != DemolitionApprovalState.ACTIVE
                || !project.projectId().equals(salvageEntry.projectId())
                || !project.playerId().equals(player.getUUID())
                || !salvageEntry.playerId().equals(player.getUUID())
                || !project.dimension().equals(salvageEntry.dimension())
                || !preview.success() || !preview.planHash().equals(project.planHash())
                || !preview.snapshotHash().equals(project.siteSnapshotHash())) {
            return PlayerClearingAction.failure("DEMOLITION_APPROVAL_STALE");
        }
        DeploymentBoundingBox bounds = PlayerApprovalService.authorizedBounds(player, preview);
        String regionHash = PlayerApprovalService.regionAuthorizationHash(project, bounds);
        if (!bounds.contains(salvageEntry.position())
                || !regionHash.equals(token.context().regionAuthorizationHash())) {
            return PlayerClearingAction.failure("SALVAGE_OUTSIDE_AUTHORIZED_REGION");
        }
        BlockPos salvagePosition = block(salvageEntry.position());
        if (!player.serverLevel().hasChunkAt(salvagePosition)
                || !salvageEntry.stateFingerprint().equals(ForgeSiteSurveyAdapter.fingerprint(
                        player.serverLevel().getBlockState(salvagePosition)))
                || !(player.serverLevel().getBlockEntity(salvagePosition) instanceof Container)) {
            return PlayerClearingAction.failure("SALVAGE_DESTINATION_UNAVAILABLE");
        }
        List<ObstacleObservation> observations = new ArrayList<>();
        try {
            for (ApprovedObstacle approved : token.approvedObstacles()) {
                observations.add(ForgeSiteSurveyAdapter.observe(
                        player.serverLevel(), block(approved.position())));
            }
        } catch (RuntimeException stale) {
            return PlayerClearingAction.failure("DEMOLITION_APPROVAL_STALE");
        }
        DemolitionApprovalContext expectedContext = DemolitionApprovalContext.create(
                project.projectId().toString(), project.target(), project.quantity(), project.anchor(),
                project.orientation(), project.layoutVariant(), regionHash,
                PlayerApprovalService.SAFETY_POLICY, project.executionMode());
        var approvalCheck = APPROVALS.check(token, token.worldIdentity(), project.dimension(),
                project.planHash(), project.siteSnapshotHash(), player.getUUID().toString(),
                expectedContext, observations, token.approvedObstacles().size(), Instant.now());
        if (!approvalCheck.accepted()) {
            return PlayerClearingAction.failure("DEMOLITION_APPROVAL_STALE:" + approvalCheck.failures());
        }
        List<ObstacleFinding> findings = observations.stream()
                .map(new ObstacleClassifier()::classify).toList();
        SiteSurveySnapshot survey = new SiteSurveySnapshot(token.worldIdentity(),
                project.dimension(), bounds, regionHash, project.planHash(), Instant.now(),
                findings, project.siteSnapshotHash());
        Instant now = Instant.now();
        PlacementAnchor anchor = new PlacementAnchor(token.worldIdentity(), project.dimension(),
                project.anchor(), player.getUUID().toString(), now, AnchorSource.PLAYER_LOOK,
                sha256("player-workflow-anchor\n" + project.projectId() + "\n" + project.anchor()));
        ConfirmedSiteSelection confirmed = new ConfirmedSiteSelection(anchor,
                facing(project.orientation()), bounds, "player-workflow:" + project.projectId(),
                now, now.plusSeconds(3_600), regionHash);
        SalvageDestination salvage = new SalvageDestination(token.worldIdentity(),
                project.dimension(), salvagePosition.immutable(), salvageEntry.stateFingerprint(),
                player.getUUID().toString(), salvageEntry.destinationIdentity());
        PlayerSiteState state = new PlayerSiteState("player-workflow:" + project.projectId());
        state.anchor = anchor;
        state.facing = confirmed.facing();
        state.confirmed = confirmed;
        state.planHash = project.planHash();
        state.survey = survey;
        state.preview = DemolitionPreview.create(survey,
                Math.multiplyExact(20, findings.size()), findings.size(), token.expiresAt());
        state.approval = token;
        state.salvage = salvage;
        state.workflowProjectId = project.projectId();
        STATES.put(player.getUUID(), state);
        startValidated(player, state, clearingMode(project.executionMode()));
        ClearingSession session = CLEARING.get(player.getUUID());
        if (session == null) {
            STATES.remove(player.getUUID());
            return PlayerClearingAction.failure("CLEARING_START_REFUSED");
        }
        persistPlayerWorkflowState(player, state);
        return new PlayerClearingAction(true, "OK", session.sessionIdentity);
    }

    static PlayerClearingAction pausePlayerWorkflow(ServerPlayer player) {
        ClearingSession session = CLEARING.get(player.getUUID());
        if (session == null) return PlayerClearingAction.failure("CLEARING_NOT_ACTIVE");
        session.pause("PLAYER_PAUSED");
        return new PlayerClearingAction(true, "PAUSED", session.sessionIdentity);
    }

    static PlayerClearingAction resumePlayerWorkflow(ServerPlayer player) {
        ClearingSession session = CLEARING.get(player.getUUID());
        if (session == null) return PlayerClearingAction.failure("CLEARING_NOT_ACTIVE");
        if (!salvageCurrent(player.serverLevel(), session.destination)) {
            return PlayerClearingAction.failure("SALVAGE_DESTINATION_UNAVAILABLE");
        }
        session.resume();
        return new PlayerClearingAction(true, "RUNNING", session.sessionIdentity);
    }

    static PlayerClearingAction cancelPlayerWorkflow(ServerPlayer player) {
        ClearingSession session = CLEARING.get(player.getUUID());
        if (session == null) return PlayerClearingAction.failure("CLEARING_NOT_ACTIVE");
        if (!session.safeToCancel()) {
            return PlayerClearingAction.failure("CANCEL_REQUIRES_SALVAGE_DELIVERY");
        }
        CLEARING.remove(player.getUUID());
        session.cancel("cancelled by player workflow owner");
        releasePlayerWorkflowState(player);
        return new PlayerClearingAction(true, "CANCELLED", session.sessionIdentity);
    }

    static PlayerClearingSnapshot playerWorkflowSnapshot(ServerPlayer player) {
        ClearingSession session = CLEARING.get(player.getUUID());
        PlayerSiteState state = restoredPlayerWorkflowState(player);
        if (session != null) {
            return new PlayerClearingSnapshot(true, session.paused, false,
                    session.phase.name(), session.index, session.targets.size(),
                    session.mutations.size(), session.collectedCount(), session.deliveredCount(),
                    (int) session.bots.stream().filter(bot -> !bot.isRemoved()).count(),
                    session.pauseReason.isBlank() ? "RUNNING" : session.pauseReason,
                    session.safeToCancel());
        }
        boolean postScan = SURVEYS.containsKey(player.getUUID())
                && state != null && state.lastCompleted != null;
        boolean prepared = state != null && state.prepared != null;
        return new PlayerClearingSnapshot(false, false, prepared,
                prepared ? "PREPARED" : postScan ? "POST_CLEAR_RESCAN" : "IDLE",
                0, 0, state != null && state.lastCompleted != null
                        ? state.lastCompleted.mutations.size() : 0,
                state != null && state.lastCompleted != null
                        ? state.lastCompleted.collectedCount() : 0,
                state != null && state.lastCompleted != null
                        ? state.lastCompleted.deliveredCount() : 0,
                0, prepared ? "POST_CLEARANCE_VERIFIED" : postScan ? "RESCANNING" : "IDLE", true);
    }

    private static SiteFacing facing(dev.stevecreate.agent.core.model.QuarterTurn turn) {
        return switch (turn) {
            case ZERO -> SiteFacing.NORTH;
            case CLOCKWISE_90 -> SiteFacing.EAST;
            case CLOCKWISE_180 -> SiteFacing.SOUTH;
            case CLOCKWISE_270 -> SiteFacing.WEST;
        };
    }

    private static ClearingMode clearingMode(PlayerExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> ClearingMode.DIRECT;
            case HYBRID -> ClearingMode.HYBRID;
            case SMART_RECOMMENDED, BOTS -> ClearingMode.BOTS;
        };
    }

    static String clearingFailureCode(String suppliedDetail) {
        String detail = String.valueOf(suppliedDetail).toUpperCase(Locale.ROOT);
        if (detail.contains("DEMOLITION_APPROVAL_STALE")) {
            return "DEMOLITION_APPROVAL_STALE";
        }
        if (detail.contains("SALVAGE")) return "SALVAGE_DESTINATION_UNAVAILABLE";
        if (detail.contains("TOOL")) return "BOT_CLEARING_TOOL_UNAVAILABLE";
        if (detail.contains("LEVELING")) return "TERRAIN_LEVELING_UNSAFE";
        if (detail.contains("POST_CLEARANCE_SITE_CHANGED")) {
            return "POST_CLEARANCE_SITE_CHANGED";
        }
        return "BOT_CLEARING_PATH_UNREACHABLE";
    }

    static AcceptanceHandle acceptanceSession(
            ServerLevel level,
            BlockPos start,
            BlockPos target,
            BlockPos destination) {
        return acceptanceSession(level, start, List.of(target), destination);
    }

    static AcceptanceHandle acceptanceSession(
            ServerLevel level,
            BlockPos start,
            List<BlockPos> targets,
            BlockPos destination) {
        if (targets.isEmpty()) throw new IllegalArgumentException("targets are empty");
        int minimumX = Math.min(start.getX(), destination.getX());
        int minimumY = Math.min(start.getY(), destination.getY());
        int minimumZ = Math.min(start.getZ(), destination.getZ());
        int maximumX = Math.max(start.getX(), destination.getX());
        int maximumY = Math.max(start.getY(), destination.getY());
        int maximumZ = Math.max(start.getZ(), destination.getZ());
        for (BlockPos target : targets) {
            minimumX = Math.min(minimumX, target.getX());
            minimumY = Math.min(minimumY, target.getY());
            minimumZ = Math.min(minimumZ, target.getZ());
            maximumX = Math.max(maximumX, target.getX());
            maximumY = Math.max(maximumY, target.getY());
            maximumZ = Math.max(maximumZ, target.getZ());
        }
        BlockPos minimum = new BlockPos(minimumX - 2, minimumY - 1, minimumZ - 2);
        BlockPos maximum = new BlockPos(maximumX + 2, maximumY + 2, maximumZ + 2);
        return acceptanceSession(level, start, targets, destination,
                new DeploymentBoundingBox(position(minimum), position(maximum)));
    }

    static AcceptanceHandle acceptanceSession(
            ServerLevel level,
            BlockPos start,
            List<BlockPos> targets,
            BlockPos destination,
            DeploymentBoundingBox authorizedBounds) {
        if (targets.isEmpty()) throw new IllegalArgumentException("targets are empty");
        if (!authorizedBounds.contains(position(start))
                || !authorizedBounds.contains(position(destination))
                || targets.stream().map(SitePreparationCommand::position)
                        .anyMatch(value -> !authorizedBounds.contains(value))) {
            throw new IllegalArgumentException("acceptance points exceed authorized bounds");
        }
        BlockPos firstTarget = targets.get(0);
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        String world = "world:" + sha256("site-prep-gametest\n"
                + level.dimension().location() + "\n" + start + "\n" + targets + "\n"
                + destination + "\n" + authorizedBounds);
        ResourceId dimension = dimension(level);
        Instant now = Instant.now();
        PlacementAnchor anchor = SELECTIONS.anchor(world, dimension, position(firstTarget),
                playerIdentity(player), now, AnchorSource.PLAYER_LOOK);
        RegionCornerSelection region = SELECTIONS.corners(
                world, dimension, authorizedBounds.minimum(),
                world, dimension, authorizedBounds.maximum(), world, dimension,
                playerIdentity(player), now);
        ConfirmedSiteSelection confirmed = SELECTIONS.confirm(anchor, SiteFacing.NORTH, region,
                "site-gametest-session", now, now.plusSeconds(3_600));
        String planHash = sha256("site-prep-gametest-plan\n" + confirmed.selectionHash());
        List<ObstacleObservation> observations = targets.stream()
                .map(target -> ForgeSiteSurveyAdapter.observe(level, target)).toList();
        SiteSurveySnapshot survey = new SiteSurvey(
                new dev.stevecreate.agent.core.siteprep.ObstacleClassifier()).assemble(
                world, dimension, confirmed.authorizedBounds(), confirmed.selectionHash(),
                planHash, now, observations);
        if (survey.findings().stream().anyMatch(
                value -> !value.classification().approvable())) {
            throw new IllegalStateException("GameTest target is not approvable");
        }
        DemolitionPreview preview = DemolitionPreview.create(
                survey, Math.multiplyExact(20, targets.size()), targets.size(),
                now.plusSeconds(1_800));
        Set<String> obstacleIds = survey.findings().stream()
                .map(ObstacleFinding::obstacleId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        DemolitionApprovalToken approval = APPROVALS.issue(survey, preview,
                new DemolitionApprovalRequest(playerIdentity(player),
                        obstacleIds, obstacleIds.size(), now, now.plusSeconds(900)));
        PlayerSiteState state = new PlayerSiteState("site-gametest-session");
        state.anchor = anchor;
        state.region = region;
        state.facing = SiteFacing.NORTH;
        state.confirmed = confirmed;
        state.planHash = planHash;
        state.survey = survey;
        state.preview = preview;
        state.approval = approval;
        state.salvage = new SalvageDestination(world, dimension, destination.immutable(),
                ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(destination)),
                playerIdentity(player), "salvage:" + sha256(world + "\n" + destination));
        TerrainPreparationPlan plan = terrainPlan(state);
        ClearingSession session = new ClearingSession(player.getUUID(), world, dimension,
                confirmed, approval, plan, state.salvage, ClearingMode.BOTS,
                level, start);
        return new AcceptanceHandle(session, player, survey);
    }

    static AcceptanceHandle gradingAcceptanceSession(
            ServerLevel level,
            BlockPos start,
            BlockPos firstCorner,
            BlockPos secondCorner,
            int surfaceY,
            Block fillBlock,
            BlockPos destination) {
        return gradingAcceptanceSession(level, start, firstCorner, secondCorner,
                surfaceY, fillBlock, destination, false);
    }

    static AcceptanceHandle forcedGradingAcceptanceSession(
            ServerLevel level,
            BlockPos start,
            BlockPos firstCorner,
            BlockPos secondCorner,
            int surfaceY,
            Block fillBlock,
            BlockPos destination) {
        return gradingAcceptanceSession(level, start, firstCorner, secondCorner,
                surfaceY, fillBlock, destination, true);
    }

    private static AcceptanceHandle gradingAcceptanceSession(
            ServerLevel level,
            BlockPos start,
            BlockPos firstCorner,
            BlockPos secondCorner,
            int surfaceY,
            Block fillBlock,
            BlockPos destination,
            boolean forceHighRisk) {
        int minimumX = Math.min(firstCorner.getX(), secondCorner.getX());
        int maximumX = Math.max(firstCorner.getX(), secondCorner.getX());
        int minimumZ = Math.min(firstCorner.getZ(), secondCorner.getZ());
        int maximumZ = Math.max(firstCorner.getZ(), secondCorner.getZ());
        DeploymentBoundingBox scanBounds = new DeploymentBoundingBox(
                new BlockPos3i(minimumX, surfaceY - GRADING_MAXIMUM_FILL_DEPTH, minimumZ),
                new BlockPos3i(maximumX, surfaceY + GRADING_CLEARANCE_HEIGHT, maximumZ));
        DeploymentBoundingBox authority = new DeploymentBoundingBox(
                new BlockPos3i(Math.min(scanBounds.minimum().x() - 1,
                                Math.min(start.getX() - 1, destination.getX() - 1)),
                        Math.min(scanBounds.minimum().y(),
                                Math.min(start.getY() - 1, destination.getY() - 1)),
                        Math.min(scanBounds.minimum().z() - 1,
                                Math.min(start.getZ() - 1, destination.getZ() - 1))),
                new BlockPos3i(Math.max(scanBounds.maximum().x() + 1,
                                Math.max(start.getX() + 1, destination.getX() + 1)),
                        Math.max(scanBounds.maximum().y(),
                                Math.max(start.getY() + 1, destination.getY() + 1)),
                        Math.max(scanBounds.maximum().z() + 1,
                                Math.max(start.getZ() + 1, destination.getZ() + 1))));
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        String world = "world:" + sha256("site-grading-gametest\n" + start + "\n"
                + scanBounds + "\n" + destination);
        ResourceId levelDimension = dimension(level);
        Instant now = Instant.now();
        PlacementAnchor anchor = SELECTIONS.anchor(world, levelDimension,
                new BlockPos3i(minimumX, surfaceY, minimumZ), playerIdentity(player), now,
                AnchorSource.EXPLICIT_COORDINATE);
        RegionCornerSelection region = SELECTIONS.corners(world, levelDimension,
                authority.minimum(), world, levelDimension, authority.maximum(), world,
                levelDimension, playerIdentity(player), now);
        ConfirmedSiteSelection confirmed = SELECTIONS.confirm(anchor, SiteFacing.NORTH, region,
                "site-grading-gametest", now, now.plusSeconds(3_600));
        ResourceLocation fillKey = ForgeRegistries.BLOCKS.getKey(fillBlock);
        if (fillKey == null || !(fillBlock.asItem() instanceof BlockItem)
                || fillBlock.defaultBlockState().hasBlockEntity()) {
            throw new IllegalArgumentException("unsafe grading acceptance fill block");
        }
        String fillFingerprint = ForgeSiteSurveyAdapter.fingerprint(
                fillBlock.defaultBlockState());
        String planHash = sha256("site-grading-plan\n" + confirmed.selectionHash()
                + "\n" + fillKey + "\n" + fillFingerprint);
        Map<BlockPos3i, String> fingerprints = new LinkedHashMap<>();
        Set<BlockPos3i> air = new java.util.LinkedHashSet<>();
        Map<BlockPos3i, ObstacleObservation> observed = new LinkedHashMap<>();
        for (int y = scanBounds.minimum().y(); y <= scanBounds.maximum().y(); y++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(position)) {
                        throw new IllegalStateException("grading acceptance chunk is unloaded");
                    }
                    BlockPos3i exact = SitePreparationCommand.position(position);
                    BlockState state = level.getBlockState(position);
                    fingerprints.put(exact, ForgeSiteSurveyAdapter.fingerprint(state));
                    if (state.isAir()) air.add(exact);
                    else observed.put(exact, ForgeSiteSurveyAdapter.observe(level, position));
                }
            }
        }
        List<BlockPos3i> removals = new ArrayList<>();
        List<BlockPos3i> placements = new ArrayList<>();
        List<BlockPos3i> expected = new ArrayList<>();
        Map<BlockPos3i, String> before = new LinkedHashMap<>();
        List<ObstacleObservation> removalObservations = new ArrayList<>();
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                BlockPos3i surface = new BlockPos3i(x, surfaceY, z);
                expected.add(surface);
                if (!fillFingerprint.equals(fingerprints.get(surface))) {
                    if (!air.contains(surface)) {
                        removals.add(surface);
                        removalObservations.add(requiredObservation(observed, surface));
                    }
                    placements.add(surface);
                    before.put(surface, fingerprints.get(surface));
                }
                boolean support = false;
                for (int y = surfaceY - 1;
                        y >= surfaceY - GRADING_MAXIMUM_FILL_DEPTH; y--) {
                    BlockPos3i below = new BlockPos3i(x, y, z);
                    if (!air.contains(below)) {
                        support = true;
                        break;
                    }
                    expected.add(below);
                    placements.add(below);
                    before.put(below, fingerprints.get(below));
                }
                if (!support) throw new IllegalStateException("grading acceptance has no support");
                for (int y = surfaceY + 1;
                        y <= surfaceY + GRADING_CLEARANCE_HEIGHT; y++) {
                    BlockPos3i above = new BlockPos3i(x, y, z);
                    if (!air.contains(above)) {
                        removals.add(above);
                        removalObservations.add(requiredObservation(observed, above));
                        before.put(above, fingerprints.get(above));
                    }
                }
            }
        }
        removals.forEach(value -> before.putIfAbsent(value, fingerprints.get(value)));
        TerrainGradingSpecification grading = TerrainGradingSpecification.create(
                minimumX, minimumZ, maximumX, maximumZ, surfaceY,
                GRADING_CLEARANCE_HEIGHT, GRADING_MAXIMUM_FILL_DEPTH,
                ResourceId.parse(fillKey.toString()), fillFingerprint, removals, placements,
                expected, before);
        SiteSurveySnapshot survey = new SiteSurvey(
                new dev.stevecreate.agent.core.siteprep.ObstacleClassifier())
                .assembleExplicitGrading(world, levelDimension, scanBounds,
                        confirmed.selectionHash(), planHash, now, removalObservations);
        List<ObstacleFinding> hard = survey.findings().stream()
                .filter(value -> !value.classification().approvable()).toList();
        ForcedTerrainRemovalAuthorization forcedAuthorization = null;
        if (!hard.isEmpty()) {
            if (!forceHighRisk) {
                throw new IllegalStateException("grading acceptance contains hard-refused terrain");
            }
            List<ForcedTerrainRemoval> forced = hard.stream().map(value ->
                    new ForcedTerrainRemoval(value.position(), value.blockId(),
                            value.blockStateFingerprint(), value.container(),
                            value.blockEntity(), value.fluidRisk()
                                    || value.classification()
                                    == ObstacleClassification.ENVIRONMENTAL_HAZARD,
                            value.hardness() < 0.0D,
                            "GameTest exact destructive confirmation")).toList();
            survey = forcedApprovableSnapshot(survey,
                    forced.stream().map(ForcedTerrainRemoval::position)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            forcedAuthorization = ForcedTerrainRemovalAuthorization.confirm(
                    grading.specificationHash(), survey.siteSnapshotHash(),
                    playerIdentity(player), now, now.plusSeconds(900), forced);
        }
        SalvageDestination supply = new SalvageDestination(world, levelDimension,
                destination.immutable(),
                ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(destination)),
                playerIdentity(player), "grading-supply:" + sha256(world + "\n" + destination));
        if (gradingMaterialCount(level, supply, grading.fillBlockId())
                < grading.placementPositions().size()) {
            throw new IllegalStateException("grading acceptance supply is insufficient");
        }
        DemolitionPreview preview = DemolitionPreview.create(survey,
                Math.multiplyExact(20, grading.mutationCount()), grading.mutationCount(),
                now.plusSeconds(1_800));
        Set<String> ids = survey.findings().stream().map(ObstacleFinding::obstacleId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        DemolitionApprovalToken approval = APPROVALS.issue(survey, preview,
                new DemolitionApprovalRequest(playerIdentity(player), ids,
                        grading.mutationCount(), now, now.plusSeconds(900)));
        PlayerSiteState state = new PlayerSiteState("site-grading-gametest");
        state.anchor = anchor;
        state.region = region;
        state.facing = SiteFacing.NORTH;
        state.confirmed = confirmed;
        state.planHash = planHash;
        state.survey = survey;
        state.preview = preview;
        state.approval = approval;
        state.salvage = supply;
        state.gradingSpecification = grading;
        state.gradingScanBounds = scanBounds;
        state.forcedRemovalAuthorization = forcedAuthorization;
        TerrainPreparationPlan plan = terrainPlan(state);
        ClearingSession session = new ClearingSession(player.getUUID(), world, levelDimension,
                confirmed, approval, plan, supply, ClearingMode.BOTS, level, start);
        return new AcceptanceHandle(session, player, survey);
    }

    static ObstacleClassification acceptanceClassification(
            ServerLevel level,
            BlockPos position) {
        return new dev.stevecreate.agent.core.siteprep.ObstacleClassifier()
                .classify(ForgeSiteSurveyAdapter.observe(level, position)).classification();
    }

    private static int anchor(CommandSourceStack source, AnchorSource anchorSource) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (busy(context.player().getUUID(), source)) return 0;
        BlockPos position = context.player().blockPosition();
        if (anchorSource == AnchorSource.PLAYER_LOOK) {
            HitResult hit = context.player().pick(32.0D, 1.0F, false);
            if (!(hit instanceof BlockHitResult blockHit)
                    || hit.getType() != HitResult.Type.BLOCK) {
                return refuse(source, "SITE_ANCHOR_NOT_SELECTED",
                        "No block was hit within 32 blocks", "Look at the intended anchor block");
            }
            position = blockHit.getBlockPos();
        }
        state.anchor = SELECTIONS.anchor(context.identity().value(),
                dimension(context.player().serverLevel()), position(position),
                playerIdentity(context.player()), Instant.now(), anchorSource);
        invalidateAfterSelection(state);
        return success(source, "Site anchor selected source=" + anchorSource
                + " position=" + format(position) + " world=" + context.identity().value()
                + " dimension=" + dimension(context.player().serverLevel())
                + " selectionHash=" + state.anchor.selectionHash() + " worldMutation=false");
    }

    private static int anchorStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlacementAnchor anchor = state(player).anchor;
        if (anchor == null) return refuse(source, "SITE_ANCHOR_NOT_SELECTED",
                "No site anchor is selected", "Run site anchor here or site anchor look");
        return success(source, "Site anchor position=" + anchor.position() + " source="
                + anchor.source() + " world=" + anchor.worldIdentity() + " dimension="
                + anchor.dimension() + " player=" + anchor.playerIdentity() + " selectedAt="
                + anchor.selectedAt() + " selectionHash=" + anchor.selectionHash());
    }

    private static int clearAnchor(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        if (busy(player.getUUID(), source)) return 0;
        PlayerSiteState state = state(player);
        state.anchor = null;
        invalidateAfterSelection(state);
        return success(source, "Site anchor cleared worldMutation=false");
    }

    private static int corner(CommandSourceStack source, boolean first) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        if (busy(context.player().getUUID(), source)) return 0;
        PlayerSiteState state = state(context.player());
        Corner corner = new Corner(context.identity().value(),
                dimension(context.player().serverLevel()),
                position(context.player().blockPosition()), Instant.now());
        if (first) state.pos1 = corner;
        else state.pos2 = corner;
        state.region = null;
        invalidateConfirmation(state);
        if (state.pos1 != null && state.pos2 != null) {
            try {
                state.region = SELECTIONS.corners(context.identity().value(),
                        dimension(context.player().serverLevel()), state.pos1.position,
                        state.pos1.worldIdentity, state.pos1.dimension, state.pos2.position,
                        state.pos2.worldIdentity, state.pos2.dimension,
                        playerIdentity(context.player()), Instant.now());
            } catch (IllegalArgumentException failure) {
                return refuse(source, failure.getMessage(), "Corner identities cannot be combined",
                        "Clear the region and select both corners in this world and dimension");
            }
        }
        return success(source, (first ? "pos1=" : "pos2=") + corner.position
                + " world=" + corner.worldIdentity + " dimension=" + corner.dimension
                + " selectionReady=" + (state.region != null) + " worldMutation=false");
    }

    private static int regionPreview(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (state.region == null) return refuse(source, "SITE_REGION_NOT_SELECTED",
                "Both region corners are required", "Run site pos1 and site pos2");
        if (!state.region.worldIdentity().equals(context.identity().value())
                || !state.region.dimension().equals(dimension(context.player().serverLevel()))) {
            return refuse(source, "SITE_SELECTION_STALE",
                    "Selected region belongs to another world or dimension",
                    "Clear and select both corners again");
        }
        showBounds(context.player().serverLevel(), state.region.bounds());
        return success(source, "Site region preview bounds=" + format(state.region.bounds())
                + " volume=" + state.region.bounds().volume() + " selectionHash="
                + state.region.selectionHash()
                + " permanentBlocksPlaced=0 worldMutation=false");
    }

    private static int regionConfirm(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (state.anchor == null) return refuse(source, "SITE_ANCHOR_NOT_SELECTED",
                "Anchor is required before confirmation", "Select an anchor");
        if (state.region == null) return refuse(source, "SITE_REGION_NOT_SELECTED",
                "Region is required before confirmation", "Select and preview two corners");
        if (state.facing == null) return refuse(source, "SITE_FACING_NOT_SELECTED",
                "Facing is required before confirmation", "Select north, south, east, or west");
        try {
            state.confirmed = SELECTIONS.confirm(state.anchor, state.facing, state.region,
                    state.sessionIdentity, Instant.now(), Instant.now().plusSeconds(900));
        } catch (IllegalArgumentException failure) {
            return refuse(source, failure.getMessage(), "Site selection identities do not match",
                    "Select a fresh anchor and region in this world");
        }
        invalidateAfterConfirmation(state);
        state.planHash = sha256("site-prep-contract-v1\n" + state.confirmed.selectionHash());
        return success(source, "Site region confirmed anchor=" + state.confirmed.anchor().position()
                + " facing=" + state.confirmed.facing() + " bounds="
                + format(state.confirmed.authorizedBounds()) + " selectionHash="
                + state.confirmed.selectionHash() + " planHash=" + state.planHash
                + " writeAuthority=false");
    }

    private static int clearRegion(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        if (busy(player.getUUID(), source)) return 0;
        PlayerSiteState state = state(player);
        state.pos1 = null;
        state.pos2 = null;
        state.region = null;
        invalidateConfirmation(state);
        return success(source, "Site region cleared worldMutation=false");
    }

    private static int facing(CommandSourceStack source, SiteFacing facing) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        if (busy(player.getUUID(), source)) return 0;
        PlayerSiteState state = state(player);
        state.facing = facing;
        invalidateConfirmation(state);
        return success(source, "Site facing=" + facing + " worldMutation=false");
    }

    private static int rotateFacing(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        if (state.facing == null) return refuse(source, "SITE_FACING_NOT_SELECTED",
                "No facing is selected", "Select north, south, east, or west");
        return facing(source, state.facing.rotateClockwise());
    }

    private static int facingStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        SiteFacing facing = state(player).facing;
        if (facing == null) return refuse(source, "SITE_FACING_NOT_SELECTED",
                "No facing is selected", "Select north, south, east, or west");
        return success(source, "Site facing=" + facing + " worldMutation=false");
    }

    private static int gradeCorner(
            CommandSourceStack source, boolean first, int x, int z) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (busy(context.player().getUUID(), source)) return 0;
        GradeCorner corner = new GradeCorner(x, z);
        if (first) state.gradeCorner1 = corner;
        else state.gradeCorner2 = corner;
        invalidateGradingExecution(state);
        return success(source, "Terrain grading " + (first ? "corner1" : "corner2")
                + "=" + x + "," + z + " worldMutation=false");
    }

    private static int gradeHeight(CommandSourceStack source, int y) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        if (busy(context.player().getUUID(), source)) return 0;
        ServerLevel level = context.player().serverLevel();
        if (y - GRADING_MAXIMUM_FILL_DEPTH < level.getMinBuildHeight()
                || y + GRADING_CLEARANCE_HEIGHT >= level.getMaxBuildHeight()) {
            return refuse(source, "TERRAIN_LEVELING_UNSAFE",
                    "Target height exceeds the loaded dimension build bounds",
                    "Choose a height with bounded space above and below");
        }
        PlayerSiteState state = state(context.player());
        state.gradeSurfaceY = y;
        invalidateGradingExecution(state);
        return success(source, "Terrain grading height=" + y + " surfaceBlockY=" + y
                + " playerStandY=" + (y + 1) + " worldMutation=false");
    }

    private static int gradingSupplySet(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (busy(context.player().getUUID(), source)) return 0;
        HitResult hit = context.player().pick(16.0D, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return refuse(source, "TERRAIN_FILL_MATERIAL_UNAVAILABLE",
                    "No dedicated supply container was hit within 16 blocks",
                    "Look at the grading supply chest and run site grade supply");
        }
        BlockPos position = blockHit.getBlockPos();
        if (!(context.player().serverLevel().getBlockEntity(position) instanceof Container)) {
            return refuse(source, "TERRAIN_FILL_MATERIAL_UNAVAILABLE",
                    "Selected block is not a container",
                    "Use a dedicated non-private grading supply chest");
        }
        state.salvage = new SalvageDestination(context.identity().value(),
                dimension(context.player().serverLevel()), position.immutable(),
                ForgeSiteSurveyAdapter.fingerprint(
                        context.player().serverLevel().getBlockState(position)),
                playerIdentity(context.player()),
                "grading-supply:" + sha256(context.identity().value() + "\n"
                        + dimension(context.player().serverLevel()) + "\n" + position + "\n"
                        + ForgeSiteSurveyAdapter.fingerprint(
                                context.player().serverLevel().getBlockState(position))));
        return success(source, "Dedicated grading supply/salvage authorized identity="
                + state.salvage.identity + " position=" + format(position)
                + " playerInventoryAccess=0 privateContainerAccess=0 worldMutation=false");
    }

    private static int gradeStart(CommandSourceStack source, String suppliedBlockId) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player = context.player();
        PlayerSiteState state = state(player);
        if (busy(player.getUUID(), source)) return 0;
        if (state.gradeCorner1 == null || state.gradeCorner2 == null
                || state.gradeSurfaceY == null) {
            return refuse(source, "SITE_REGION_NOT_SELECTED",
                    "Two X/Z corners and one target height are required",
                    "Enter site grade corner1, corner2 and height before the block type");
        }
        if (state.salvage == null || !salvageCurrent(player.serverLevel(), state.salvage)) {
            return refuse(source, "TERRAIN_FILL_MATERIAL_UNAVAILABLE",
                    "No current dedicated grading supply/salvage container is authorized",
                    "Look at it once and run site grade supply");
        }
        ResourceLocation key = ResourceLocation.tryParse(suppliedBlockId);
        Block fillBlock = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
        if (key == null || fillBlock == null || !ForgeRegistries.BLOCKS.containsKey(key)
                || fillBlock == Blocks.AIR || fillBlock == Blocks.CAVE_AIR
                || fillBlock == Blocks.VOID_AIR || fillBlock == Blocks.FIRE
                || fillBlock == Blocks.SOUL_FIRE || fillBlock == Blocks.TNT
                || !fillBlock.defaultBlockState().getFluidState().isEmpty()
                || fillBlock.defaultBlockState().hasBlockEntity()
                || !(fillBlock.asItem() instanceof BlockItem)) {
            return refuse(source, "TERRAIN_FILL_BLOCK_UNSUPPORTED",
                    "Fill block must be a registered state-only non-hazard BlockItem",
                    "Choose an ordinary placeable block such as minecraft:stone");
        }
        int minimumX = Math.min(state.gradeCorner1.x, state.gradeCorner2.x);
        int maximumX = Math.max(state.gradeCorner1.x, state.gradeCorner2.x);
        int minimumZ = Math.min(state.gradeCorner1.z, state.gradeCorner2.z);
        int maximumZ = Math.max(state.gradeCorner1.z, state.gradeCorner2.z);
        long area = Math.multiplyExact(
                Math.addExact(Math.subtractExact((long) maximumX, minimumX), 1L),
                Math.addExact(Math.subtractExact((long) maximumZ, minimumZ), 1L));
        if (area < 1 || area > TerrainGradingSpecification.MAX_MUTATIONS) {
            return refuse(source, "TERRAIN_LEVELING_UNSAFE",
                    "Rectangle area exceeds the 4096-action safety envelope",
                    "Choose a smaller rectangular grading site");
        }
        int surfaceY = state.gradeSurfaceY;
        DeploymentBoundingBox scanBounds = new DeploymentBoundingBox(
                new BlockPos3i(minimumX, surfaceY - GRADING_MAXIMUM_FILL_DEPTH, minimumZ),
                new BlockPos3i(maximumX, surfaceY + GRADING_CLEARANCE_HEIGHT, maximumZ));
        BlockPos supply = state.salvage.position;
        BlockPos playerPosition = player.blockPosition();
        DeploymentBoundingBox authorityBounds = new DeploymentBoundingBox(
                new BlockPos3i(Math.min(minimumX - 1,
                                Math.min(supply.getX() - 1, playerPosition.getX() - 1)),
                        Math.min(scanBounds.minimum().y(),
                                Math.min(supply.getY() - 1, playerPosition.getY() - 1)),
                        Math.min(minimumZ - 1,
                                Math.min(supply.getZ() - 1, playerPosition.getZ() - 1))),
                new BlockPos3i(Math.max(maximumX + 1,
                                Math.max(supply.getX() + 1, playerPosition.getX() + 1)),
                        Math.max(scanBounds.maximum().y(),
                                Math.max(supply.getY() + 1, playerPosition.getY() + 1)),
                        Math.max(maximumZ + 1,
                                Math.max(supply.getZ() + 1, playerPosition.getZ() + 1))));
        if (authorityBounds.volume() > SiteSelectionService.MAX_REGION_VOLUME) {
            return refuse(source, "SITE_OUTSIDE_AUTHORIZED_REGION",
                    "The rectangle, player and dedicated supply are too far apart",
                    "Move the dedicated supply chest within the bounded grading work area");
        }
        Instant now = Instant.now();
        ResourceId levelDimension = dimension(player.serverLevel());
        PlacementAnchor anchor = SELECTIONS.anchor(context.identity().value(), levelDimension,
                new BlockPos3i(minimumX + (maximumX - minimumX) / 2, surfaceY,
                        minimumZ + (maximumZ - minimumZ) / 2),
                playerIdentity(player), now, AnchorSource.EXPLICIT_COORDINATE);
        RegionCornerSelection region = SELECTIONS.corners(context.identity().value(), levelDimension,
                authorityBounds.minimum(), context.identity().value(), levelDimension,
                authorityBounds.maximum(), context.identity().value(), levelDimension,
                playerIdentity(player), now);
        state.anchor = anchor;
        state.region = region;
        state.facing = SiteFacing.NORTH;
        state.confirmed = SELECTIONS.confirm(anchor, state.facing, region,
                state.sessionIdentity, now, now.plusSeconds(1_800));
        state.gradingScanBounds = scanBounds;
        state.gradingFillBlockId = ResourceId.parse(key.toString());
        state.gradingFillStateFingerprint = ForgeSiteSurveyAdapter.fingerprint(
                fillBlock.defaultBlockState());
        state.planHash = sha256("terrain-grading-v1\n" + scanBounds + "\n" + surfaceY
                + "\n" + key + "\n" + state.gradingFillStateFingerprint);
        state.survey = null;
        state.preview = null;
        state.approval = null;
        state.prepared = null;
        state.gradingSpecification = null;
        state.pendingForcedAuthorization = null;
        state.forcedRemovalAuthorization = null;
        SURVEYS.put(player.getUUID(), new PendingSurvey(context.identity().value(), levelDimension,
                state.confirmed, state.planHash, SurveyPurpose.GRADING_INITIAL, scanBounds));
        return success(source, "Terrain grading exact scan started rectangle=" + minimumX + ","
                + minimumZ + ".." + maximumX + "," + maximumZ + " surfaceY=" + surfaceY
                + " clearance=" + GRADING_CLEARANCE_HEIGHT + " fillDepth="
                + GRADING_MAXIMUM_FILL_DEPTH + " block=" + key + " cellsPerTick="
                + CELLS_PER_TICK + " finalPlayerGroupApproval=true worldMutation=false");
    }

    private static int gradeStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        return success(source, "Terrain grading corner1=" + state.gradeCorner1
                + " corner2=" + state.gradeCorner2 + " height=" + state.gradeSurfaceY
                + " fillBlock=" + state.gradingFillBlockId + " supply="
                + (state.salvage == null ? "unset" : state.salvage.identity)
                + " pendingForceWarning=" + (state.pendingForcedAuthorization == null
                        ? "none" : state.pendingForcedAuthorization.warningHash())
                + " active=" + CLEARING.containsKey(player.getUUID()) + " prepared="
                + (state.prepared != null) + " safety=" + state.latestSafetyLine);
    }

    private static int gradeForceConfirm(CommandSourceStack source, String warningHash) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player = context.player();
        PlayerSiteState state = state(player);
        if (busy(player.getUUID(), source)) return 0;
        ForcedTerrainRemovalAuthorization pending = state.pendingForcedAuthorization;
        if (pending == null || !pending.warningHash().equals(warningHash)
                || !pending.playerIdentity().equals(playerIdentity(player))
                || !Instant.now().isBefore(pending.expiresAt())
                || state.confirmed == null || state.gradingScanBounds == null) {
            return refuse(source, "FORCED_TERRAIN_CONFIRMATION_STALE",
                    "No current exact high-risk warning matches that hash",
                    "Run site grade start again and review the new warning");
        }
        SURVEYS.put(player.getUUID(), new PendingSurvey(context.identity().value(),
                dimension(player.serverLevel()), state.confirmed, state.planHash,
                SurveyPurpose.GRADING_FORCE_VALIDATION, state.gradingScanBounds));
        return success(source, "High-risk terrain deletion confirmation accepted for exact"
                + " revalidation warningHash=" + warningHash + " forcedCells="
                + pending.removals().size() + " worldMutation=false");
    }

    private static int survey(CommandSourceStack source, boolean postClearance) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (state.confirmed == null) return refuse(source, "SITE_REGION_NOT_SELECTED",
                "A confirmed anchor, region and facing are required",
                "Complete site anchor, facing, region preview and region confirm");
        if (busy(context.player().getUUID(), source)) return 0;
        if (state.confirmed.authorizedBounds().volume()
                > SiteSelectionService.MAX_REGION_VOLUME) {
            return refuse(source, "SITE_OUTSIDE_AUTHORIZED_REGION",
                    "Confirmed region exceeds the bounded site scan limit",
                    "Select a smaller site region");
        }
        PendingSurvey pending = new PendingSurvey(context.identity().value(),
                dimension(context.player().serverLevel()), state.confirmed,
                state.planHash, postClearance ? SurveyPurpose.POST_CLEARANCE
                        : SurveyPurpose.INITIAL);
        SURVEYS.put(context.player().getUUID(), pending);
        return success(source, "Site survey started bounds="
                + format(state.confirmed.authorizedBounds()) + " cellsPerTick="
                + CELLS_PER_TICK + " playerInventoryAccess=0 containerContentsRead=0"
                + " regionOutsideReads=0 worldMutation=false");
    }

    private static void finishSurvey(ServerPlayer player, PendingSurvey pending) {
        PlayerSiteState state = state(player);
        if (state.confirmed != pending.selection
                || !state.planHash.equals(pending.planHash)) {
            refuse(player.createCommandSourceStack(), "SITE_SELECTION_STALE",
                    "Selection changed while survey was running", "Run a fresh survey");
            return;
        }
        if (pending.purpose == SurveyPurpose.GRADING_INITIAL
                || pending.purpose == SurveyPurpose.GRADING_FORCE_VALIDATION) {
            finishGradingSurvey(player, state, pending,
                    pending.purpose == SurveyPurpose.GRADING_FORCE_VALIDATION);
            return;
        }
        List<ObstacleObservation> surveyObservations = pending.observations;
        if (pending.purpose == SurveyPurpose.POST_CLEARANCE
                && state.gradingSpecification != null) {
            TerrainGradingSpecification grading = state.gradingSpecification;
            Set<BlockPos3i> expected = Set.copyOf(grading.expectedFillPositions());
            surveyObservations = pending.observations.stream()
                    .filter(value -> value.position().y() > grading.surfaceY()
                            || expected.contains(value.position()))
                    .toList();
        }
        SiteSurveySnapshot snapshot = new SiteSurvey(
                new dev.stevecreate.agent.core.siteprep.ObstacleClassifier()).assemble(
                pending.worldIdentity, pending.dimension, pending.scanBounds,
                pending.selection.selectionHash(), pending.planHash, Instant.now(),
                surveyObservations);
        if (pending.purpose == SurveyPurpose.INITIAL) {
            state.survey = snapshot;
            state.preview = null;
            state.approval = null;
            state.prepared = null;
            state.latestSafetyLine = "unknownBlocksRemoved=0 protectedBlocksRemoved=0"
                    + " containerOpened=0 playerInventoryAccess=0 regionOutsideMutations=0"
                    + " formalWorldAccess=0";
            success(player.createCommandSourceStack(), "Site survey complete snapshotHash="
                    + snapshot.siteSnapshotHash() + " findings=" + snapshot.findings().size()
                    + " classes=" + counts(snapshot) + " " + state.latestSafetyLine);
            return;
        }
        if (pending.purpose == SurveyPurpose.PRE_CLEAR_VALIDATION) {
            if (state.survey == null || !snapshot.siteSnapshotHash().equals(
                    state.survey.siteSnapshotHash())) {
                state.pendingMode = null;
                refuse(player.createCommandSourceStack(), "DEMOLITION_APPROVAL_STALE",
                        "Any relevant region block changed after approval",
                        "Review changes and run a fresh survey, preview and approval");
                return;
            }
            ClearingMode mode = state.pendingMode;
            state.pendingMode = null;
            startValidated(player, state, mode);
            return;
        }
        finishPostClearance(player, state, snapshot);
    }

    private static void finishGradingSurvey(
            ServerPlayer player,
            PlayerSiteState state,
            PendingSurvey pending,
            boolean forceConfirmed) {
        if (state.gradingScanBounds == null || state.gradingFillBlockId == null
                || state.gradingFillStateFingerprint == null || state.gradeSurfaceY == null
                || state.gradeCorner1 == null || state.gradeCorner2 == null
                || state.salvage == null || !salvageCurrent(player.serverLevel(), state.salvage)) {
            refuse(player.createCommandSourceStack(), "TERRAIN_LEVELING_UNSAFE",
                    "Grading authority changed during the bounded scan",
                    "Enter the exact grading inputs again");
            return;
        }
        int surfaceY = state.gradeSurfaceY;
        List<BlockPos3i> removals = new ArrayList<>();
        List<BlockPos3i> placements = new ArrayList<>();
        List<BlockPos3i> expectedFill = new ArrayList<>();
        Map<BlockPos3i, String> before = new LinkedHashMap<>();
        Map<BlockPos3i, ObstacleObservation> observations = pending.observations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObstacleObservation::position, value -> value));
        List<ObstacleObservation> removalObservations = new ArrayList<>();
        for (int x = state.gradingScanBounds.minimum().x();
                x <= state.gradingScanBounds.maximum().x(); x++) {
            for (int z = state.gradingScanBounds.minimum().z();
                    z <= state.gradingScanBounds.maximum().z(); z++) {
                BlockPos3i surface = new BlockPos3i(x, surfaceY, z);
                expectedFill.add(surface);
                String surfaceFingerprint = pending.stateFingerprints.get(surface);
                boolean surfaceAir = pending.airPositions.contains(surface);
                if (!state.gradingFillStateFingerprint.equals(surfaceFingerprint)) {
                    if (!surfaceAir) {
                        removals.add(surface);
                        removalObservations.add(requiredObservation(observations, surface));
                    }
                    placements.add(surface);
                    before.put(surface, surfaceFingerprint);
                }
                boolean supportFound = false;
                for (int y = surfaceY - 1;
                        y >= surfaceY - GRADING_MAXIMUM_FILL_DEPTH; y--) {
                    BlockPos3i below = new BlockPos3i(x, y, z);
                    if (!pending.airPositions.contains(below)) {
                        supportFound = true;
                        break;
                    }
                    expectedFill.add(below);
                    placements.add(below);
                    before.put(below, pending.stateFingerprints.get(below));
                }
                if (!supportFound) {
                    refuse(player.createCommandSourceStack(), "TERRAIN_LEVELING_UNSAFE",
                            "A grading column has no stable support within "
                                    + GRADING_MAXIMUM_FILL_DEPTH + " blocks at " + x + "," + z,
                            "Choose a shallower hole or provide stable support");
                    return;
                }
                for (int y = surfaceY + 1;
                        y <= surfaceY + GRADING_CLEARANCE_HEIGHT; y++) {
                    BlockPos3i above = new BlockPos3i(x, y, z);
                    if (!pending.airPositions.contains(above)) {
                        removals.add(above);
                        removalObservations.add(requiredObservation(observations, above));
                        before.put(above, pending.stateFingerprints.get(above));
                    }
                }
            }
        }
        for (BlockPos3i removal : removals) {
            before.putIfAbsent(removal, pending.stateFingerprints.get(removal));
        }
        TerrainGradingSpecification grading;
        try {
            grading = TerrainGradingSpecification.create(
                    state.gradeCorner1.x, state.gradeCorner1.z,
                    state.gradeCorner2.x, state.gradeCorner2.z,
                    surfaceY, GRADING_CLEARANCE_HEIGHT, GRADING_MAXIMUM_FILL_DEPTH,
                    state.gradingFillBlockId, state.gradingFillStateFingerprint,
                    removals, placements, expectedFill, before);
        } catch (IllegalArgumentException failure) {
            refuse(player.createCommandSourceStack(), "TERRAIN_LEVELING_UNSAFE",
                    failure.getMessage(), "Choose a smaller or shallower grading rectangle");
            return;
        }
        SiteSurveySnapshot snapshot = new SiteSurvey(
                new dev.stevecreate.agent.core.siteprep.ObstacleClassifier())
                .assembleExplicitGrading(pending.worldIdentity, pending.dimension,
                        pending.scanBounds, pending.selection.selectionHash(), pending.planHash,
                        Instant.now(), removalObservations);
        List<ObstacleFinding> hardRefusals = snapshot.findings().stream()
                .filter(value -> !value.classification().approvable()).toList();
        ForcedTerrainRemovalAuthorization forcedAuthorization = null;
        if (!hardRefusals.isEmpty()) {
            List<ForcedTerrainRemoval> forced = new ArrayList<>();
            for (ObstacleFinding finding : hardRefusals) {
                ObstacleObservation observation = requiredObservation(
                        observations, finding.position());
                boolean dangerous = finding.fluidRisk()
                        || finding.classification() == ObstacleClassification.ENVIRONMENTAL_HAZARD;
                boolean unbreakable = finding.hardness() < 0.0D;
                if (!finding.container() && !finding.blockEntity()
                        && !dangerous && !unbreakable) {
                    refuse(player.createCommandSourceStack(), "PROTECTED_OBSTACLE_PRESENT",
                            "Protected or claimed cell is not eligible for destructive override at "
                                    + finding.position(),
                            "Move the rectangle or resolve the external protection");
                    return;
                }
                forced.add(new ForcedTerrainRemoval(finding.position(), finding.blockId(),
                        finding.blockStateFingerprint(), finding.container(),
                        finding.blockEntity(), dangerous, unbreakable,
                        "Permanent data loss or environmental mutation may occur"));
            }
            snapshot = forcedApprovableSnapshot(snapshot,
                    forced.stream().map(ForcedTerrainRemoval::position)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            Instant forceNow = Instant.now();
            Instant forceExpiry = forceConfirmed && state.pendingForcedAuthorization != null
                    ? state.pendingForcedAuthorization.expiresAt()
                    : forceNow.plusSeconds(180);
            ForcedTerrainRemovalAuthorization candidate =
                    ForcedTerrainRemovalAuthorization.confirm(grading.specificationHash(),
                            snapshot.siteSnapshotHash(), playerIdentity(player), forceNow,
                            forceExpiry, forced);
            if (!forceConfirmed) {
                state.survey = snapshot;
                state.gradingSpecification = grading;
                state.pendingForcedAuthorization = candidate;
                CommandSourceStack source = player.createCommandSourceStack();
                refuse(source, "HIGH_RISK_TERRAIN_CONFIRMATION_REQUIRED",
                        "Exact grading snapshot contains " + forced.size()
                                + " destructive high-risk cells; contents/NBT will not be read",
                        "Review warnings, then run site grade force-confirm "
                                + candidate.warningHash());
                forced.stream().limit(MAX_VISIBLE_FINDINGS).forEach(value ->
                        source.sendFailure(Component.literal("WARNING pos=" + value.position()
                                + " block=" + value.blockId() + " container=" + value.container()
                                + " blockEntity=" + value.blockEntity() + " dangerousMedium="
                                + value.dangerousMedium() + " unbreakable="
                                + value.unbreakable() + " permanentDataLoss=true")));
                return;
            }
            ForcedTerrainRemovalAuthorization pendingAuthorization =
                    state.pendingForcedAuthorization;
            if (pendingAuthorization == null
                    || !pendingAuthorization.warningHash().equals(candidate.warningHash())
                    || !pendingAuthorization.removals().equals(candidate.removals())) {
                state.pendingForcedAuthorization = null;
                refuse(player.createCommandSourceStack(),
                        "FORCED_TERRAIN_CONFIRMATION_STALE",
                        "High-risk cells or exact grading state changed during revalidation",
                        "Run site grade start again and review the new warning");
                return;
            }
            forcedAuthorization = candidate;
        } else if (forceConfirmed) {
            state.pendingForcedAuthorization = null;
            refuse(player.createCommandSourceStack(), "FORCED_TERRAIN_CONFIRMATION_STALE",
                    "The previously warned high-risk state is no longer present",
                    "Run site grade start again for the current site");
            return;
        }
        int available = gradingMaterialCount(player.serverLevel(), state.salvage,
                grading.fillBlockId());
        if (available < grading.placementPositions().size()) {
            refuse(player.createCommandSourceStack(), "TERRAIN_FILL_MATERIAL_UNAVAILABLE",
                    "Dedicated supply has " + available + " but exact fill requires "
                            + grading.placementPositions().size() + " untagged blocks",
                    "Add the selected ordinary blocks to the authorized supply chest");
            return;
        }
        Instant now = Instant.now();
        DemolitionPreview preview = DemolitionPreview.create(snapshot,
                Math.multiplyExact(20, grading.mutationCount()), grading.mutationCount(),
                now.plusSeconds(300));
        Set<String> obstacleIds = snapshot.findings().stream()
                .map(ObstacleFinding::obstacleId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        DemolitionApprovalToken approval = APPROVALS.issue(snapshot, preview,
                new DemolitionApprovalRequest(playerIdentity(player), obstacleIds,
                        grading.mutationCount(), now, now.plusSeconds(240)));
        state.survey = snapshot;
        state.preview = preview;
        state.approval = approval;
        state.gradingSpecification = grading;
        state.forcedRemovalAuthorization = forcedAuthorization;
        state.pendingForcedAuthorization = null;
        state.latestSafetyLine = "forcedHighRisk=" + (forcedAuthorization != null)
                + " privateContainerContentsRead=0 playerInventoryAccess=0"
                + " regionOutsideMutations=0 formalWorldAccess=0";
        startValidated(player, state, ClearingMode.BOTS);
    }

    private static SiteSurveySnapshot forcedApprovableSnapshot(
            SiteSurveySnapshot snapshot,
            Set<BlockPos3i> forcedPositions) {
        List<ObstacleFinding> findings = snapshot.findings().stream().map(value -> {
            if (!forcedPositions.contains(value.position())) return value;
            return new ObstacleFinding(value.obstacleId(), value.position(), value.blockId(),
                    value.blockStateFingerprint(), value.blockEntity(), value.container(),
                    value.hasInventory(), value.machine(), value.naturalCandidate(),
                    value.hardness(), value.toolRequirement(), value.dropsExpectation(),
                    value.fluidRisk(), ObstacleClassification.CONFIRM_EACH_OR_GROUP, 100,
                    value.evidenceSource(),
                    "separate exact high-risk player confirmation required");
        }).toList();
        return SiteSurveySnapshot.create(snapshot.worldIdentity(), snapshot.dimension(),
                snapshot.scannedBounds(), snapshot.selectionHash(), snapshot.planHash(),
                snapshot.scannedAt(), findings);
    }

    private static ObstacleObservation requiredObservation(
            Map<BlockPos3i, ObstacleObservation> observations,
            BlockPos3i position) {
        ObstacleObservation observation = observations.get(position);
        if (observation == null) {
            throw new IllegalStateException(
                    "SITE_SURVEY_STALE: non-air grading observation disappeared");
        }
        return observation;
    }

    private static int obstacles(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        SiteSurveySnapshot survey = state(player).survey;
        if (survey == null) return refuse(source, "SITE_SURVEY_REQUIRED",
                "No current site survey exists", "Run site survey");
        success(source, "Site obstacles snapshotHash=" + survey.siteSnapshotHash()
                + " total=" + survey.findings().size() + " classes=" + counts(survey));
        survey.findings().stream().limit(MAX_VISIBLE_FINDINGS).forEach(finding ->
                source.sendSuccess(() -> Component.literal(finding.obstacleId() + " pos="
                        + finding.position() + " block=" + finding.blockId() + " class="
                        + finding.classification() + " confidence=" + finding.confidence()
                        + " reason=" + finding.reason()), false));
        if (survey.findings().size() > MAX_VISIBLE_FINDINGS) {
            source.sendSuccess(() -> Component.literal("Obstacle list truncated displayed="
                    + MAX_VISIBLE_FINDINGS + " total=" + survey.findings().size()), false);
        }
        return 1;
    }

    private static int demolitionPreview(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        if (state.survey == null) return refuse(source, "SITE_SURVEY_REQUIRED",
                "No current survey exists", "Run site survey");
        state.preview = DemolitionPreview.create(state.survey,
                Math.multiplyExact(state.survey.findings().size(), 20),
                state.survey.findings().size(), Instant.now().plusSeconds(300));
        return success(source, "Demolition preview approvalHash="
                + state.preview.approvalHash() + " snapshotHash="
                + state.preview.siteSnapshotHash() + " planHash=" + state.preview.planHash()
                + " counts=" + state.preview.counts() + " estimatedTicks="
                + state.preview.estimatedTicks() + " mutationBudget="
                + state.preview.mutationBudget() + " expires=" + state.preview.expiresAt()
                + " protectedPreserved=true approveAllAvailable=false");
    }

    private static int approveSafe(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        if (state.preview == null || state.survey == null) return refuse(source,
                "DEMOLITION_APPROVAL_REQUIRED", "A current demolition preview is required",
                "Run site demolition preview");
        Set<String> safe = state.survey.findings().stream()
                .filter(value -> value.classification()
                        == ObstacleClassification.SAFE_NATURAL_CLEARABLE)
                .map(ObstacleFinding::obstacleId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (safe.isEmpty()) return refuse(source, "DEMOLITION_APPROVAL_SCOPE_MISMATCH",
                "No SAFE_NATURAL_CLEARABLE obstacles exist",
                "Adjust the site or approve one eligible ordinary obstacle by ID");
        return issueApproval(source, player, safe);
    }

    private static int approveOne(CommandSourceStack source, String obstacleId) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        return issueApproval(source, player, Set.of(obstacleId));
    }

    private static int issueApproval(
            CommandSourceStack source,
            ServerPlayer player,
            Set<String> obstacleIds) {
        PlayerSiteState state = state(player);
        if (state.preview == null || state.survey == null) return refuse(source,
                "DEMOLITION_APPROVAL_REQUIRED", "A current demolition preview is required",
                "Run site demolition preview");
        try {
            state.approval = APPROVALS.issue(state.survey, state.preview,
                    new DemolitionApprovalRequest(playerIdentity(player), obstacleIds,
                            obstacleIds.size(), Instant.now(),
                            minimum(state.preview.expiresAt(), Instant.now().plusSeconds(120))));
        } catch (IllegalArgumentException failure) {
            return refuse(source, failure.getMessage(), "Approval request failed closed",
                    "Review the current obstacle list and create a fresh preview");
        }
        return success(source, "Demolition approved token=" + state.approval.tokenIdentity()
                + " exactPositions=" + state.approval.approvedObstacles().size()
                + " state=" + state.approval.state() + " expires="
                + state.approval.expiresAt() + " oneTime=true revocable=true");
    }

    private static int revoke(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        if (state.approval == null) return refuse(source, "DEMOLITION_APPROVAL_REQUIRED",
                "No approval token exists", "Create a demolition preview and approval");
        state.approval = APPROVALS.revoke(state.approval);
        return success(source, "Demolition approval revoked token="
                + state.approval.tokenIdentity() + " state=" + state.approval.state());
    }

    private static int approvalStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        DemolitionApprovalToken token = state(player).approval;
        if (token == null) return refuse(source, "DEMOLITION_APPROVAL_REQUIRED",
                "No approval token exists", "Create a demolition preview and approval");
        return success(source, "Demolition token=" + token.tokenIdentity() + " state="
                + token.state() + " world=" + token.worldIdentity() + " dimension="
                + token.dimension() + " planHash=" + token.planHash() + " snapshotHash="
                + token.siteSnapshotHash() + " exactPositions="
                + token.approvedObstacles().size() + " maximumMutations="
                + token.maximumMutations() + " expires=" + token.expiresAt());
    }

    private static int salvageSet(CommandSourceStack source) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        PlayerSiteState state = state(context.player());
        if (state.confirmed == null) return refuse(source, "SITE_REGION_NOT_SELECTED",
                "Confirm the site before selecting salvage storage",
                "Complete site region confirm");
        HitResult hit = context.player().pick(16.0D, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return refuse(source, "SALVAGE_DESTINATION_UNAVAILABLE",
                    "No container was hit within 16 blocks",
                    "Look at a dedicated empty test-world salvage chest");
        }
        BlockPos position = blockHit.getBlockPos();
        if (!state.confirmed.authorizedBounds().contains(position(position))) {
            return refuse(source, "SITE_OUTSIDE_AUTHORIZED_REGION",
                    "Salvage destination is outside the confirmed region",
                    "Select a dedicated container inside the confirmed region");
        }
        BlockEntity entity = context.player().serverLevel().getBlockEntity(position);
        if (!(entity instanceof Container)) {
            return refuse(source, "SALVAGE_DESTINATION_UNAVAILABLE",
                    "Selected block is not a container",
                    "Look at a dedicated chest or barrel in the confirmed region");
        }
        state.salvage = new SalvageDestination(context.identity().value(),
                dimension(context.player().serverLevel()), position.immutable(),
                ForgeSiteSurveyAdapter.fingerprint(
                        context.player().serverLevel().getBlockState(position)),
                playerIdentity(context.player()),
                "salvage:" + sha256(context.identity().value() + "\n"
                        + dimension(context.player().serverLevel()) + "\n" + position + "\n"
                        + ForgeSiteSurveyAdapter.fingerprint(
                                context.player().serverLevel().getBlockState(position))));
        return success(source, "Salvage destination authorized identity="
                + state.salvage.identity + " position=" + format(position)
                + " exactStateFingerprint=" + state.salvage.stateFingerprint
                + " playerInventoryAccess=0");
    }

    private static int salvageStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        SalvageDestination salvage = state(player).salvage;
        if (salvage == null) return refuse(source, "SALVAGE_DESTINATION_UNAVAILABLE",
                "No salvage destination is authorized",
                "Look at a dedicated container and run site salvage set");
        return success(source, "Salvage destination=" + salvage.identity + " position="
                + format(salvage.position) + " world=" + salvage.worldIdentity
                + " dimension=" + salvage.dimension + " player=" + salvage.playerIdentity);
    }

    private static int start(CommandSourceStack source, ClearingMode mode) {
        PilotRegionCommand.CurrentPilotContext context = current(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player = context.player();
        PlayerSiteState state = state(player);
        if (busy(player.getUUID(), source)) return 0;
        if (state.confirmed == null || state.survey == null || state.preview == null
                || state.approval == null) {
            return refuse(source, "DEMOLITION_APPROVAL_REQUIRED",
                    "Confirmed selection, survey, preview and approval are required",
                    "Complete the site selection and demolition approval flow");
        }
        if (state.salvage == null) return refuse(source, "SALVAGE_DESTINATION_UNAVAILABLE",
                "An exact salvage destination is required",
                "Look at a dedicated container and run site salvage set");
        if (state.approval.state() != DemolitionApprovalState.ACTIVE) {
            return refuse(source, "DEMOLITION_APPROVAL_STALE",
                    "Approval token is not active", "Run a fresh survey, preview and approval");
        }
        List<ObstacleObservation> current = new ArrayList<>();
        try {
            for (ApprovedObstacle approved : state.approval.approvedObstacles()) {
                current.add(ForgeSiteSurveyAdapter.observe(player.serverLevel(),
                        block(approved.position())));
            }
        } catch (RuntimeException failure) {
            return refuse(source, "SITE_SURVEY_STALE", failure.getMessage(),
                    "Load the exact region and run a fresh survey");
        }
        var check = APPROVALS.check(state.approval, context.identity().value(),
                dimension(player.serverLevel()), state.planHash,
                state.survey.siteSnapshotHash(), playerIdentity(player), current,
                state.approval.approvedObstacles().size(), Instant.now());
        if (!check.accepted()) {
            return refuse(source, "DEMOLITION_APPROVAL_STALE",
                    "Exact approval validation failed: " + check.failures(),
                    "Inspect changes and run a fresh survey, preview and approval");
        }
        if (!salvageCurrent(player.serverLevel(), state.salvage)) {
            return refuse(source, "SALVAGE_DESTINATION_UNAVAILABLE",
                    "Salvage destination changed, unloaded, or is no longer a container",
                    "Select the destination again");
        }
        state.pendingMode = mode;
        SURVEYS.put(player.getUUID(), new PendingSurvey(context.identity().value(),
                dimension(player.serverLevel()), state.confirmed, state.planHash,
                SurveyPurpose.PRE_CLEAR_VALIDATION));
        return success(source, "Pre-clearance exact snapshot validation started mode=" + mode
                + " token=" + state.approval.tokenIdentity() + " cellsPerTick="
                + CELLS_PER_TICK + " worldMutation=false");
    }

    private static void startValidated(
            ServerPlayer player,
            PlayerSiteState state,
            ClearingMode mode) {
        if (mode == null || state.approval == null || state.salvage == null
                || !salvageCurrent(player.serverLevel(), state.salvage)) {
            refuse(player.createCommandSourceStack(), "DEMOLITION_APPROVAL_STALE",
                    "Validated start context is incomplete or stale",
                    "Run a fresh survey, preview and approval");
            return;
        }
        TerrainPreparationPlan plan = terrainPlan(state);
        DemolitionApprovalToken active = state.approval;
        state.approval = APPROVALS.consume(state.approval);
        ClearingSession session = new ClearingSession(player.getUUID(),
                active.worldIdentity(), active.dimension(), state.confirmed,
                active, plan, state.salvage, mode, player.serverLevel(), player.blockPosition());
        CLEARING.put(player.getUUID(), session);
        state.prepared = null;
        success(player.createCommandSourceStack(), "Bot clearing started mode=" + mode
                + " token=" + active.tokenIdentity() + " graph="
                + plan.taskGraph().graphIdentity() + " exactMutations="
                + active.approvedObstacles().size()
                + " fullSnapshotRevalidated=true oneActionPerTick=true teleportFallback=false");
    }

    private static TerrainPreparationPlan terrainPlan(PlayerSiteState state) {
        List<BlockPos3i> positions = state.approval.approvedObstacles().stream()
                .map(ApprovedObstacle::position).sorted(Comparator
                        .comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z)).toList();
        TerrainGradingSpecification grading = state.gradingSpecification;
        List<BlockPos3i> leasedPositions = new ArrayList<>(positions);
        if (grading != null) leasedPositions.addAll(grading.placementPositions());
        leasedPositions = leasedPositions.stream().distinct().sorted(Comparator
                .comparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::x)
                .thenComparingInt(BlockPos3i::z)).toList();
        TerrainPreparationTask reserve = new TerrainPreparationTask(
                "site-task:reserve", TerrainPreparationTaskKind.RESERVE_CLEARANCE_POSITIONS,
                leasedPositions, Set.of(), 0, 200);
        int shardCount = Math.min(5, positions.size());
        List<List<BlockPos3i>> shards = new ArrayList<>();
        for (int shard = 0; shard < shardCount; shard++) shards.add(new ArrayList<>());
        for (int index = 0; index < positions.size(); index++) {
            shards.get(index % shardCount).add(positions.get(index));
        }
        List<TerrainPreparationTask> tasks = new ArrayList<>();
        tasks.add(reserve);
        Set<String> deliveryIds = new java.util.LinkedHashSet<>();
        for (int shard = 0; shard < shards.size(); shard++) {
            List<BlockPos3i> shardPositions = List.copyOf(shards.get(shard));
            TerrainPreparationTask mine = new TerrainPreparationTask(
                    "site-task:mine-" + shard, TerrainPreparationTaskKind.MINE_AUTHORIZED_BLOCK,
                    shardPositions, Set.of(reserve.taskIdentity()), shardPositions.size(), 72_000);
            TerrainPreparationTask collect = new TerrainPreparationTask(
                    "site-task:collect-" + shard, TerrainPreparationTaskKind.COLLECT_DROPS,
                    shardPositions, Set.of(mine.taskIdentity()), 0, 20_000);
            TerrainPreparationTask deliver = new TerrainPreparationTask(
                    "site-task:deliver-" + shard, TerrainPreparationTaskKind.DELIVER_SALVAGE,
                    List.of(position(state.salvage.position)), Set.of(collect.taskIdentity()),
                    0, 20_000);
            tasks.add(mine);
            tasks.add(collect);
            tasks.add(deliver);
            deliveryIds.add(deliver.taskIdentity());
        }
        Set<String> fillPredecessors = deliveryIds.isEmpty()
                ? Set.of(reserve.taskIdentity()) : Set.copyOf(deliveryIds);
        Set<String> finalFillIds = new java.util.LinkedHashSet<>(fillPredecessors);
        if (grading != null) {
            List<BlockPos3i> holes = grading.placementPositions().stream()
                    .filter(value -> value.y() < grading.surfaceY()).toList();
            List<BlockPos3i> surface = grading.placementPositions().stream()
                    .filter(value -> value.y() == grading.surfaceY()).toList();
            Set<String> holeIds = addFillTasks(tasks, holes,
                    TerrainPreparationTaskKind.FILL_MINOR_HOLE, "fill-hole",
                    fillPredecessors);
            Set<String> surfacePredecessors = holeIds.isEmpty()
                    ? fillPredecessors : holeIds;
            Set<String> surfaceIds = addFillTasks(tasks, surface,
                    TerrainPreparationTaskKind.LEVEL_SURFACE, "level-surface",
                    surfacePredecessors);
            finalFillIds = surfaceIds.isEmpty()
                    ? surfacePredecessors : surfaceIds;
        }
        List<BlockPos3i> verificationPositions = grading == null ? positions
                : java.util.stream.Stream.concat(grading.removalPositions().stream(),
                        grading.expectedFillPositions().stream()).distinct().toList();
        TerrainPreparationTask verify = new TerrainPreparationTask(
                "site-task:verify", TerrainPreparationTaskKind.VERIFY_GROUND,
                verificationPositions, finalFillIds, 0, 10_000);
        TerrainPreparationTask release = new TerrainPreparationTask(
                "site-task:release", TerrainPreparationTaskKind.RELEASE_CLEARANCE_POSITIONS,
                leasedPositions, Set.of(verify.taskIdentity()), 0, 100);
        tasks.add(verify);
        tasks.add(release);
        String graphId = "site-graph:" + sha256(state.approval.tokenIdentity() + "\n"
                + state.planHash + "\n" + positions + "\n"
                + (grading == null ? "no-grading" : grading.specificationHash()));
        TerrainPreparationTaskGraph graph = new TerrainPreparationTaskGraph(graphId,
                state.planHash, state.survey.siteSnapshotHash(),
                state.approval.tokenIdentity(),
                tasks, grading == null ? positions.size() : grading.mutationCount());
        GroundLevelingPolicy leveling = grading == null
                ? GroundLevelingPolicy.conservative()
                : GroundLevelingPolicy.boundedGrading(grading.clearanceHeight(),
                        grading.maximumFillDepth(), grading.placementPositions().size(),
                        grading.mutationCount());
        List<String> constraints = new ArrayList<>(List.of(
                "exact-token-positions-only", "unknown-ordinary-requires-final-group-approval",
                "container-contents-never-read", "no-player-inventory", "no-teleport-fallback",
                "post-clearance-rescan-required"));
        constraints.add(state.forcedRemovalAuthorization == null
                ? "high-risk:separate-confirmation-required"
                : "exact-warning-hash-forced-removal-only");
        return new TerrainPreparationPlan("site-plan:" + sha256(graphId + "\n"
                + state.salvage.identity), state.approval, graph, leveling, state.salvage.identity,
                constraints, Optional.ofNullable(grading),
                Optional.ofNullable(state.forcedRemovalAuthorization));
    }

    private static Set<String> addFillTasks(
            List<TerrainPreparationTask> tasks,
            List<BlockPos3i> positions,
            TerrainPreparationTaskKind kind,
            String identityPrefix,
            Set<String> predecessors) {
        int shardCount = Math.min(5, positions.size());
        if (shardCount == 0) return Set.of();
        List<List<BlockPos3i>> shards = new ArrayList<>();
        for (int shard = 0; shard < shardCount; shard++) shards.add(new ArrayList<>());
        for (int index = 0; index < positions.size(); index++) {
            shards.get(index % shardCount).add(positions.get(index));
        }
        Set<String> identities = new java.util.LinkedHashSet<>();
        for (int shard = 0; shard < shards.size(); shard++) {
            List<BlockPos3i> exact = shards.get(shard).stream()
                    .sorted(Comparator.comparingInt(BlockPos3i::y)
                            .thenComparingInt(BlockPos3i::x)
                            .thenComparingInt(BlockPos3i::z)).toList();
            TerrainPreparationTask task = new TerrainPreparationTask(
                    "site-task:" + identityPrefix + "-" + shard, kind, exact,
                    predecessors, exact.size(), 72_000);
            tasks.add(task);
            identities.add(task.taskIdentity());
        }
        return Set.copyOf(identities);
    }

    private static int cancel(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        ClearingSession session = CLEARING.remove(player.getUUID());
        if (session == null) return refuse(source, "DEMOLITION_APPROVAL_REQUIRED",
                "No clearing session is active", "Review site clearing status");
        session.cancel("cancelled by owner");
        return success(source, "Bot clearing cancelled session=" + session.sessionIdentity
                + " actualMutations=" + session.mutations.size()
                + " noFurtherMutation=true");
    }

    private static int clearingStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        ClearingSession session = CLEARING.get(player.getUUID());
        if (session == null) {
            PlayerSiteState state = state(player);
            return success(source, "Bot clearing active=false prepared="
                    + (state.prepared != null) + " safety=" + state.latestSafetyLine);
        }
        return success(source, "Bot clearing active=true session=" + session.sessionIdentity
                + " mode=" + session.mode + " phase=" + session.phase
                + " completedTargets=" + session.index + "/"
                + session.approval.approvedObstacles().size() + " actualMutations="
                + session.mutations.size() + " collected=" + session.collectedCount()
                + " botOverlap=false teleportFallback=false");
    }

    private static int preparedStatus(CommandSourceStack source) {
        ServerPlayer player = player(source).orElse(null);
        if (player == null) return 0;
        PlayerSiteState state = state(player);
        if (state.prepared == null) return refuse(source, "POST_CLEARANCE_RESCAN_REQUIRED",
                "No current PreparedConstructionSite exists",
                "Complete clearing and the automatic post-clearance rescan");
        PreparedConstructionSite prepared = state.prepared;
        return success(source, "PreparedConstructionSite identity="
                + prepared.preparedSiteIdentity() + " world=" + prepared.worldIdentity()
                + " dimension=" + prepared.dimension() + " anchor="
                + prepared.anchor().position() + " facing=" + prepared.facing()
                + " planHash=" + prepared.planHash() + " cleanSnapshotHash="
                + prepared.cleanSiteSnapshotHash() + " salvageCollected="
                + prepared.salvageLedger().collectedCount() + " salvageDelivered="
                + prepared.salvageLedger().deliveredCount() + " expires="
                + prepared.expiresAt() + " safety=" + state.latestSafetyLine);
    }

    private static void beginPostClearanceSurvey(
            ServerPlayer player,
            ClearingSession session) {
        PlayerSiteState state = state(player);
        state.lastCompleted = session;
        PendingSurvey pending = new PendingSurvey(session.worldIdentity, session.dimension,
                session.selection, session.plan.taskGraph().planHash(),
                SurveyPurpose.POST_CLEARANCE,
                session.plan.gradingSpecification()
                        .map(grading -> new DeploymentBoundingBox(
                                new BlockPos3i(grading.minimumX(),
                                        grading.surfaceY() - grading.maximumFillDepth(),
                                        grading.minimumZ()),
                                new BlockPos3i(grading.maximumX(),
                                        grading.surfaceY() + grading.clearanceHeight(),
                                        grading.maximumZ())))
                        .orElse(session.selection.authorizedBounds()));
        SURVEYS.put(player.getUUID(), pending);
        success(player.createCommandSourceStack(), "Bot clearing mutations complete; mandatory"
                + " post-clearance rescan started actualMutations=" + session.mutations.size()
                + " collected=" + session.collectedCount() + " delivered="
                + session.deliveredCount() + " teleportFallback=false");
    }

    private static void finishPostClearance(
            ServerPlayer player,
            PlayerSiteState state,
            SiteSurveySnapshot clean) {
        ClearingSession completed = state.lastCompleted;
        if (completed == null) {
            refuse(player.createCommandSourceStack(), "POST_CLEARANCE_RESCAN_REQUIRED",
                    "No completed clearing session owns this rescan",
                    "Start a fresh approved clearing session");
            return;
        }
        Set<BlockPos3i> approved = completed.approval.approvedObstacles().stream()
                .map(ApprovedObstacle::position).collect(java.util.stream.Collectors.toSet());
        Set<BlockPos3i> expectedFill = completed.plan.gradingSpecification()
                .map(value -> Set.copyOf(value.expectedFillPositions())).orElse(Set.of());
        boolean targetStillBlocked = clean.findings().stream()
                .anyMatch(value -> approved.contains(value.position())
                        && !expectedFill.contains(value.position()));
        if (targetStillBlocked) {
            refuse(player.createCommandSourceStack(), "POST_CLEARANCE_SITE_CHANGED",
                    "An approved target is still occupied after clearing",
                    "Inspect the obstruction and run a fresh survey");
            return;
        }
        try {
            state.prepared = new PostClearanceRescan().prepare(state.confirmed, state.survey,
                    clean, completed.plan, completed.ledger(), completed.mutations,
                    Instant.now(), Instant.now().plusSeconds(300));
        } catch (IllegalArgumentException failure) {
            // The code carries its own detail after a colon. Reporting the whole string
            // as the code made the block that is actually in the way unreadable.
            String raw = String.valueOf(failure.getMessage());
            int separator = raw.indexOf(':');
            String code = separator < 0 ? raw : raw.substring(0, separator);
            String detail = separator < 0
                    ? "Post-clearance identity or footprint verification failed"
                    : "Still in the way: " + raw.substring(separator + 1);
            refuse(player.createCommandSourceStack(), code, detail,
                    "Clear that block, then run a fresh survey");
            return;
        }
        state.latestSafetyLine = "forcedHighRisk="
                + completed.plan.forcedRemovalAuthorization().isPresent()
                + " protectedBlocksRemoved=" + completed.protectedBlocksRemoved
                + " containersDestroyed=" + completed.containersDestroyed
                + " blockEntitiesDestroyed=" + completed.blockEntitiesDestroyed
                + " dangerousMediaRemoved=" + completed.dangerousMediaRemoved
                + " unbreakableBlocksRemoved=" + completed.unbreakableBlocksRemoved
                + " privateContainerContentsRead=0 playerInventoryAccess=0"
                + " unknownNbtDestroyed=" + completed.blockEntitiesDestroyed
                + " regionOutsideMutations=0"
                + " formalWorldAccess=0 botOverlap=false teleportFallback=false";
        persistPlayerWorkflowState(player, state);
        success(player.createCommandSourceStack(), "Site preparation complete preparedSite="
                + state.prepared.preparedSiteIdentity() + " cleanSnapshotHash="
                + state.prepared.cleanSiteSnapshotHash() + " salvageCollected="
                + state.prepared.salvageLedger().collectedCount() + " salvageDelivered="
                + state.prepared.salvageLedger().deliveredCount() + " "
                + state.latestSafetyLine);
        state.lastCompleted = null;
    }

    private static Optional<PilotRegionCommand.CurrentPilotContext> current(
            CommandSourceStack source) {
        return PilotRegionCommand.currentContext(source);
    }

    private static Optional<ServerPlayer> player(CommandSourceStack source) {
        try {
            return Optional.of(source.getPlayerOrException());
        } catch (Exception noPlayer) {
            refuse(source, "SITE_SELECTION_STALE", "Site preparation requires a player",
                    "Run the command as the owning player");
            return Optional.empty();
        }
    }

    private static PlayerSiteState state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerSiteState(
                "site-session:" + player.getUUID() + ":" + UUID.randomUUID()));
    }

    /**
     * Whether persisted site evidence may be restored for what the player is doing now.
     *
     * <p>Kept separate from the world lookup because this is the part that can be wrong in a
     * way nobody sees: evidence outliving the project that owned it, or a record from another
     * dimension answering for the one the player is standing in. A finished project's record
     * is released, but a crash between the two writes must not resurrect it either.</p>
     */
    static boolean restorable(
            PlayerSitePreparationSavedData.Entry entry,
            UUID currentProjectId,
            String currentDimension) {
        return entry != null && currentProjectId != null && currentDimension != null
                && entry.projectId().equals(currentProjectId)
                && currentDimension.equals(entry.selection().anchor().dimension().toString())
                && currentDimension.equals(entry.salvage().dimension().toString());
    }

    /**
     * The live site state for this player, restored from disk when the server has restarted.
     *
     * <p>The project itself is persistent, so a state that only ever lived in a static map
     * left a restarted project asking for a prepared site that could not exist any more. The
     * restored record is the same evidence that was written, not a renewal of it: its own
     * expiry, identity and salvage fingerprint are still checked by the callers and by
     * {@code PreparedSiteExecutionGate}, and a record whose project is no longer the player's
     * current one is never restored at all.</p>
     */
    private static PlayerSiteState restoredPlayerWorkflowState(ServerPlayer player) {
        PlayerSiteState live = STATES.get(player.getUUID());
        if (live != null) return live;
        PlayerSitePreparationSavedData.Entry entry = PlayerSitePreparationSavedData
                .forLevel(player.serverLevel()).entry(player.getUUID()).orElse(null);
        if (entry == null) return null;
        UUID currentProject = PlayerWorkflowSavedData.forLevel(player.serverLevel())
                .entry(player.getUUID())
                .map(PlayerWorkflowSavedData.ProjectEntry::projectId).orElse(null);
        if (!restorable(entry, currentProject,
                player.serverLevel().dimension().location().toString())) {
            return null;
        }
        PlayerSiteState state = new PlayerSiteState(entry.sessionIdentity());
        state.workflowProjectId = entry.projectId();
        state.anchor = entry.selection().anchor();
        state.facing = entry.selection().facing();
        state.confirmed = entry.selection();
        state.prepared = entry.prepared();
        state.planHash = entry.prepared() == null ? null : entry.prepared().planHash();
        state.salvage = new SalvageDestination(entry.salvage().worldIdentity(),
                entry.salvage().dimension(), block(entry.salvage().position()),
                entry.salvage().stateFingerprint(), entry.salvage().playerIdentity(),
                entry.salvage().identity());
        STATES.put(player.getUUID(), state);
        return state;
    }

    /** Writes the project-owned part of a site state through to disk. */
    private static void persistPlayerWorkflowState(ServerPlayer player, PlayerSiteState state) {
        if (state.workflowProjectId == null || state.confirmed == null || state.salvage == null) {
            return;
        }
        PlayerSitePreparationSavedData.forLevel(player.serverLevel()).put(
                new PlayerSitePreparationSavedData.Entry(player.getUUID(),
                        state.workflowProjectId, state.sessionIdentity, state.confirmed,
                        new PlayerSitePreparationSavedData.SalvageBinding(
                                state.salvage.worldIdentity(), state.salvage.dimension(),
                                new BlockPos3i(state.salvage.position().getX(),
                                        state.salvage.position().getY(),
                                        state.salvage.position().getZ()),
                                state.salvage.stateFingerprint(),
                                state.salvage.playerIdentity(), state.salvage.identity()),
                        state.prepared, Instant.now().toEpochMilli()));
    }

    private static void releasePlayerWorkflowState(ServerPlayer player) {
        PlayerSiteState state = STATES.get(player.getUUID());
        if (state != null && state.workflowProjectId != null) STATES.remove(player.getUUID());
        PlayerSitePreparationSavedData.forLevel(player.serverLevel()).remove(player.getUUID());
    }

    /**
     * Releases the durable site evidence a finished project owns.
     *
     * <p>Called where a project reaches a terminal stage. Keeping the record would let the
     * next project restore someone else's prepared site.</p>
     */
    public static void releasePlayerWorkflow(ServerPlayer player, UUID projectId) {
        if (player == null || projectId == null) return;
        PlayerSiteState state = STATES.get(player.getUUID());
        if (state != null && projectId.equals(state.workflowProjectId)) {
            STATES.remove(player.getUUID());
        }
        PlayerSitePreparationSavedData.forLevel(player.serverLevel()).removeProject(projectId);
    }

    private static boolean busy(UUID player, CommandSourceStack source) {
        if (SURVEYS.containsKey(player) || CLEARING.containsKey(player)) {
            refuse(source, "SITE_SELECTION_STALE",
                    "A site survey or clearing session is active",
                    "Wait for it to complete or cancel clearing");
            return true;
        }
        return false;
    }

    private static void invalidateAfterSelection(PlayerSiteState state) {
        state.confirmed = null;
        invalidateAfterConfirmation(state);
    }

    private static void invalidateConfirmation(PlayerSiteState state) {
        state.confirmed = null;
        invalidateAfterConfirmation(state);
    }

    private static void invalidateAfterConfirmation(PlayerSiteState state) {
        state.survey = null;
        state.preview = null;
        state.approval = null;
        state.prepared = null;
        state.planHash = null;
        state.lastCompleted = null;
    }

    private static void invalidateGradingExecution(PlayerSiteState state) {
        state.anchor = null;
        state.region = null;
        state.facing = null;
        state.confirmed = null;
        state.survey = null;
        state.preview = null;
        state.approval = null;
        state.prepared = null;
        state.planHash = null;
        state.lastCompleted = null;
        state.gradingScanBounds = null;
        state.gradingFillBlockId = null;
        state.gradingFillStateFingerprint = null;
        state.gradingSpecification = null;
        state.pendingForcedAuthorization = null;
        state.forcedRemovalAuthorization = null;
    }

    private static boolean salvageCurrent(ServerLevel level, SalvageDestination destination) {
        return level.dimension().location().toString().equals(destination.dimension.toString())
                && level.hasChunkAt(destination.position)
                && destination.stateFingerprint.equals(ForgeSiteSurveyAdapter.fingerprint(
                        level.getBlockState(destination.position)))
                && level.getBlockEntity(destination.position) instanceof Container;
    }

    private static int gradingMaterialCount(
            ServerLevel level,
            SalvageDestination destination,
            ResourceId blockId) {
        if (!salvageCurrent(level, destination)
                || !(level.getBlockEntity(destination.position) instanceof Container container)) {
            return 0;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(
                ResourceLocation.tryParse(blockId.toString()));
        if (block == null || !(block.asItem() instanceof BlockItem)) return 0;
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(block.asItem()) && !stack.hasTag()) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static Map<ObstacleClassification, Long> counts(SiteSurveySnapshot survey) {
        return survey.findings().stream().collect(java.util.stream.Collectors.groupingBy(
                ObstacleFinding::classification, java.util.TreeMap::new,
                java.util.stream.Collectors.counting()));
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        LOGGER.info("SITE_PREP {}", message);
        return 1;
    }

    private static int refuse(
            CommandSourceStack source,
            String code,
            String reason,
            String next) {
        String line = "Site preparation refused code=" + code + " reason=" + reason
                + " safeNextStep=" + next + " worldMutation=false";
        source.sendFailure(Component.literal(line));
        LOGGER.info("SITE_PREP_REFUSAL {}", line);
        return 0;
    }

    private static ResourceId dimension(ServerLevel level) {
        return ResourceId.parse(level.dimension().location().toString());
    }

    private static String playerIdentity(ServerPlayer player) {
        return "player:" + player.getUUID();
    }

    private static BlockPos3i position(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static BlockPos3i position(BlockPos3i position) {
        return position;
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static String format(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static String format(DeploymentBoundingBox bounds) {
        return bounds.minimum().x() + "," + bounds.minimum().y() + ","
                + bounds.minimum().z() + ".." + bounds.maximum().x() + ","
                + bounds.maximum().y() + "," + bounds.maximum().z();
    }

    private static void showBounds(ServerLevel level, DeploymentBoundingBox bounds) {
        DustParticleOptions blue = new DustParticleOptions(new Vector3f(0.1F, 0.4F, 1.0F), 1.0F);
        int stepX = Math.max(1, (bounds.maximum().x() - bounds.minimum().x()) / 16);
        int stepY = Math.max(1, (bounds.maximum().y() - bounds.minimum().y()) / 8);
        int stepZ = Math.max(1, (bounds.maximum().z() - bounds.minimum().z()) / 16);
        for (int x = bounds.minimum().x(); x <= bounds.maximum().x(); x += stepX) {
            particle(level, blue, x, bounds.minimum().y(), bounds.minimum().z());
            particle(level, blue, x, bounds.minimum().y(), bounds.maximum().z());
            particle(level, blue, x, bounds.maximum().y(), bounds.minimum().z());
            particle(level, blue, x, bounds.maximum().y(), bounds.maximum().z());
        }
        for (int z = bounds.minimum().z(); z <= bounds.maximum().z(); z += stepZ) {
            particle(level, blue, bounds.minimum().x(), bounds.minimum().y(), z);
            particle(level, blue, bounds.maximum().x(), bounds.minimum().y(), z);
            particle(level, blue, bounds.minimum().x(), bounds.maximum().y(), z);
            particle(level, blue, bounds.maximum().x(), bounds.maximum().y(), z);
        }
        for (int y = bounds.minimum().y(); y <= bounds.maximum().y(); y += stepY) {
            particle(level, blue, bounds.minimum().x(), y, bounds.minimum().z());
            particle(level, blue, bounds.maximum().x(), y, bounds.maximum().z());
        }
    }

    private static void particle(
            ServerLevel level,
            DustParticleOptions particle,
            int x,
            int y,
            int z) {
        level.sendParticles(particle, x + 0.5, y + 0.5, z + 0.5,
                1, 0.0, 0.0, 0.0, 0.0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class PendingSurvey {
        private final String worldIdentity;
        private final ResourceId dimension;
        private final ConfirmedSiteSelection selection;
        private final DeploymentBoundingBox scanBounds;
        private final String planHash;
        private final SurveyPurpose purpose;
        private final List<ObstacleObservation> observations = new ArrayList<>();
        private final Map<BlockPos3i, String> stateFingerprints = new LinkedHashMap<>();
        private final Set<BlockPos3i> airPositions = new java.util.LinkedHashSet<>();
        private int x;
        private int y;
        private int z;

        private PendingSurvey(
                String worldIdentity,
                ResourceId dimension,
                ConfirmedSiteSelection selection,
                String planHash,
                SurveyPurpose purpose) {
            this(worldIdentity, dimension, selection, planHash, purpose,
                    selection.authorizedBounds());
        }

        private PendingSurvey(
                String worldIdentity,
                ResourceId dimension,
                ConfirmedSiteSelection selection,
                String planHash,
                SurveyPurpose purpose,
                DeploymentBoundingBox scanBounds) {
            this.worldIdentity = worldIdentity;
            this.dimension = dimension;
            this.selection = selection;
            this.scanBounds = scanBounds;
            this.planHash = planHash;
            this.purpose = purpose;
            if (!selection.authorizedBounds().contains(scanBounds.minimum())
                    || !selection.authorizedBounds().contains(scanBounds.maximum())) {
                throw new IllegalArgumentException("survey bounds exceed site authority");
            }
            this.x = scanBounds.minimum().x();
            this.y = scanBounds.minimum().y();
            this.z = scanBounds.minimum().z();
        }

        private boolean advance(ServerLevel level, int limit) {
            if (!dimension.equals(SitePreparationCommand.dimension(level))) {
                throw new IllegalStateException("SITE_DIMENSION_MISMATCH");
            }
            DeploymentBoundingBox bounds = scanBounds;
            int processed = 0;
            while (processed < limit && y <= bounds.maximum().y()) {
                BlockPos position = new BlockPos(x, y, z);
                if (!level.hasChunkAt(position)) {
                    throw new IllegalStateException("unloaded chunk at " + format(position));
                }
                BlockState state = level.getBlockState(position);
                BlockPos3i exactPosition = SitePreparationCommand.position(position);
                stateFingerprints.put(exactPosition, ForgeSiteSurveyAdapter.fingerprint(state));
                if (!state.isAir()) {
                    observations.add(ForgeSiteSurveyAdapter.observe(level, position));
                    if (observations.size() > SiteSurveySnapshot.MAX_FINDINGS) {
                        throw new IllegalStateException("site findings exceed "
                                + SiteSurveySnapshot.MAX_FINDINGS);
                    }
                } else airPositions.add(exactPosition);
                processed++;
                x++;
                if (x > bounds.maximum().x()) {
                    x = bounds.minimum().x();
                    z++;
                    if (z > bounds.maximum().z()) {
                        z = bounds.minimum().z();
                        y++;
                    }
                }
            }
            return y > bounds.maximum().y();
        }
    }

    private static final class ClearingSession implements BotClearingExecutor {
        private final String sessionIdentity = "site-clearing:" + UUID.randomUUID();
        private final UUID owner;
        private final String worldIdentity;
        private final ResourceId dimension;
        private final ConfirmedSiteSelection selection;
        private final DemolitionApprovalToken approval;
        private final TerrainPreparationPlan plan;
        private final SalvageDestination destination;
        private final ClearingMode mode;
        private final List<ApprovedObstacle> targets;
        private final Map<String, List<ItemStack>> collected = new LinkedHashMap<>();
        private final List<TerrainMutationEvidence> mutations = new ArrayList<>();
        private final List<ConstructionBotEntity> bots = new ArrayList<>();
        private final Set<UUID> workersUsed = new java.util.LinkedHashSet<>();
        private final Set<ResourceId> fleetAssignmentsSeen =
                new java.util.LinkedHashSet<>();
        private final Map<ResourceId, SitePreparationFleetWorker> fleetWorkers =
                new LinkedHashMap<>();
        private final Map<ResourceId, Assignment> liveAssignments = new LinkedHashMap<>();
        private final Map<ResourceId, Integer> taskIndexes = new LinkedHashMap<>();
        private final Map<ResourceId, Long> taskNextTicks = new LinkedHashMap<>();
        private final Map<UUID, BlockPos> movementTargets = new LinkedHashMap<>();
        private final Map<String, List<UUID>> pendingDropIds = new LinkedHashMap<>();
        private final Map<ResourceId, Map<String, List<ItemStack>>> carried =
                new LinkedHashMap<>();
        private final Set<String> deliveredObstacles = new java.util.LinkedHashSet<>();
        private final TerrainPreparationFleetTaskAdapter fleetAdapter =
                new TerrainPreparationFleetTaskAdapter();
        private GraphNeutralFleetCoordinator<TerrainPreparationTaskGraph,
                TerrainPreparationTask> fleet;
        private ConstructionBotEntity activeBot;
        private ClearingPhase phase = ClearingPhase.CLEAR;
        private int index;
        private int fillBlocksConsumed;
        private int protectedBlocksRemoved;
        private int containersDestroyed;
        private int blockEntitiesDestroyed;
        private int dangerousMediaRemoved;
        private int unbreakableBlocksRemoved;
        private long nextActionTick;
        private boolean paused;
        private String pauseReason = "";

        private ClearingSession(
                UUID owner,
                String worldIdentity,
                ResourceId dimension,
                ConfirmedSiteSelection selection,
                DemolitionApprovalToken approval,
                TerrainPreparationPlan plan,
                SalvageDestination destination,
                ClearingMode mode,
                ServerLevel level,
                BlockPos start) {
            this.owner = owner;
            this.worldIdentity = worldIdentity;
            this.dimension = dimension;
            this.selection = selection;
            this.approval = approval;
            this.plan = plan;
            this.destination = destination;
            this.mode = mode;
            this.targets = approval.approvedObstacles().stream()
                    .sorted(Comparator.<ApprovedObstacle>comparingInt(value -> toolPriority(
                            ForgeSiteSurveyAdapter.observe(level, block(value.position()))
                                    .toolRequirement()))
                            .thenComparing(ApprovedObstacle::obstacleId))
                    .toList();
            if (mode != ClearingMode.DIRECT) {
                int workerCount = mode == ClearingMode.BOTS
                        ? Math.max(2, Math.min(5, targets.size())) : 1;
                BlockPos spawn = start;
                for (int number = 1; number <= workerCount; number++) {
                    if (number > 1) spawn = spawnCell(level, spawn);
                    ConstructionBotEntity entity = spawnBot(level, spawn, number);
                    bots.add(entity);
                    ResourceId workerId = ResourceId.parse(
                            "site-prep:worker/" + number + "/" + entity.getUUID());
                    fleetWorkers.put(workerId,
                            new SitePreparationFleetWorker(workerId, entity));
                }
                activeBot = bots.get(0);
                if (mode == ClearingMode.BOTS) {
                    fleet = new GraphNeutralFleetCoordinator<>(
                            ResourceId.parse(sessionIdentity), plan.taskGraph(), fleetAdapter,
                            new TerrainPreparationFleetDispatcher(plan, this, fleetAdapter),
                            TerrainPreparationFleetDispatcher.DISPATCHER_ID,
                            List.copyOf(fleetWorkers.values()),
                            fleetAdapter.workPositions(plan.taskGraph()),
                            AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                            new RetryBudget(2, 1), new WorkerHealthPolicy(1, true), 72_000);
                }
            }
        }

        private boolean tick(ServerLevel level, ServerPlayer player) {
            long tick = level.getGameTime();
            if (paused) return false;
            if (!player.getUUID().equals(owner)
                    || !dimension.equals(SitePreparationCommand.dimension(level))
                    || !selection.authorizedBounds().contains(position(destination.position))
                    || !salvageCurrent(level, destination)) {
                throw new IllegalStateException("site/session/destination identity changed");
            }
            if (mode == ClearingMode.BOTS) {
                requireCapacityForCarriedSalvage(level);
                reconcileFleetFailures(level, tick);
                GraphNeutralFleetCoordinator.TickResult update = fleet.tick(tick);
                if (!update.terminalUpdates().isEmpty()) {
                    Update failure = update.terminalUpdates().values().iterator().next();
                    throw new IllegalStateException(failure.failureCode().orElseThrow()
                            + ": " + failure.detail());
                }
                if (update.completedUpdates().size() == plan.taskGraph().tasks().size()) {
                    phase = ClearingPhase.COMPLETED;
                    discardBots();
                    return true;
                }
                return false;
            }
            if (tick < nextActionTick) return false;
            if (phase == ClearingPhase.CLEAR) {
                if (index >= targets.size()) {
                    phase = ClearingPhase.DELIVER;
                    nextActionTick = tick + 1;
                    return false;
                }
                ApprovedObstacle target = targets.get(index);
                boolean botTask = mode == ClearingMode.BOTS
                        || (mode == ClearingMode.HYBRID
                        && target.classification()
                                == ObstacleClassification.SAFE_NATURAL_CLEARABLE);
                if (botTask) activeBot = bots.get(0);
                if (botTask && !atWorkPosition(level, target.position())) {
                    moveOneAdjacentStep(level, workPosition(level, target.position()));
                    nextActionTick = tick + 2;
                    return false;
                }
                clearOne(level, target, botTask, false);
                index++;
                nextActionTick = tick + 4;
                return false;
            }
            if (phase == ClearingPhase.DELIVER) {
                if (!bots.isEmpty()) activeBot = bots.get(0);
                if (activeBot != null
                        && !adjacent(activeBot.blockPosition(), destination.position)) {
                    moveOneAdjacentStep(level, workPosition(level, position(destination.position)));
                    nextActionTick = tick + 2;
                    return false;
                }
                deliver(level);
                phase = ClearingPhase.COMPLETED;
                discardBots();
                return true;
            }
            return phase == ClearingPhase.COMPLETED;
        }

        private void requireCapacityForCarriedSalvage(ServerLevel level) {
            List<ItemStack> pending = carried.values().stream()
                    .flatMap(manifest -> manifest.values().stream())
                    .flatMap(List::stream).map(ItemStack::copy).toList();
            if (pending.isEmpty()) return;
            if (!(level.getBlockEntity(destination.position) instanceof Container container)
                    || !canInsertAll(container, pending)) {
                throw new IllegalStateException(
                        "SALVAGE_DESTINATION_UNAVAILABLE: destination lacks exact capacity");
            }
        }

        @Override
        public BotClearingUpdate execute(
                TerrainPreparationPlan suppliedPlan,
                TerrainPreparationTask task,
                Assignment assignment,
                ExecutionContext context) {
            if (!plan.planIdentity().equals(suppliedPlan.planIdentity())
                    || !fleetWorkers.containsKey(assignment.workerId())) {
                throw new IllegalArgumentException(
                        "Terrain dispatcher plan/worker identity changed");
            }
            ResourceId taskId = fleetAdapter.taskId(task);
            SitePreparationFleetWorker worker = fleetWorkers.get(assignment.workerId());
            activeBot = worker.entity;
            if (context.command() == GraphNeutralFleetCoordinator.Command.CANCEL) {
                liveAssignments.remove(assignment.workerId());
                taskIndexes.remove(taskId);
                taskNextTicks.remove(taskId);
                return botUpdate(task, BotClearingState.CANCELLED, Optional.empty(),
                        "Terrain task cancelled by exact fleet assignment");
            }
            fleetAssignmentsSeen.add(assignment.workerId());
            liveAssignments.put(assignment.workerId(), assignment);
            try {
                boolean complete = executeTask(task, assignment, context.currentTick());
                if (complete) liveAssignments.remove(assignment.workerId());
                return botUpdate(task,
                        complete ? BotClearingState.COMPLETED : BotClearingState.RUNNING,
                        Optional.empty(), complete
                                ? "Terrain task completed with authoritative readback"
                                : "Terrain task remains inside its bounded action sequence");
            } catch (RuntimeException failure) {
                liveAssignments.remove(assignment.workerId());
                return botUpdate(task, BotClearingState.FAILED,
                        Optional.of(failureCode(failure)), failure.getMessage());
            }
        }

        private boolean executeTask(
                TerrainPreparationTask task,
                Assignment assignment,
                long tick) {
            ResourceId taskId = fleetAdapter.taskId(task);
            if (tick < taskNextTicks.getOrDefault(taskId, 0L)) return false;
            return switch (task.kind()) {
                case RESERVE_CLEARANCE_POSITIONS, RELEASE_CLEARANCE_POSITIONS -> true;
                case REMOVE_SAFE_FOLIAGE, MINE_AUTHORIZED_BLOCK ->
                        executeMineTask(task, assignment, tick);
                case COLLECT_DROPS -> executeCollectTask(task, assignment, tick);
                case DELIVER_SALVAGE -> executeDeliveryTask(task, assignment, tick);
                case VERIFY_GROUND -> verifyPreparedGround(task);
                case FILL_MINOR_HOLE, LEVEL_SURFACE ->
                        executeFillTask(task, assignment, tick);
            };
        }

        private boolean executeFillTask(
                TerrainPreparationTask task,
                Assignment assignment,
                long tick) {
            TerrainGradingSpecification grading = plan.gradingSpecification()
                    .orElseThrow(() -> new IllegalStateException(
                            "TERRAIN_LEVELING_UNSAFE: no exact approved fill plan is bound"));
            ResourceId taskId = fleetAdapter.taskId(task);
            int cursor = taskIndexes.getOrDefault(taskId, 0);
            if (cursor >= task.positions().size()) return true;
            BlockPos3i exact = task.positions().get(cursor);
            if (!grading.placementPositions().contains(exact)
                    || !selection.authorizedBounds().contains(exact)) {
                throw new IllegalStateException(
                        "TERRAIN_LEVELING_UNSAFE: fill task exceeds exact grading authority");
            }
            ServerLevel level = (ServerLevel) activeBot.level();
            BlockPos target = block(exact);
            if (!level.hasChunkAt(target)) {
                throw new IllegalStateException("CHUNK_NOT_LOADED: fill target is unloaded");
            }
            if (!level.getBlockState(target).isAir()) {
                throw new IllegalStateException(
                        "TERRAIN_LEVELING_UNSAFE: exact fill target is not air");
            }
            if (!atWorkPosition(level, exact)) {
                moveOneAdjacentStep(level, workPosition(level, exact));
                taskNextTicks.put(taskId, tick + 1);
                return false;
            }
            ItemStack consumed = takeFillMaterial(level, grading.fillBlockId());
            Block fillBlock = ForgeRegistries.BLOCKS.getValue(
                    ResourceLocation.tryParse(grading.fillBlockId().toString()));
            if (fillBlock == null || fillBlock.defaultBlockState().hasBlockEntity()
                    || !(fillBlock.asItem() instanceof BlockItem)
                    || !ForgeSiteSurveyAdapter.fingerprint(fillBlock.defaultBlockState())
                            .equals(grading.fillStateFingerprint())) {
                returnFillMaterial(level, consumed);
                throw new IllegalStateException(
                        "TERRAIN_LEVELING_UNSAFE: selected fill block registry state changed");
            }
            String before = ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target));
            activeBot.swing(InteractionHand.MAIN_HAND);
            boolean placed = level.setBlock(target, fillBlock.defaultBlockState(), 3);
            if (!placed || !ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target))
                    .equals(grading.fillStateFingerprint())) {
                if (placed) level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                returnFillMaterial(level, consumed);
                throw new IllegalStateException(
                        "TERRAIN_LEVELING_UNSAFE: placed block failed exact readback");
            }
            fillBlocksConsumed++;
            workersUsed.add(activeBot.getUUID());
            mutations.add(new TerrainMutationEvidence(exact, before,
                    grading.fillStateFingerprint(), "site-bot:" + sessionIdentity,
                    Instant.now()));
            taskIndexes.put(taskId, cursor + 1);
            index++;
            taskNextTicks.put(taskId, tick + 2);
            return cursor + 1 >= task.positions().size();
        }

        private ItemStack takeFillMaterial(ServerLevel level, ResourceId blockId) {
            if (!(level.getBlockEntity(destination.position) instanceof Container container)) {
                throw new IllegalStateException("TERRAIN_FILL_MATERIAL_UNAVAILABLE");
            }
            Block block = ForgeRegistries.BLOCKS.getValue(
                    ResourceLocation.tryParse(blockId.toString()));
            if (block == null || !(block.asItem() instanceof BlockItem)) {
                throw new IllegalStateException("TERRAIN_FILL_BLOCK_UNSUPPORTED");
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(block.asItem()) && !stack.hasTag()) {
                    ItemStack taken = stack.copy();
                    taken.setCount(1);
                    stack.shrink(1);
                    container.setChanged();
                    return taken;
                }
            }
            throw new IllegalStateException(
                    "TERRAIN_FILL_MATERIAL_UNAVAILABLE: authorized supply is exhausted");
        }

        private void returnFillMaterial(ServerLevel level, ItemStack material) {
            if (level.getBlockEntity(destination.position) instanceof Container container) {
                ItemStack remaining = insert(container, material.copy());
                container.setChanged();
                if (remaining.isEmpty()) return;
            }
            throw new IllegalStateException(
                    "TERRAIN_FILL_MATERIAL_UNAVAILABLE: failed placement could not restore supply");
        }

        private boolean executeMineTask(
                TerrainPreparationTask task,
                Assignment assignment,
                long tick) {
            ResourceId taskId = fleetAdapter.taskId(task);
            int cursor = taskIndexes.getOrDefault(taskId, 0);
            if (cursor >= task.positions().size()) return true;
            ApprovedObstacle target = approved(task.positions().get(cursor));
            ServerLevel level = (ServerLevel) activeBot.level();
            requireApprovedState(level, target);
            if (!atWorkPosition(null, target.position())) {
                moveOneAdjacentStep(level, workPosition(level, target.position()));
                taskNextTicks.put(taskId, tick + 1);
                return false;
            }
            clearOne(level, target, true, true);
            workersUsed.add(activeBot.getUUID());
            taskIndexes.put(taskId, cursor + 1);
            index++;
            taskNextTicks.put(taskId, tick + 2);
            return cursor + 1 >= task.positions().size();
        }

        private boolean executeCollectTask(
                TerrainPreparationTask task,
                Assignment assignment,
                long tick) {
            ResourceId taskId = fleetAdapter.taskId(task);
            int cursor = taskIndexes.getOrDefault(taskId, 0);
            if (cursor >= task.positions().size()) return true;
            ApprovedObstacle target = approved(task.positions().get(cursor));
            ServerLevel level = (ServerLevel) activeBot.level();
            if (!atWorkPosition(level, target.position())) {
                moveOneAdjacentStep(level, workPosition(level, target.position()));
                taskNextTicks.put(taskId, tick + 1);
                return false;
            }
            collectDrops(level, target, assignment.workerId());
            workersUsed.add(activeBot.getUUID());
            taskIndexes.put(taskId, cursor + 1);
            taskNextTicks.put(taskId, tick + 1);
            return cursor + 1 >= task.positions().size();
        }

        private boolean executeDeliveryTask(
                TerrainPreparationTask task,
                Assignment assignment,
                long tick) {
            ResourceId taskId = fleetAdapter.taskId(task);
            ServerLevel level = (ServerLevel) activeBot.level();
            if (!adjacent(activeBot.blockPosition(), destination.position)) {
                moveOneAdjacentStep(level, workPosition(level, position(destination.position)));
                taskNextTicks.put(taskId, tick + 1);
                return false;
            }
            deliverWorker(level, assignment.workerId());
            workersUsed.add(activeBot.getUUID());
            return true;
        }

        private boolean verifyPreparedGround(TerrainPreparationTask task) {
            ServerLevel level = (ServerLevel) activeBot.level();
            TerrainGradingSpecification grading = plan.gradingSpecification().orElse(null);
            if (grading != null) {
                for (BlockPos3i position : grading.expectedFillPositions()) {
                    if (!level.hasChunkAt(block(position))
                            || !ForgeSiteSurveyAdapter.fingerprint(
                                    level.getBlockState(block(position)))
                            .equals(grading.fillStateFingerprint())) {
                        throw new IllegalStateException(
                                "POST_CLEARANCE_SITE_CHANGED: graded surface is not exact");
                    }
                }
                for (BlockPos3i position : grading.removalPositions()) {
                    if (!grading.expectedFillPositions().contains(position)
                            && (!level.hasChunkAt(block(position))
                            || !level.getBlockState(block(position)).isAir())) {
                        throw new IllegalStateException(
                                "POST_CLEARANCE_SITE_CHANGED: clearance volume is occupied");
                    }
                }
                if (fillBlocksConsumed != grading.placementPositions().size()) {
                    throw new IllegalStateException(
                            "TERRAIN_FILL_MATERIAL_UNAVAILABLE: exact consumption mismatch");
                }
            } else {
                for (BlockPos3i position : task.positions()) {
                    if (!level.hasChunkAt(block(position))
                            || !level.getBlockState(block(position)).isAir()) {
                        throw new IllegalStateException(
                                "POST_CLEARANCE_SITE_CHANGED: approved position is not air");
                    }
                }
            }
            if (!pendingDropIds.isEmpty()
                    || carried.values().stream().anyMatch(value -> !value.isEmpty())
                    || deliveredObstacles.size() != collected.size()) {
                throw new IllegalStateException(
                        "SALVAGE_DESTINATION_UNAVAILABLE: salvage chain is incomplete");
            }
            return true;
        }

        private BotClearingUpdate botUpdate(
                TerrainPreparationTask task,
                BotClearingState state,
                Optional<ResourceId> failure,
                String detail) {
            Set<ResourceId> evidence = new java.util.LinkedHashSet<>(Set.of(
                    ResourceId.parse("site-prep:task_state"),
                    ResourceId.parse("site-prep:exact_world_readback")));
            if (task.kind() == TerrainPreparationTaskKind.VERIFY_GROUND) {
                evidence.add(TerrainPreparationFleetTaskAdapter.AUTHORITATIVE_TERRAIN_RESCAN);
            }
            Map<ResourceId, String> fields = new LinkedHashMap<>();
            fields.put(ResourceId.parse("site-prep:task_kind"), task.kind().name());
            fields.put(ResourceId.parse("site-prep:plan_hash"), plan.taskGraph().planHash());
            fields.put(ResourceId.parse("site-prep:site_snapshot_hash"),
                    plan.taskGraph().siteSnapshotHash());
            fields.put(ResourceId.parse("site-prep:actual_mutations"),
                    Integer.toString(mutations.size()));
            fields.put(ResourceId.parse("site-prep:container_opened"), "0");
            fields.put(ResourceId.parse("site-prep:player_inventory_access"), "0");
            fields.put(ResourceId.parse("site-prep:private_container_access"), "0");
            fields.put(ResourceId.parse("site-prep:unknown_nbt_mutation"), "0");
            fields.put(ResourceId.parse("site-prep:fill_blocks_consumed"),
                    Integer.toString(fillBlocksConsumed));
            fields.put(ResourceId.parse("site-prep:protected_blocks_removed"),
                    Integer.toString(protectedBlocksRemoved));
            fields.put(ResourceId.parse("site-prep:containers_destroyed"),
                    Integer.toString(containersDestroyed));
            fields.put(ResourceId.parse("site-prep:block_entities_destroyed"),
                    Integer.toString(blockEntitiesDestroyed));
            fields.put(ResourceId.parse("site-prep:dangerous_media_removed"),
                    Integer.toString(dangerousMediaRemoved));
            fields.put(ResourceId.parse("site-prep:unbreakable_blocks_removed"),
                    Integer.toString(unbreakableBlocksRemoved));
            return new BotClearingUpdate(
                    state, state == BotClearingState.COMPLETED ? 1 : 0, mutations.size(),
                    ledger(), mutations, failure, evidence, fields,
                    detail == null || detail.isBlank() ? "typed terrain failure" : detail);
        }

        private ResourceId failureCode(RuntimeException failure) {
            String detail = String.valueOf(failure.getMessage()).toUpperCase(Locale.ROOT);
            if (detail.contains("PATH_UNREACHABLE")) {
                return TerrainPreparationFleetTaskAdapter.PATH_UNREACHABLE;
            }
            if (detail.contains("CHUNK") || detail.contains("UNLOADED")) {
                return TerrainPreparationFleetTaskAdapter.CHUNK_NOT_LOADED;
            }
            if (detail.contains("BOT") && detail.contains("UNAVAILABLE")) {
                return TerrainPreparationFleetTaskAdapter.WORKER_UNAVAILABLE;
            }
            if (detail.contains("SALVAGE")) {
                return ResourceId.parse("site-prep:salvage_destination_unavailable");
            }
            if (detail.contains("TOOL")) {
                return ResourceId.parse("site-prep:bot_clearing_tool_unavailable");
            }
            if (detail.contains("LEVELING")) {
                return ResourceId.parse("site-prep:terrain_leveling_unsafe");
            }
            return ResourceId.parse("site-prep:demolition_approval_stale");
        }

        private ApprovedObstacle approved(BlockPos3i position) {
            return targets.stream().filter(value -> value.position().equals(position))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "DEMOLITION_APPROVAL_SCOPE_MISMATCH: task position is not approved"));
        }

        private void clearOne(
                ServerLevel level,
                ApprovedObstacle approved,
                boolean botTask,
                boolean deferCollection) {
            BlockPos target = block(approved.position());
            ObstacleObservation current = requireApprovedState(level, approved);
            ForcedTerrainRemoval forced = forcedRemoval(approved.position()).orElse(null);
            if (botTask) {
                equip(current.toolRequirement());
                activeBot.swing(InteractionHand.MAIN_HAND);
                workersUsed.add(activeBot.getUUID());
            }
            if (forced != null) {
                String before = current.blockStateFingerprint();
                // A normal chest removal invokes ChestBlock.onRemove, which drops its
                // inventory. The separately confirmed destructive path must discard
                // the opaque BlockEntity without reading or transferring its contents.
                // Removing the BlockEntity first also makes that guarantee independent
                // of how long the Bot takes to finish the remaining grading tasks.
                if (forced.blockEntity()) {
                    level.removeBlockEntity(target);
                }
                if (!level.setBlock(target, Blocks.AIR.defaultBlockState(), 3)
                        || !level.getBlockState(target).isAir()) {
                    throw new IllegalStateException(
                            "FORCED_TERRAIN_REMOVAL_FAILED: exact cell did not become air");
                }
                if (deferCollection) pendingDropIds.put(approved.obstacleId(), List.of());
                else {
                    collected.put(approved.obstacleId(), List.of());
                    deliveredObstacles.add(approved.obstacleId());
                }
                mutations.add(new TerrainMutationEvidence(approved.position(), before,
                        ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target)),
                        "site-bot-forced:" + sessionIdentity, Instant.now()));
                protectedBlocksRemoved++;
                if (forced.container()) containersDestroyed++;
                if (forced.blockEntity()) blockEntitiesDestroyed++;
                if (forced.dangerousMedium()) dangerousMediaRemoved++;
                if (forced.unbreakable()) unbreakableBlocksRemoved++;
                return;
            }
            Set<UUID> beforeDrops = level.getEntitiesOfClass(ItemEntity.class,
                            new AABB(target).inflate(2.0D))
                    .stream().map(ItemEntity::getUUID)
                    .collect(java.util.stream.Collectors.toSet());
            String before = current.blockStateFingerprint();
            boolean destroyed = botTask
                    ? level.destroyBlock(target, true, activeBot)
                    : level.destroyBlock(target, true);
            if (!destroyed || !level.getBlockState(target).isAir()) {
                throw new IllegalStateException("authorized block did not produce an air readback");
            }
            List<ItemStack> actual = new ArrayList<>();
            List<UUID> spawned = new ArrayList<>();
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(target).inflate(2.0D))) {
                if (!beforeDrops.contains(item.getUUID())) {
                    if (deferCollection) {
                        // The Bot collects by exact entity UUID after walking back to the
                        // drop. Vanilla ItemEntity merges equal nearby stacks meanwhile;
                        // the absorbed entity is removed and that exact UUID then looks as
                        // though real salvage vanished, although the items are still on the
                        // ground inside the surviving entity.
                        //
                        // Unlimited lifetime is also ItemEntity's vanilla no-merge sentinel
                        // (age == Short.MIN_VALUE). Set it in the same tick that destroyBlock
                        // spawned the entity, before any entity tick can merge it. Unlike
                        // setNeverPickUp, it does not strand salvage if the in-memory Bot
                        // session is lost: a player can still recover the physical item.
                        item.setUnlimitedLifetime();
                        spawned.add(item.getUUID());
                    } else {
                        actual.add(item.getItem().copy());
                        item.discard();
                    }
                }
            }
            if (deferCollection) {
                pendingDropIds.put(approved.obstacleId(), List.copyOf(spawned));
            } else {
                collected.put(approved.obstacleId(), actual);
                deliveredObstacles.add(approved.obstacleId());
            }
            mutations.add(new TerrainMutationEvidence(approved.position(), before,
                    ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target)),
                    botTask ? "site-bot:" + sessionIdentity : "site-direct:" + sessionIdentity,
                    Instant.now()));
            if (botTask) damageTool();
        }

        private ObstacleObservation requireApprovedState(
                ServerLevel level,
                ApprovedObstacle approved) {
            BlockPos target = block(approved.position());
            if (!selection.authorizedBounds().contains(approved.position())
                    || !level.hasChunkAt(target)) {
                throw new IllegalStateException("approved target is outside loaded authorized region");
            }
            ObstacleObservation current = ForgeSiteSurveyAdapter.observe(level, target);
            ForcedTerrainRemoval forced = forcedRemoval(approved.position()).orElse(null);
            boolean forcedExact = forced != null
                    && forced.blockStateFingerprint().equals(current.blockStateFingerprint())
                    && forced.blockId().equals(current.blockId());
            if (!current.blockStateFingerprint().equals(approved.blockStateFingerprint())
                    || (!forcedExact && (current.blockEntity() || current.container()))
                    || !approved.classification().approvable()) {
                throw new IllegalStateException("approved target state/classification is stale");
            }
            return current;
        }

        private Optional<ForcedTerrainRemoval> forcedRemoval(BlockPos3i position) {
            return plan.forcedRemovalAuthorization().stream()
                    .flatMap(value -> value.removals().stream())
                    .filter(value -> value.position().equals(position)).findFirst();
        }

        private void equip(String tool) {
            ItemStack stack = switch (tool) {
                case "minecraft:shears" -> new ItemStack(Items.SHEARS);
                case "minecraft:iron_axe" -> new ItemStack(Items.IRON_AXE);
                case "minecraft:iron_pickaxe" -> new ItemStack(Items.IRON_PICKAXE);
                default -> ItemStack.EMPTY;
            };
            activeBot.setItemSlot(EquipmentSlot.MAINHAND, stack);
        }

        private static int toolPriority(String tool) {
            return switch (tool) {
                case "minecraft:shears" -> 0;
                case "minecraft:iron_axe" -> 1;
                case "minecraft:iron_pickaxe" -> 2;
                default -> 3;
            };
        }

        private void damageTool() {
            ItemStack stack = activeBot.getMainHandItem();
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + 1));
                if (stack.getDamageValue() >= stack.getMaxDamage()) {
                    throw new IllegalStateException("BOT_CLEARING_TOOL_UNAVAILABLE");
                }
            }
        }

        private boolean atWorkPosition(ServerLevel level, BlockPos3i target) {
            return activeBot != null && adjacent(activeBot.blockPosition(), block(target));
        }

        private BlockPos workPosition(ServerLevel level, BlockPos3i target) {
            return workPosition(level, target, selection.authorizedBounds());
        }

        private BlockPos workPosition(ServerLevel level, BlockPos3i target,
                DeploymentBoundingBox bounds) {
            BlockPos block = block(target);
            List<BlockPos> candidates = new ArrayList<>(List.of(block.east(), block.west(),
                    block.north(), block.south(), block.above()));
            if (activeBot != null) {
                candidates.sort(Comparator.comparingInt(value ->
                        manhattan(activeBot.blockPosition(), value)));
            }
            for (BlockPos candidate : candidates) {
                if (bounds.contains(position(candidate))
                        && level.hasChunkAt(candidate)
                        && level.getBlockState(candidate).isAir()
                        && bots.stream().filter(worker -> worker != activeBot)
                        .noneMatch(worker -> !worker.isRemoved()
                                && (worker.blockPosition().equals(candidate)
                                || candidate.equals(movementTargets.get(worker.getUUID()))))) {
                    return candidate;
                }
            }
            throw new IllegalStateException("BOT_CLEARING_PATH_UNREACHABLE: no adjacent work cell");
        }

        private void moveOneAdjacentStep(ServerLevel level, BlockPos target) {
            if (activeBot == null) throw new IllegalStateException("Bot is unavailable");
            BlockPos current = activeBot.blockPosition();
            BlockPos moving = movementTargets.get(activeBot.getUUID());
            if (moving != null) {
                if (activeBot.advanceToward(moving)) {
                    movementTargets.remove(activeBot.getUUID());
                }
                return;
            }
            BlockPos next = findNextPathStep(level, current, target, true);
            if (next == null) {
                if (findNextPathStep(level, current, target, false) != null) {
                    return;
                }
                throw new IllegalStateException(
                        "BOT_CLEARING_PATH_UNREACHABLE: adjacent path cell is blocked");
            }
            movementTargets.put(activeBot.getUUID(), next);
            if (activeBot.advanceToward(next)) movementTargets.remove(activeBot.getUUID());
        }

        private BlockPos findNextPathStep(
                ServerLevel level,
                BlockPos start,
                BlockPos target,
                boolean avoidBots) {
            if (start.equals(target)) return start;
            ArrayDeque<BlockPos> ready = new ArrayDeque<>();
            Map<BlockPos, BlockPos> parent = new LinkedHashMap<>();
            ready.add(start);
            parent.put(start, start);
            int visited = 0;
            while (!ready.isEmpty() && visited++ < 4_096) {
                BlockPos current = ready.removeFirst();
                List<BlockPos> neighbors = new ArrayList<>(List.of(
                        current.east(), current.west(), current.north(), current.south(),
                        current.above(), current.below()));
                neighbors.sort(Comparator.comparingInt((BlockPos value) ->
                                manhattan(value, target))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ));
                for (BlockPos neighbor : neighbors) {
                    if (parent.containsKey(neighbor)
                            || !selection.authorizedBounds().contains(position(neighbor))
                            || !level.hasChunkAt(neighbor)
                            || !level.getBlockState(neighbor).isAir()) {
                        continue;
                    }
                    if (avoidBots && bots.stream()
                            .filter(worker -> worker != activeBot && !worker.isRemoved())
                            .anyMatch(worker -> worker.blockPosition().equals(neighbor)
                                    || neighbor.equals(movementTargets.get(worker.getUUID())))) {
                        continue;
                    }
                    parent.put(neighbor, current);
                    if (neighbor.equals(target)) {
                        BlockPos step = neighbor;
                        while (!parent.get(step).equals(start)) step = parent.get(step);
                        return step;
                    }
                    ready.addLast(neighbor);
                }
            }
            return null;
        }

        private void collectDrops(
                ServerLevel level,
                ApprovedObstacle approved,
                ResourceId workerId) {
            List<UUID> dropIds = pendingDropIds.remove(approved.obstacleId());
            if (dropIds == null) {
                throw new IllegalStateException(
                        "SALVAGE_DESTINATION_UNAVAILABLE: no mined-drop journal exists");
            }
            List<ItemStack> actual = new ArrayList<>();
            for (UUID dropId : dropIds) {
                if (!(level.getEntity(dropId) instanceof ItemEntity item) || item.isRemoved()) {
                    throw new IllegalStateException(
                            "SALVAGE_DESTINATION_UNAVAILABLE: an actual drop disappeared");
                }
                actual.add(item.getItem().copy());
                item.discard();
            }
            collected.put(approved.obstacleId(), List.copyOf(actual));
            carried.computeIfAbsent(workerId, ignored -> new LinkedHashMap<>())
                    .put(approved.obstacleId(), List.copyOf(actual));
        }

        private void deliverWorker(ServerLevel level, ResourceId workerId) {
            BlockEntity entity = level.getBlockEntity(destination.position);
            if (!(entity instanceof Container container)) {
                throw new IllegalStateException("SALVAGE_DESTINATION_UNAVAILABLE");
            }
            Map<String, List<ItemStack>> manifest = carried.getOrDefault(workerId, Map.of());
            List<ItemStack> stacks = manifest.values().stream().flatMap(List::stream)
                    .map(ItemStack::copy).toList();
            if (!canInsertAll(container, stacks)) {
                throw new IllegalStateException(
                        "SALVAGE_DESTINATION_UNAVAILABLE: destination lacks exact capacity");
            }
            for (ItemStack stack : stacks) {
                ItemStack remaining = insert(container, stack.copy());
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException(
                            "SALVAGE_DESTINATION_UNAVAILABLE: preflight/insert mismatch");
                }
            }
            deliveredObstacles.addAll(manifest.keySet());
            carried.remove(workerId);
            container.setChanged();
        }

        private boolean canInsertAll(Container container, List<ItemStack> inputs) {
            List<ItemStack> simulated = new ArrayList<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                simulated.add(container.getItem(slot).copy());
            }
            for (ItemStack input : inputs) {
                ItemStack remaining = input.copy();
                for (int slot = 0; slot < simulated.size() && !remaining.isEmpty(); slot++) {
                    if (!container.canPlaceItem(slot, remaining)) continue;
                    ItemStack existing = simulated.get(slot);
                    if (existing.isEmpty()) {
                        int amount = Math.min(remaining.getCount(), Math.min(
                                remaining.getMaxStackSize(), container.getMaxStackSize()));
                        ItemStack placed = remaining.copy();
                        placed.setCount(amount);
                        simulated.set(slot, placed);
                        remaining.shrink(amount);
                    } else if (ItemStack.isSameItemSameTags(existing, remaining)) {
                        int capacity = Math.min(existing.getMaxStackSize(),
                                container.getMaxStackSize()) - existing.getCount();
                        int amount = Math.min(capacity, remaining.getCount());
                        if (amount > 0) {
                            existing.grow(amount);
                            remaining.shrink(amount);
                        }
                    }
                }
                if (!remaining.isEmpty()) return false;
            }
            return true;
        }

        private void reconcileFleetFailures(ServerLevel level, long tick) {
            GraphNeutralFleetCoordinator.Snapshot snapshot = fleet.snapshot(tick);
            for (ResourceId taskId : snapshot.reconciliationRequiredTaskIds()) {
                Assignment assignment = snapshot.recoveryAssignments().get(taskId);
                TerrainPreparationTask task = plan.taskGraph().tasks().stream()
                        .filter(value -> fleetAdapter.taskId(value).equals(taskId))
                        .findFirst().orElseThrow();
                boolean exact = exactRescan(level, task);
                GraphNeutralFleetCoordinator.ReconciliationEvidence evidence =
                        new GraphNeutralFleetCoordinator.ReconciliationEvidence(
                                TerrainPreparationFleetTaskAdapter.AUTHORITATIVE_TERRAIN_RESCAN,
                                assignment.sessionId(), assignment.graphId(), taskId,
                                assignment.assignmentId(), assignment.workerId(), tick, exact,
                                Map.of(
                                        ResourceId.parse("site-prep:plan_hash"),
                                        plan.taskGraph().planHash(),
                                        ResourceId.parse("site-prep:site_snapshot_hash"),
                                        plan.taskGraph().siteSnapshotHash(),
                                        ResourceId.parse("site-prep:rescan"),
                                        exact ? "exact" : "changed"),
                                "site-prep:authoritative-server-rescan");
                if (!fleet.recordReconciliation(taskId, List.of(evidence), tick) && !exact) {
                    throw new IllegalStateException(
                            "DEMOLITION_APPROVAL_STALE: fleet reconciliation rescan changed");
                }
            }
        }

        private boolean exactRescan(ServerLevel level, TerrainPreparationTask task) {
            TerrainGradingSpecification grading = plan.gradingSpecification().orElse(null);
            for (BlockPos3i taskPosition : task.positions()) {
                BlockPos target = block(taskPosition);
                if (!level.hasChunkAt(target)) return false;
                if (grading != null && grading.placementPositions().contains(taskPosition)) {
                    String current = ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target));
                    boolean placed = mutations.stream().anyMatch(value ->
                            value.position().equals(taskPosition)
                                    && value.afterStateFingerprint().equals(
                                            grading.fillStateFingerprint()));
                    if (placed) {
                        if (!current.equals(grading.fillStateFingerprint())) return false;
                    } else {
                        boolean removedForReplacement = grading.removalPositions()
                                .contains(taskPosition) && mutations.stream().anyMatch(value ->
                                value.position().equals(taskPosition)
                                        && level.getBlockState(target).isAir());
                        if (!removedForReplacement && !current.equals(
                                grading.initialStateFingerprints().get(taskPosition))) {
                            return false;
                        }
                    }
                    continue;
                }
                ApprovedObstacle approved = targets.stream()
                        .filter(value -> value.position().equals(taskPosition))
                        .findFirst().orElse(null);
                if (approved != null) {
                    boolean alreadyMutated = mutations.stream()
                            .anyMatch(value -> value.position().equals(taskPosition));
                    if (alreadyMutated) {
                        if (!level.getBlockState(target).isAir()) return false;
                    } else if (!ForgeSiteSurveyAdapter.fingerprint(level.getBlockState(target))
                            .equals(approved.blockStateFingerprint())) {
                        return false;
                    }
                } else if (taskPosition.equals(position(destination.position))
                        && !salvageCurrent(level, destination)) {
                    return false;
                }
            }
            return true;
        }

        private void deliver(ServerLevel level) {
            BlockEntity entity = level.getBlockEntity(destination.position);
            if (!(entity instanceof Container container)) {
                throw new IllegalStateException("SALVAGE_DESTINATION_UNAVAILABLE");
            }
            List<ItemStack> allStacks = collected.values().stream().flatMap(List::stream)
                    .map(ItemStack::copy).toList();
            if (!canInsertAll(container, allStacks)) {
                throw new IllegalStateException(
                        "SALVAGE_DESTINATION_UNAVAILABLE: destination is full");
            }
            for (ItemStack stack : allStacks) {
                ItemStack remaining = insert(container, stack.copy());
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException(
                            "SALVAGE_DESTINATION_UNAVAILABLE: preflight/insert mismatch");
                }
            }
            deliveredObstacles.addAll(collected.keySet());
            container.setChanged();
        }

        private SalvageLedger ledger() {
            List<SalvageEntry> entries = new ArrayList<>();
            collected.forEach((obstacle, stacks) -> {
                Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
                stacks.forEach(stack -> {
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (key != null) counts.merge(key, stack.getCount(), Integer::sum);
                });
                counts.forEach((resource, count) -> entries.add(new SalvageEntry(
                        obstacle, ResourceId.parse(resource.toString()), count,
                        deliveredObstacles.contains(obstacle) ? count : 0,
                        deliveredObstacles.contains(obstacle) ? count : 0,
                        mode == ClearingMode.DIRECT ? "site-direct:" + sessionIdentity
                                : "site-bot:" + sessionIdentity)));
            });
            return new SalvageLedger("salvage-ledger:" + sha256(sessionIdentity + "\n"
                    + entries), destination.identity, entries);
        }

        private int collectedCount() {
            return collected.values().stream().flatMap(List::stream)
                    .mapToInt(ItemStack::getCount).sum();
        }

        private int deliveredCount() {
            return phase == ClearingPhase.COMPLETED ? collectedCount() : 0;
        }

        private void cancel(String reason) {
            if (fleet != null && !bots.isEmpty() && phase != ClearingPhase.COMPLETED
                    && phase != ClearingPhase.CANCELLED) {
                fleet.cancelAll(ResourceId.parse("site-prep:operator_cancel"),
                        Math.max(0L, ((ServerLevel) bots.get(0).level()).getGameTime()));
            }
            phase = ClearingPhase.CANCELLED;
            discardBots();
            LOGGER.info("SITE_PREP_CLEARING_CANCEL session={} reason={}", sessionIdentity, reason);
        }

        private void pause(String reason) {
            if (phase == ClearingPhase.COMPLETED || phase == ClearingPhase.CANCELLED
                    || phase == ClearingPhase.FAILED) return;
            paused = true;
            pauseReason = reason == null || reason.isBlank() ? "PAUSED" : reason;
            LOGGER.info("SITE_PREP_CLEARING_PAUSE session={} reason={}", sessionIdentity, pauseReason);
        }

        private void resume() {
            if (phase == ClearingPhase.COMPLETED || phase == ClearingPhase.CANCELLED
                    || phase == ClearingPhase.FAILED) {
                throw new IllegalStateException("CLEARING_NOT_RESUMABLE");
            }
            paused = false;
            pauseReason = "";
            LOGGER.info("SITE_PREP_CLEARING_RESUME session={}", sessionIdentity);
        }

        private boolean safeToCancel() {
            return pendingDropIds.isEmpty()
                    && carried.values().stream().allMatch(Map::isEmpty)
                    && (collected.isEmpty() || deliveredObstacles.size() == collected.size());
        }

        private void fail(String reason) {
            phase = ClearingPhase.FAILED;
            discardBots();
            LOGGER.info("SITE_PREP_CLEARING_FAIL session={} reason={}", sessionIdentity, reason);
        }

        private void simulateReload(long tick) {
            if (fleet == null) throw new IllegalStateException("No Bot fleet exists");
            GraphNeutralFleetCoordinator.Snapshot snapshot = fleet.snapshot(tick);
            liveAssignments.clear();
            fleet = GraphNeutralFleetCoordinator.restore(
                    snapshot, plan.taskGraph(), fleetAdapter,
                    new TerrainPreparationFleetDispatcher(plan, this, fleetAdapter),
                    TerrainPreparationFleetDispatcher.DISPATCHER_ID,
                    List.copyOf(fleetWorkers.values()),
                    fleetAdapter.workPositions(plan.taskGraph()),
                    AssignmentPolicy.LEAST_CARRIED_THEN_ID,
                    new RetryBudget(2, 1), new WorkerHealthPolicy(1, true), 72_000);
        }

        private final class SitePreparationFleetWorker implements FleetWorker {
            private final ResourceId workerId;
            private final ConstructionBotEntity entity;

            private SitePreparationFleetWorker(
                    ResourceId workerId,
                    ConstructionBotEntity entity) {
                this.workerId = workerId;
                this.entity = entity;
            }

            @Override
            public ResourceId workerId() {
                return workerId;
            }

            @Override
            public BotWorkerSnapshot snapshot(long tick) {
                Assignment assignment = liveAssignments.get(workerId);
                boolean busy = assignment != null;
                Map<ResourceId, Long> quantities = new LinkedHashMap<>();
                carried.getOrDefault(workerId, Map.of()).values().stream()
                        .flatMap(List::stream).forEach(stack -> {
                            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                            if (id != null) quantities.merge(
                                    ResourceId.parse(id.toString()), (long) stack.getCount(),
                                    Math::addExact);
                        });
                boolean loaded = entity.isAlive()
                        && entity.level() instanceof ServerLevel serverLevel
                        && serverLevel.hasChunkAt(entity.blockPosition());
                return new BotWorkerSnapshot(
                        workerId, loaded
                        ? busy ? BotWorkerStatus.BUSY : BotWorkerStatus.IDLE
                        : BotWorkerStatus.OFFLINE,
                        position(entity.blockPosition()),
                        ResourceId.parse("site-prep:region/"
                                + selection.selectionHash().substring(0, 32)),
                        java.util.EnumSet.allOf(BotWorkerCapability.class),
                        busy ? Optional.of(assignment.sessionId()) : Optional.empty(),
                        busy ? Optional.of(assignment.taskId()) : Optional.empty(),
                        busy ? Optional.of(assignment.assignmentId()) : Optional.empty(),
                        new BotInventory(workerId, BotInventory.MAX_CAPACITY,
                                quantities, mutations.size(), tick),
                        loaded ? 20 : 1, loaded, false, mutations.size(), tick);
            }
        }

        private ConstructionBotEntity spawnBot(
                ServerLevel level,
                BlockPos start,
                int number) {
            ConstructionBotEntity worker = Optional.ofNullable(
                            ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level))
                    .orElseThrow(() -> new IllegalStateException(
                            "BOT_CLEARING_PATH_UNREACHABLE: worker entity unavailable"));
            worker.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                    0.0F, 0.0F);
            worker.setRole(number == 1
                    ? ConstructionBotEntity.Role.LOGISTICS
                    : ConstructionBotEntity.Role.BUILDER_INSPECTOR);
            worker.setCustomName(Component.literal("Steve Site Preparation Bot " + number));
            worker.setCustomNameVisible(true);
            if (!level.addFreshEntity(worker)) {
                throw new IllegalStateException("BOT_CLEARING_PATH_UNREACHABLE: spawn failed");
            }
            return worker;
        }

        private BlockPos spawnCell(ServerLevel level, BlockPos first) {
            for (BlockPos candidate : List.of(first.east(), first.west(),
                    first.north(), first.south())) {
                if (selection.authorizedBounds().contains(position(candidate))
                        && level.hasChunkAt(candidate)
                        && level.getBlockState(candidate).isAir()) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "BOT_CLEARING_PATH_UNREACHABLE: no non-overlapping second Bot spawn");
        }

        private void discardBots() {
            bots.forEach(worker -> {
                if (!worker.isRemoved()) worker.discard();
            });
            movementTargets.clear();
            activeBot = null;
        }
    }

    private static ItemStack insert(Container container, ItemStack input) {
        ItemStack remaining = input;
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!container.canPlaceItem(slot, remaining)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                int amount = Math.min(remaining.getCount(),
                        Math.min(remaining.getMaxStackSize(), container.getMaxStackSize()));
                ItemStack placed = remaining.copy();
                placed.setCount(amount);
                container.setItem(slot, placed);
                remaining.shrink(amount);
            } else if (ItemStack.isSameItemSameTags(existing, remaining)) {
                int capacity = Math.min(existing.getMaxStackSize(), container.getMaxStackSize())
                        - existing.getCount();
                int amount = Math.min(capacity, remaining.getCount());
                if (amount > 0) {
                    existing.grow(amount);
                    remaining.shrink(amount);
                }
            }
        }
        return remaining;
    }

    private static boolean adjacent(BlockPos first, BlockPos second) {
        return manhattan(first, second) == 1;
    }

    private static int manhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ());
    }

    static final class AcceptanceHandle {
        private final ClearingSession session;
        private final FakePlayer player;
        private final SiteSurveySnapshot initialSurvey;

        private AcceptanceHandle(
                ClearingSession session,
                FakePlayer player,
                SiteSurveySnapshot initialSurvey) {
            this.session = session;
            this.player = player;
            this.initialSurvey = initialSurvey;
        }

        boolean tick(ServerLevel level) {
            return session.tick(level, player);
        }

        int mutations() {
            return session.mutations.size();
        }

        int fillBlocksConsumed() {
            return session.fillBlocksConsumed;
        }

        int protectedBlocksRemoved() {
            return session.protectedBlocksRemoved;
        }

        int containersDestroyed() {
            return session.containersDestroyed;
        }

        int blockEntitiesDestroyed() {
            return session.blockEntitiesDestroyed;
        }

        int dangerousMediaRemoved() {
            return session.dangerousMediaRemoved;
        }

        int unbreakableBlocksRemoved() {
            return session.unbreakableBlocksRemoved;
        }

        String debugStatus() {
            long tick = session.bots.isEmpty() ? 0L
                    : ((ServerLevel) session.bots.get(0).level()).getGameTime();
            return "phase=" + session.phase + " mutations=" + session.mutations.size()
                    + " filled=" + session.fillBlocksConsumed + " taskIndexes="
                    + session.taskIndexes + " assignments=" + session.liveAssignments
                    + " fleet=" + (session.fleet == null ? "none" : session.fleet.snapshot(tick));
        }

        SalvageLedger ledger() {
            return session.ledger();
        }

        boolean botRemoved() {
            return session.bots.stream().allMatch(ConstructionBotEntity::isRemoved);
        }

        int workerCount() {
            return session.bots.size();
        }

        int workersUsed() {
            return session.workersUsed.size();
        }

        int assignmentWorkersSeen() {
            return session.fleetAssignmentsSeen.size();
        }

        void reload(ServerLevel level) {
            session.simulateReload(level.getGameTime());
        }

        void cancel() {
            session.cancel("acceptance cancel");
        }

        void pause() {
            session.pause("acceptance pause");
        }

        void resume() {
            session.resume();
        }

        boolean paused() {
            return session.paused;
        }

        ConfirmedSiteSelection selection() {
            return session.selection;
        }

        PreparedConstructionSite prepare(ServerLevel level) {
            if (session.phase != ClearingPhase.COMPLETED
                    || session.bots.stream().anyMatch(worker -> !worker.isRemoved())) {
                throw new IllegalStateException(
                        "POST_CLEARANCE_RESCAN_REQUIRED: clearing is not terminal");
            }
            List<ObstacleObservation> observations = new ArrayList<>();
            TerrainGradingSpecification grading = session.plan.gradingSpecification()
                    .orElse(null);
            DeploymentBoundingBox bounds = grading == null
                    ? session.selection.authorizedBounds()
                    : new DeploymentBoundingBox(
                            new BlockPos3i(grading.minimumX(),
                                    grading.surfaceY() - grading.maximumFillDepth(),
                                    grading.minimumZ()),
                            new BlockPos3i(grading.maximumX(),
                                    grading.surfaceY() + grading.clearanceHeight(),
                                    grading.maximumZ()));
            Set<BlockPos3i> expectedFill = grading == null ? Set.of()
                    : Set.copyOf(grading.expectedFillPositions());
            for (int y = bounds.minimum().y(); y <= bounds.maximum().y(); y++) {
                for (int z = bounds.minimum().z(); z <= bounds.maximum().z(); z++) {
                    for (int x = bounds.minimum().x(); x <= bounds.maximum().x(); x++) {
                        BlockPos position = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(position)) {
                            throw new IllegalStateException(
                                    "SITE_SURVEY_STALE: integrated cell is unloaded");
                        }
                        if (!level.getBlockState(position).isAir()
                                && (grading == null || y > grading.surfaceY()
                                || expectedFill.contains(SitePreparationCommand.position(position)))) {
                            observations.add(ForgeSiteSurveyAdapter.observe(level, position));
                        }
                    }
                }
            }
            Instant preparedAt = Instant.now();
            SiteSurveySnapshot clean = new SiteSurvey(
                    new dev.stevecreate.agent.core.siteprep.ObstacleClassifier()).assemble(
                    session.worldIdentity, session.dimension, bounds,
                    session.selection.selectionHash(), session.plan.taskGraph().planHash(),
                    preparedAt, observations);
            return new PostClearanceRescan().prepare(
                    session.selection, initialSurvey, clean, session.plan, session.ledger(),
                    session.mutations, preparedAt, preparedAt.plusSeconds(300));
        }
    }

    private record Corner(
            String worldIdentity,
            ResourceId dimension,
            BlockPos3i position,
            Instant selectedAt) {}

    private record GradeCorner(int x, int z) {}

    private record SalvageDestination(
            String worldIdentity,
            ResourceId dimension,
            BlockPos position,
            String stateFingerprint,
            String playerIdentity,
            String identity) {}

    private enum ClearingMode {
        DIRECT,
        BOTS,
        HYBRID
    }

    private enum SurveyPurpose {
        INITIAL,
        GRADING_INITIAL,
        GRADING_FORCE_VALIDATION,
        PRE_CLEAR_VALIDATION,
        POST_CLEARANCE
    }

    private enum ClearingPhase {
        CLEAR,
        DELIVER,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    record PreparedExecutionContext(
            PreparedConstructionSite prepared,
            ConfirmedSiteSelection selection) {}

    record PlayerClearingAction(boolean success, String statusCode, String sessionIdentity) {
        static PlayerClearingAction failure(String code) {
            return new PlayerClearingAction(false, code, "");
        }
    }

    public record PlayerClearingSnapshot(
            boolean active,
            boolean paused,
            boolean prepared,
            String phase,
            int completedTargets,
            int totalTargets,
            int mutations,
            int salvageCollected,
            int salvageDelivered,
            int activeBots,
            String statusCode,
            boolean safeToCancel) {}

    private static final class PlayerSiteState {
        private final String sessionIdentity;
        /**
         * The player project that owns this state, or null for the command-driven path.
         *
         * <p>Only a project-owned state is persisted. A command session is re-run by hand
         * and has nothing durable to strand.</p>
         */
        private UUID workflowProjectId;
        private PlacementAnchor anchor;
        private Corner pos1;
        private Corner pos2;
        private RegionCornerSelection region;
        private SiteFacing facing;
        private ConfirmedSiteSelection confirmed;
        private String planHash;
        private SiteSurveySnapshot survey;
        private DemolitionPreview preview;
        private DemolitionApprovalToken approval;
        private SalvageDestination salvage;
        private PreparedConstructionSite prepared;
        private ClearingSession lastCompleted;
        private ClearingMode pendingMode;
        private GradeCorner gradeCorner1;
        private GradeCorner gradeCorner2;
        private Integer gradeSurfaceY;
        private DeploymentBoundingBox gradingScanBounds;
        private ResourceId gradingFillBlockId;
        private String gradingFillStateFingerprint;
        private TerrainGradingSpecification gradingSpecification;
        private ForcedTerrainRemovalAuthorization pendingForcedAuthorization;
        private ForcedTerrainRemovalAuthorization forcedRemovalAuthorization;
        private String latestSafetyLine = "not-run";

        private PlayerSiteState(String sessionIdentity) {
            this.sessionIdentity = sessionIdentity;
        }
    }
}
