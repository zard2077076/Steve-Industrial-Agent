package dev.stevecreate.agent.forge1201.player.net;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.command.PlayerWorkflowService;
import dev.stevecreate.agent.forge1201.command.PlayerMaterialService;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.CreateProjectC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalSearchC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.GoalListS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.OpenTerminalS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewRequestC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.CancelPreviewC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectControlResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.StartRelocationC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationProgressS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RelocationCandidateWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.SelectRelocationCandidateC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ReselectPlacementC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.RequestConfirmationC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ConfirmationSummaryS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApproveProjectC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ApprovalResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.BindSalvageC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.SalvageResultS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.StartClearingC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ClearingStatusS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.BindMaterialSourceC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialControlC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.MaterialSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.command.SitePreparationCommand;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Small versioned protocol with expiring server-issued nonces and bounded request rate. */
public final class PlayerWorkflowNetwork {
    public static final String PROTOCOL = "phase-iv-player-workflow-v3";
    private static final long SESSION_TTL_MILLIS = 120_000L;
    private static final long RATE_WINDOW_MILLIS = 1_000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 6;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(
                    SteveIndustrialAgentMod.MOD_ID, "player_workflow"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, TrafficWindow> PROJECT_TRAFFIC = new HashMap<>();
    private static int nextMessageId;
    private static boolean initialized;

    private PlayerWorkflowNetwork() {}

    public static synchronized void register() {
        if (initialized) return;
        initialized = true;
        CHANNEL.messageBuilder(OpenTerminalS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenTerminalS2C::encode).decoder(OpenTerminalS2C::decode)
                .consumerMainThread(OpenTerminalS2C::handle).add();
        CHANNEL.messageBuilder(GoalSearchC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(GoalSearchC2S::encode).decoder(GoalSearchC2S::decode)
                .consumerMainThread(GoalSearchC2S::handle).add();
        CHANNEL.messageBuilder(GoalListS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GoalListS2C::encode).decoder(GoalListS2C::decode)
                .consumerMainThread(GoalListS2C::handle).add();
        CHANNEL.messageBuilder(CreateProjectC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CreateProjectC2S::encode).decoder(CreateProjectC2S::decode)
                .consumerMainThread(CreateProjectC2S::handle).add();
        CHANNEL.messageBuilder(ProjectResultS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ProjectResultS2C::encode).decoder(ProjectResultS2C::decode)
                .consumerMainThread(ProjectResultS2C::handle).add();
        CHANNEL.messageBuilder(PreviewRequestC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PreviewRequestC2S::encode).decoder(PreviewRequestC2S::decode)
                .consumerMainThread(PreviewRequestC2S::handle).add();
        CHANNEL.messageBuilder(PreviewSnapshotS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PreviewSnapshotS2C::encode).decoder(PreviewSnapshotS2C::decode)
                .consumerMainThread(PreviewSnapshotS2C::handle).add();
        CHANNEL.messageBuilder(CancelPreviewC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CancelPreviewC2S::encode).decoder(CancelPreviewC2S::decode)
                .consumerMainThread(CancelPreviewC2S::handle).add();
        CHANNEL.messageBuilder(ProjectControlResultS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ProjectControlResultS2C::encode).decoder(ProjectControlResultS2C::decode)
                .consumerMainThread(ProjectControlResultS2C::handle).add();
        CHANNEL.messageBuilder(StartRelocationC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StartRelocationC2S::encode).decoder(StartRelocationC2S::decode)
                .consumerMainThread(StartRelocationC2S::handle).add();
        CHANNEL.messageBuilder(RelocationProgressS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RelocationProgressS2C::encode).decoder(RelocationProgressS2C::decode)
                .consumerMainThread(RelocationProgressS2C::handle).add();
        CHANNEL.messageBuilder(RelocationResultS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RelocationResultS2C::encode).decoder(RelocationResultS2C::decode)
                .consumerMainThread(RelocationResultS2C::handle).add();
        CHANNEL.messageBuilder(SelectRelocationCandidateC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectRelocationCandidateC2S::encode).decoder(SelectRelocationCandidateC2S::decode)
                .consumerMainThread(SelectRelocationCandidateC2S::handle).add();
        CHANNEL.messageBuilder(ReselectPlacementC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ReselectPlacementC2S::encode).decoder(ReselectPlacementC2S::decode)
                .consumerMainThread(ReselectPlacementC2S::handle).add();
        CHANNEL.messageBuilder(RequestConfirmationC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestConfirmationC2S::encode).decoder(RequestConfirmationC2S::decode)
                .consumerMainThread(RequestConfirmationC2S::handle).add();
        CHANNEL.messageBuilder(ConfirmationSummaryS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ConfirmationSummaryS2C::encode).decoder(ConfirmationSummaryS2C::decode)
                .consumerMainThread(ConfirmationSummaryS2C::handle).add();
        CHANNEL.messageBuilder(ApproveProjectC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ApproveProjectC2S::encode).decoder(ApproveProjectC2S::decode)
                .consumerMainThread(ApproveProjectC2S::handle).add();
        CHANNEL.messageBuilder(ApprovalResultS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ApprovalResultS2C::encode).decoder(ApprovalResultS2C::decode)
                .consumerMainThread(ApprovalResultS2C::handle).add();
        CHANNEL.messageBuilder(BindSalvageC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindSalvageC2S::encode).decoder(BindSalvageC2S::decode)
                .consumerMainThread(BindSalvageC2S::handle).add();
        CHANNEL.messageBuilder(SalvageResultS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SalvageResultS2C::encode).decoder(SalvageResultS2C::decode)
                .consumerMainThread(SalvageResultS2C::handle).add();
        CHANNEL.messageBuilder(StartClearingC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StartClearingC2S::encode).decoder(StartClearingC2S::decode)
                .consumerMainThread(StartClearingC2S::handle).add();
        CHANNEL.messageBuilder(ClearingControlC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClearingControlC2S::encode).decoder(ClearingControlC2S::decode)
                .consumerMainThread(ClearingControlC2S::handle).add();
        CHANNEL.messageBuilder(ClearingStatusS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClearingStatusS2C::encode).decoder(ClearingStatusS2C::decode)
                .consumerMainThread(ClearingStatusS2C::handle).add();
        CHANNEL.messageBuilder(BindMaterialSourceC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindMaterialSourceC2S::encode).decoder(BindMaterialSourceC2S::decode)
                .consumerMainThread(BindMaterialSourceC2S::handle).add();
        CHANNEL.messageBuilder(MaterialControlC2S.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MaterialControlC2S::encode).decoder(MaterialControlC2S::decode)
                .consumerMainThread(MaterialControlC2S::handle).add();
        CHANNEL.messageBuilder(MaterialSnapshotS2C.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MaterialSnapshotS2C::encode).decoder(MaterialSnapshotS2C::decode)
                .consumerMainThread(MaterialSnapshotS2C::handle).add();
    }

    public static void openTerminal(ServerPlayer player) {
        long now = System.currentTimeMillis();
        long nonce = PlayerWorkflowService.nextNonce();
        SESSIONS.put(player.getUUID(), new Session(nonce, now + SESSION_TTL_MILLIS, now, 0));
        PlayerWorkflowService.TerminalSnapshot snapshot =
                PlayerWorkflowService.terminalSnapshot(player, nonce);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenTerminalS2C.from(snapshot));
    }

    /** A typed query, asked of the server rather than of the rows already on screen. */
    public static void searchGoals(GoalSearchC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void createProject(CreateProjectC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void requestPreview(PreviewRequestC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void cancelPreview(CancelPreviewC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void startRelocation(StartRelocationC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void selectRelocation(SelectRelocationCandidateC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void reselectPlacement(ReselectPlacementC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void requestConfirmation(RequestConfirmationC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void approveProject(ApproveProjectC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void bindSalvage(BindSalvageC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void startClearing(StartClearingC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void controlClearing(ClearingControlC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void bindMaterialSource(BindMaterialSourceC2S message) {
        CHANNEL.sendToServer(message);
    }

    public static void controlMaterials(MaterialControlC2S message) {
        CHANNEL.sendToServer(message);
    }

    static void sendProjectResult(ServerPlayer player, ProjectResultS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendPreview(ServerPlayer player, PreviewSnapshotS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendControl(ServerPlayer player, ProjectControlResultS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendConfirmation(ServerPlayer player, ConfirmationSummaryS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendApproval(ServerPlayer player, ApprovalResultS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendSalvageResult(ServerPlayer player, SalvageResultS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendClearingStatus(ServerPlayer player, ClearingStatusS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    static void sendMaterialSnapshot(ServerPlayer player, MaterialSnapshotS2C result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), result);
    }

    /** Pushes the authoritative material/report projection after a server-side transition. */
    public static void sendMaterialSnapshot(
            ServerPlayer player,
            PlayerMaterialService.SelectionResult result,
            PlayerWorkflowSavedData.ProjectEntry fallback) {
        sendMaterialSnapshot(player, MaterialSnapshotS2C.from(result, fallback));
    }

    /**
     * Answers a typed query with what actually matched.
     *
     * <p>Capped at {@link PlayerWorkflowPackets#MAX_GOALS} because that is what the wire
     * carries; the service caps too, and a mismatch would fail at the packet boundary
     * rather than anywhere a person could read it.</p>
     */
    public static void sendGoalResults(
            ServerPlayer player, long terminalNonce, String query,
            java.util.List<PlayerWorkflowService.GoalAvailability> goals) {
        java.util.List<PlayerWorkflowPackets.GoalWire> wire = goals.stream()
                .limit(PlayerWorkflowPackets.MAX_GOALS)
                .map(PlayerWorkflowPackets.GoalWire::from).toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GoalListS2C(terminalNonce, query, wire));
    }

    public static void sendClearingStatus(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            SitePreparationCommand.PlayerClearingSnapshot snapshot) {
        sendClearingStatus(player, ClearingStatusS2C.from(project, snapshot));
    }

    public static void sendRelocationProgress(
            ServerPlayer player, UUID projectId, int completed, int total) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RelocationProgressS2C(projectId.toString(), completed, total, "SEARCHING"));
    }

    public static void sendRelocationFailure(ServerPlayer player, String statusCode) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RelocationResultS2C(false, statusCode,
                        "00000000-0000-0000-0000-000000000000", 0, "", java.util.List.of()));
    }

    public static void sendRelocationResult(
            ServerPlayer player,
            String statusCode,
            UUID projectId,
            long projectNonce,
            String recommendedId,
            java.util.List<dev.stevecreate.agent.forge1201.command.PlayerRelocationService.CandidateView> candidates) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RelocationResultS2C("OK".equals(statusCode), statusCode,
                        projectId.toString(), projectNonce, recommendedId,
                        candidates.stream().map(RelocationCandidateWire::from).toList()));
    }

    static String validateProjectTraffic(ServerPlayer player) {
        long now = System.currentTimeMillis();
        TrafficWindow current = PROJECT_TRAFFIC.get(player.getUUID());
        if (current == null || now - current.startedAt >= RATE_WINDOW_MILLIS) {
            PROJECT_TRAFFIC.put(player.getUUID(), new TrafficWindow(now, 1));
            return null;
        }
        if (current.requests >= MAX_REQUESTS_PER_WINDOW) return "REQUEST_RATE_LIMITED";
        PROJECT_TRAFFIC.put(player.getUUID(), new TrafficWindow(
                current.startedAt, current.requests + 1));
        return null;
    }

    static String validateRequest(ServerPlayer player, long nonce) {
        long now = System.currentTimeMillis();
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.nonce != nonce || now > session.expiresAt) {
            SESSIONS.remove(player.getUUID());
            return "STALE_REQUEST";
        }
        long windowStart = session.windowStart;
        int requests = session.requests;
        if (now - windowStart >= RATE_WINDOW_MILLIS) {
            windowStart = now;
            requests = 0;
        }
        if (requests >= MAX_REQUESTS_PER_WINDOW) return "REQUEST_RATE_LIMITED";
        SESSIONS.put(player.getUUID(), new Session(
                session.nonce, session.expiresAt, windowStart, requests + 1));
        return null;
    }

    public static void clearPlayer(UUID playerId) {
        SESSIONS.remove(playerId);
        PROJECT_TRAFFIC.remove(playerId);
    }

    public static void clearServerState() {
        SESSIONS.clear();
        PROJECT_TRAFFIC.clear();
    }

    private record Session(long nonce, long expiresAt, long windowStart, int requests) {}
    private record TrafficWindow(long startedAt, int requests) {}
}
