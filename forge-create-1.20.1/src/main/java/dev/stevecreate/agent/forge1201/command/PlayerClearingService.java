package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalService;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalState;
import dev.stevecreate.agent.core.siteprep.DemolitionApprovalToken;
import dev.stevecreate.agent.forge1201.player.PlayerApprovalSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerSalvageSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

/** Server-only player bridge for dedicated salvage selection and shared Bot clearing. */
public final class PlayerClearingService {
    private static final int MAX_CONTAINER_DISTANCE_SQUARED = 16 * 16;
    private static final long STATUS_INTERVAL_TICKS = 20;
    private static final DemolitionApprovalService APPROVALS = new DemolitionApprovalService();
    private static final Map<UUID, LastStatus> LAST_STATUS = new HashMap<>();

    private PlayerClearingService() {}

    public static Result bindSalvage(
            ServerPlayer player, UUID projectId, long projectNonce, BlockPos3i position) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElse(null);
        String validation = validate(project, projectId, projectNonce,
                WorkflowStage.AWAITING_APPROVAL);
        if (validation != null) return Result.failure(validation);
        DemolitionApprovalToken token = PlayerApprovalSavedData.forLevel(player.serverLevel())
                .token(projectId).orElse(null);
        if (token == null || token.state() != DemolitionApprovalState.ACTIVE
                || !Instant.now().isBefore(token.expiresAt())) {
            return Result.failure("DEMOLITION_APPROVAL_STALE");
        }
        PlayerPreviewService.PreviewResult preview = PlayerPreviewService.preview(player,
                projectId, projectNonce, project.anchor(), project.orientation(),
                project.layoutVariant());
        if (!preview.success() || !preview.planHash().equals(project.planHash())
                || !preview.snapshotHash().equals(project.siteSnapshotHash())) {
            return Result.failure("DEMOLITION_APPROVAL_STALE");
        }
        var authorized = PlayerApprovalService.authorizedBounds(player, preview);
        String regionHash = PlayerApprovalService.regionAuthorizationHash(project, authorized);
        if (!authorized.contains(position)
                || !regionHash.equals(token.context().regionAuthorizationHash())) {
            return Result.failure("SALVAGE_OUTSIDE_AUTHORIZED_REGION");
        }
        if (player.distanceToSqr(position.x() + 0.5D, position.y() + 0.5D,
                position.z() + 0.5D) > MAX_CONTAINER_DISTANCE_SQUARED) {
            return Result.failure("SALVAGE_OUT_OF_RANGE");
        }
        BlockPos exact = new BlockPos(position.x(), position.y(), position.z());
        if (!player.serverLevel().hasChunkAt(exact)
                || !(player.serverLevel().getBlockEntity(exact) instanceof Container)) {
            return Result.failure("SALVAGE_DESTINATION_UNAVAILABLE");
        }
        String stateFingerprint = ForgeSiteSurveyAdapter.fingerprint(
                player.serverLevel().getBlockState(exact));
        long now = Instant.now().toEpochMilli();
        String identity = "player-salvage:" + sha256(projectId + "\n" + player.getUUID()
                + "\n" + project.dimension() + "\n" + position + "\n" + stateFingerprint);
        PlayerSalvageSavedData.forLevel(player.serverLevel()).put(
                new PlayerSalvageSavedData.Entry(projectId, player.getUUID(),
                        project.dimension(), position, stateFingerprint, identity, now));
        PlayerWorkflowSavedData.ProjectEntry updated = project.withStage(
                WorkflowStage.AWAITING_APPROVAL, PlayerWorkflowService.nextNonce(), now,
                "SALVAGE_BOUND");
        data.put(updated);
        return new Result(true, "OK", updated, SitePreparationCommand.playerWorkflowSnapshot(player));
    }

    public static Result start(ServerPlayer player, UUID projectId, long projectNonce) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElse(null);
        String validation = validate(project, projectId, projectNonce,
                WorkflowStage.AWAITING_APPROVAL);
        if (validation != null) return Result.failure(validation);
        DemolitionApprovalToken token = PlayerApprovalSavedData.forLevel(player.serverLevel())
                .token(projectId).orElse(null);
        PlayerSalvageSavedData.Entry salvage = PlayerSalvageSavedData
                .forLevel(player.serverLevel()).entry(projectId).orElse(null);
        if (token == null || salvage == null) return Result.failure(
                salvage == null ? "SALVAGE_DESTINATION_UNAVAILABLE" : "DEMOLITION_APPROVAL_STALE");
        PlayerPreviewService.PreviewResult preview = PlayerPreviewService.preview(player,
                projectId, projectNonce, project.anchor(), project.orientation(),
                project.layoutVariant());
        SitePreparationCommand.PlayerClearingAction started =
                SitePreparationCommand.startPlayerWorkflow(player, project, token, preview, salvage);
        if (!started.success()) return Result.failure(started.statusCode());
        PlayerApprovalSavedData.forLevel(player.serverLevel()).put(projectId,
                APPROVALS.consume(token));
        long now = Instant.now().toEpochMilli();
        PlayerWorkflowSavedData.ProjectEntry updated = project.withStage(
                WorkflowStage.CLEARING, PlayerWorkflowService.nextNonce(), now,
                "BOT_CLEARING_RUNNING");
        data.put(updated);
        LAST_STATUS.remove(player.getUUID());
        return new Result(true, "OK", updated, SitePreparationCommand.playerWorkflowSnapshot(player));
    }

    public static Result control(
            ServerPlayer player, UUID projectId, long projectNonce, Action action) {
        PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
        PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElse(null);
        if (project == null || !project.projectId().equals(projectId)) {
            return Result.failure("PROJECT_NOT_FOUND");
        }
        if (project.nonce() != projectNonce) return Result.failure("STALE_PROJECT_REQUEST");
        // Asking about a project, or giving up on one, is allowed from anywhere it is
        // still alive. This listed the same four stages the terminal's manage button did,
        // so a project resting in MATERIAL_SOURCE_SELECTION could be neither inspected nor
        // abandoned — and site preparation lives in memory, so a restart puts a project
        // exactly there with no way forward.
        //
        // Pausing and resuming still need a clearing session, and say so below.
        if (project.stage() == WorkflowStage.COMPLETED
                || project.stage() == WorkflowStage.CANCELLED
                || project.stage() == WorkflowStage.REFUSED) {
            return Result.failure("PROJECT_ALREADY_FINISHED");
        }
        boolean clearingStage = project.stage() == WorkflowStage.CLEARING
                || project.stage() == WorkflowStage.PAUSED
                || project.stage() == WorkflowStage.POST_CLEAR_RESCAN
                || project.stage() == WorkflowStage.CONSTRUCTION;
        if (!clearingStage) {
            if (action == Action.STATUS) {
                return new Result(true, "OK", project,
                        SitePreparationCommand.playerWorkflowSnapshot(player));
            }
            if (action == Action.CANCEL) {
                PlayerWorkflowService.CreateResult abandoned =
                        PlayerWorkflowService.abandon(player, projectId, projectNonce);
                if (!abandoned.success()) return Result.failure(abandoned.statusCode());
                return new Result(true, "CANCELLED_BY_PLAYER", abandoned.project(),
                        SitePreparationCommand.playerWorkflowSnapshot(player));
            }
            return Result.failure("PROJECT_STAGE_MISMATCH");
        }
        if (project.stage() == WorkflowStage.CONSTRUCTION && action != Action.STATUS) {
            if (action != Action.CANCEL) return Result.failure("CONSTRUCTION_CONTROL_UNAVAILABLE");
            PlayerConstructionService.Result cancelled = PlayerConstructionService.cancel(
                    player, projectId, projectNonce);
            if (!cancelled.success()) return Result.failure(cancelled.statusCode());
            return new Result(true, cancelled.statusCode(), cancelled.project(),
                    SitePreparationCommand.playerWorkflowSnapshot(player));
        }
        if (project.stage() == WorkflowStage.PAUSED
                && PlayerConstructionService.isMaterialPause(project.statusCode())) {
            if (action == Action.CANCEL) {
                PlayerConstructionService.Result cancelled = PlayerConstructionService.recoverCancel(
                        player, projectId, projectNonce);
                if (!cancelled.success()) return Result.failure(cancelled.statusCode());
                return new Result(true, cancelled.statusCode(), cancelled.project(),
                        SitePreparationCommand.playerWorkflowSnapshot(player));
            }
            if (action != Action.STATUS) return Result.failure("MATERIAL_RECONCILIATION_REQUIRED");
            SitePreparationCommand.PlayerClearingSnapshot snapshot =
                    new SitePreparationCommand.PlayerClearingSnapshot(
                            false, true, true, "MATERIAL_RECONCILIATION", 0, 0, 0, 0, 0, 0,
                            project.statusCode(), true);
            return new Result(true, "OK", project, snapshot);
        }
        if (project.stage() == WorkflowStage.CONSTRUCTION) {
            boolean active = PlayerConstructionService.isActive(projectId);
            SitePreparationCommand.PlayerClearingSnapshot snapshot =
                    new SitePreparationCommand.PlayerClearingSnapshot(
                            active, false, true, "CONSTRUCTION", 0, 0, 0, 0, 0,
                            active ? 1 : 0,
                            active ? project.statusCode() : "MATERIAL_RECONCILIATION_REQUIRED",
                            active);
            return new Result(true, "OK", project, snapshot);
        }
        SitePreparationCommand.PlayerClearingAction result = switch (action) {
            case STATUS -> new SitePreparationCommand.PlayerClearingAction(
                    true, "OK", "status-only");
            case PAUSE -> SitePreparationCommand.pausePlayerWorkflow(player);
            case RESUME -> SitePreparationCommand.resumePlayerWorkflow(player);
            case CANCEL -> SitePreparationCommand.cancelPlayerWorkflow(player);
        };
        if (!result.success()) return Result.failure(result.statusCode());
        SitePreparationCommand.PlayerClearingSnapshot snapshot =
                SitePreparationCommand.playerWorkflowSnapshot(player);
        StageTransition transition = transition(project.stage(), project.statusCode(), snapshot);
        WorkflowStage next = switch (action) {
            case PAUSE -> WorkflowStage.PAUSED;
            case RESUME -> snapshot.active() ? WorkflowStage.CLEARING : transition.stage();
            case STATUS -> transition.stage();
            case CANCEL -> WorkflowStage.CANCELLED;
        };
        String code = switch (action) {
            case PAUSE -> "PLAYER_PAUSED";
            case RESUME -> snapshot.active() ? "BOT_CLEARING_RUNNING" : transition.statusCode();
            case CANCEL -> "CANCELLED_WITHOUT_UNDELIVERED_SALVAGE";
            case STATUS -> transition.statusCode();
        };
        PlayerWorkflowSavedData.ProjectEntry updated = project.withStage(next,
                PlayerWorkflowService.nextNonce(), Instant.now().toEpochMilli(), code);
        data.put(updated);
        return new Result(true, "OK", updated, snapshot);
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerWorkflowSavedData data = PlayerWorkflowSavedData.forLevel(player.serverLevel());
            PlayerWorkflowSavedData.ProjectEntry project = data.entry(player.getUUID()).orElse(null);
            if (project == null || (project.stage() != WorkflowStage.CLEARING
                    && project.stage() != WorkflowStage.PAUSED
                    && project.stage() != WorkflowStage.POST_CLEAR_RESCAN)) continue;
            SitePreparationCommand.PlayerClearingSnapshot snapshot =
                    SitePreparationCommand.playerWorkflowSnapshot(player);
            StageTransition transition = transition(project.stage(), project.statusCode(), snapshot);
            WorkflowStage next = transition.stage();
            String code = transition.statusCode();
            if (next != project.stage() || !code.equals(project.statusCode())) {
                project = project.withStage(next, PlayerWorkflowService.nextNonce(),
                        Instant.now().toEpochMilli(), code);
                data.put(project);
            }
            long tick = player.serverLevel().getGameTime();
            String fingerprint = snapshot.phase() + "|" + snapshot.paused() + "|"
                    + snapshot.completedTargets() + "|" + snapshot.mutations() + "|"
                    + snapshot.salvageDelivered() + "|" + project.nonce();
            LastStatus last = LAST_STATUS.get(player.getUUID());
            if (last == null || !last.fingerprint().equals(fingerprint)
                    || tick - last.sentAtTick() >= STATUS_INTERVAL_TICKS) {
                PlayerWorkflowNetwork.sendClearingStatus(player, project, snapshot);
                LAST_STATUS.put(player.getUUID(), new LastStatus(tick, fingerprint));
            }
        }
    }

    public static void clearPlayer(UUID playerId) {
        LAST_STATUS.remove(playerId);
    }

    public static void clearServerState() {
        LAST_STATUS.clear();
    }

    static StageTransition transition(
            WorkflowStage current,
            String currentStatus,
            SitePreparationCommand.PlayerClearingSnapshot snapshot) {
        // A material failure is not another successful post-clearance hand-off. Keep
        // its paused identity so the visible cancel path can invoke transactional
        // recovery instead of demoting it to a fresh-looking material selection screen.
        if (current == WorkflowStage.PAUSED
                && PlayerConstructionService.isMaterialPause(currentStatus)) {
            return new StageTransition(WorkflowStage.PAUSED, currentStatus);
        }
        if (snapshot.prepared()) {
            return new StageTransition(
                WorkflowStage.MATERIAL_SOURCE_SELECTION, "MATERIAL_SOURCE_SELECTION_REQUIRED");
        }
        if ("POST_CLEAR_RESCAN".equals(snapshot.phase())) {
            return new StageTransition(
                    WorkflowStage.POST_CLEAR_RESCAN, "POST_CLEAR_RESCAN_RUNNING");
        }
        if (snapshot.active()) {
            return new StageTransition(snapshot.paused() ? WorkflowStage.PAUSED
                    : WorkflowStage.CLEARING,
                    snapshot.paused() ? snapshot.statusCode() : "BOT_CLEARING_RUNNING");
        }
        if (current == WorkflowStage.CONSTRUCTION
                || current == WorkflowStage.MATERIAL_SOURCE_SELECTION
                || current == WorkflowStage.MATERIAL_RESERVED) {
            return new StageTransition(
                    WorkflowStage.PAUSED, "CONSTRUCTION_RECOVERY_REPLAN_REQUIRED");
        }
        if (current == WorkflowStage.CLEARING
                || current == WorkflowStage.POST_CLEAR_RESCAN
                || current == WorkflowStage.PAUSED) {
            String code = currentStatus.startsWith("CONSTRUCTION_RECOVERY_")
                    ? currentStatus : "RECOVERY_REAPPROVAL_REQUIRED";
            return new StageTransition(WorkflowStage.PAUSED, code);
        }
        return new StageTransition(current, currentStatus);
    }

    private static String validate(
            PlayerWorkflowSavedData.ProjectEntry project,
            UUID projectId,
            long nonce,
            WorkflowStage requiredStage) {
        if (project == null || !project.projectId().equals(projectId)) return "PROJECT_NOT_FOUND";
        if (project.nonce() != nonce) return "STALE_PROJECT_REQUEST";
        if (project.stage() != requiredStage) return "PROJECT_STAGE_MISMATCH";
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum Action {
        STATUS,
        PAUSE,
        RESUME,
        CANCEL
    }

    public record Result(
            boolean success,
            String statusCode,
            PlayerWorkflowSavedData.ProjectEntry project,
            SitePreparationCommand.PlayerClearingSnapshot snapshot) {
        static Result failure(String code) {
            return new Result(false, code, null, null);
        }
    }

    record StageTransition(WorkflowStage stage, String statusCode) {}

    private record LastStatus(long sentAtTick, String fingerprint) {}
}
