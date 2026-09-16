package dev.stevecreate.agent.forge1201.player.net;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.forge1201.command.PlayerPreviewService;
import dev.stevecreate.agent.forge1201.command.PlayerApprovalService;
import dev.stevecreate.agent.forge1201.command.PlayerClearingService;
import dev.stevecreate.agent.forge1201.command.SitePreparationCommand;
import dev.stevecreate.agent.forge1201.command.PlayerRelocationService;
import dev.stevecreate.agent.forge1201.command.PlayerWorkflowService;
import dev.stevecreate.agent.forge1201.command.PlayerMaterialService;
import dev.stevecreate.agent.forge1201.command.MetalPressProductionService;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import dev.stevecreate.agent.forge1201.player.client.PlayerWorkflowClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

public final class PlayerWorkflowPackets {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_GOALS = 32;
    /** Long enough for any item path, short enough that the wire is not a channel. */
    public static final int MAX_QUERY = 64;
    public static final int MAX_TEXT = 160;
    public static final int MAX_SUMMARY_TEXT = 1_024;
    public static final int MAX_MATERIAL_LINES = 128;

    private PlayerWorkflowPackets() {}

    public record GoalWire(
            String target,
            String recipe,
            String capability,
            String translationKey,
            String inputs,
            int modules,
            int verifiedQuantity,
            boolean available,
            String statusCode) {
        static GoalWire from(PlayerWorkflowService.GoalAvailability value) {
            String inputs = value.entry().inputsPerBatch().entrySet().stream()
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .sorted().collect(java.util.stream.Collectors.joining(", "));
            return new GoalWire(value.entry().target().toString(), value.entry().recipe().toString(),
                    value.entry().capability().toString(),
                    PlayerGoalCatalog.translationKey(value.entry()), inputs,
                    value.entry().physicalModuleCount(),
                    PlayerGoalCatalog.verifiedQuantity(value.entry()),
                    value.available(), value.statusCode());
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(target, MAX_TEXT);
            buffer.writeUtf(recipe, MAX_TEXT);
            buffer.writeUtf(capability, MAX_TEXT);
            buffer.writeUtf(translationKey, MAX_TEXT);
            buffer.writeUtf(inputs, MAX_TEXT);
            buffer.writeVarInt(modules);
            buffer.writeVarInt(verifiedQuantity);
            buffer.writeBoolean(available);
            buffer.writeUtf(statusCode, MAX_TEXT);
        }

        static GoalWire decode(FriendlyByteBuf buffer) {
            return new GoalWire(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                    buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readUtf(MAX_TEXT));
        }
    }

    public record ProjectWire(
            String projectId,
            String target,
            int quantity,
            String stage,
            String statusCode,
            long projectNonce,
            boolean hasAnchor,
            int anchorX,
            int anchorY,
            int anchorZ,
            QuarterTurn orientation,
            LayoutVariant layoutVariant,
            PlayerExecutionMode executionMode) {
        static ProjectWire from(PlayerWorkflowSavedData.ProjectEntry project) {
            if (project == null) return null;
            BlockPos3i anchor = project.anchor();
            return new ProjectWire(project.projectId().toString(), project.target().toString(),
                    Math.toIntExact(project.quantity()), project.stage().name(),
                    project.statusCode(), project.nonce(), anchor != null,
                    anchor == null ? 0 : anchor.x(), anchor == null ? 0 : anchor.y(),
                    anchor == null ? 0 : anchor.z(), project.orientation(),
                    project.layoutVariant(), project.executionMode());
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(projectId, 36);
            buffer.writeUtf(target, MAX_TEXT);
            buffer.writeVarInt(quantity);
            buffer.writeUtf(stage, 48);
            buffer.writeUtf(statusCode, 96);
            buffer.writeLong(projectNonce);
            buffer.writeBoolean(hasAnchor);
            buffer.writeInt(anchorX); buffer.writeInt(anchorY); buffer.writeInt(anchorZ);
            buffer.writeEnum(orientation); buffer.writeEnum(layoutVariant);
            buffer.writeEnum(executionMode);
        }

        static ProjectWire decode(FriendlyByteBuf buffer) {
            return new ProjectWire(buffer.readUtf(36), buffer.readUtf(MAX_TEXT),
                    buffer.readVarInt(), buffer.readUtf(48), buffer.readUtf(96), buffer.readLong(),
                    buffer.readBoolean(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readEnum(QuarterTurn.class), buffer.readEnum(LayoutVariant.class),
                    buffer.readEnum(PlayerExecutionMode.class));
        }
    }

    public record OpenTerminalS2C(
            long terminalNonce,
            boolean constructionAuthorized,
            String refusalCode,
            List<GoalWire> goals,
            ProjectWire project) {
        static OpenTerminalS2C from(PlayerWorkflowService.TerminalSnapshot snapshot) {
            return new OpenTerminalS2C(snapshot.terminalNonce(), snapshot.constructionAuthorized(),
                    snapshot.refusalCode(), snapshot.goals().stream().map(GoalWire::from).toList(),
                    ProjectWire.from(snapshot.currentProject()));
        }

        public static void encode(OpenTerminalS2C message, FriendlyByteBuf buffer) {
            buffer.writeLong(message.terminalNonce);
            buffer.writeBoolean(message.constructionAuthorized);
            buffer.writeUtf(message.refusalCode, 96);
            if (message.goals.size() > MAX_GOALS) throw new IllegalArgumentException("goal packet too large");
            buffer.writeVarInt(message.goals.size());
            message.goals.forEach(goal -> goal.encode(buffer));
            buffer.writeBoolean(message.project != null);
            if (message.project != null) message.project.encode(buffer);
        }

        public static OpenTerminalS2C decode(FriendlyByteBuf buffer) {
            long nonce = buffer.readLong();
            boolean authorized = buffer.readBoolean();
            String refusal = buffer.readUtf(96);
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_GOALS) throw new IllegalArgumentException("invalid goal count");
            java.util.ArrayList<GoalWire> goals = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) goals.add(GoalWire.decode(buffer));
            ProjectWire project = buffer.readBoolean() ? ProjectWire.decode(buffer) : null;
            return new OpenTerminalS2C(nonce, authorized, refusal, List.copyOf(goals), project);
        }

        public static void handle(
                OpenTerminalS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.openTerminal(message)));
            context.setPacketHandled(true);
        }
    }

    /**
     * A typed query, asked of the server rather than of the eleven rows already on screen.
     *
     * <p>The picker filtered its own snapshot, and that snapshot is what an empty query
     * returns — the reviewed catalog, eleven goals. So {@code catalog(player, query)},
     * which reaches the two hundred derived targets on any non-blank query, had no caller
     * at all: the search box could never reach it. Typing "co" offered a cogwheel and a
     * cooked steak while the registry held cobblestone, copper and concrete.</p>
     */
    public record GoalSearchC2S(long terminalNonce, String query) {
        public GoalSearchC2S {
            Objects.requireNonNull(query, "query");
        }

        public static void encode(GoalSearchC2S message, FriendlyByteBuf buffer) {
            buffer.writeLong(message.terminalNonce);
            buffer.writeUtf(message.query, MAX_QUERY);
        }

        public static GoalSearchC2S decode(FriendlyByteBuf buffer) {
            return new GoalSearchC2S(buffer.readLong(), buffer.readUtf(MAX_QUERY));
        }

        public static void handle(
                GoalSearchC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) return;
                String sessionFailure = PlayerWorkflowNetwork.validateRequest(
                        sender, message.terminalNonce);
                if (sessionFailure != null) {
                    // Keep the frozen v3 wire intact: the existing project-result failure
                    // envelope already reaches GoalPickerScreen and carries a typed code.
                    PlayerWorkflowNetwork.sendProjectResult(
                            sender, new ProjectResultS2C(false, sessionFailure, null));
                    return;
                }
                PlayerWorkflowNetwork.sendGoalResults(sender, message.terminalNonce,
                        message.query, PlayerWorkflowService.catalog(sender, message.query));
            });
            context.setPacketHandled(true);
        }
    }

    /**
     * What matched, replacing the list rather than filtering it.
     *
     * <p>Carries the nonce it was asked under: a player types faster than a round trip,
     * so an older reply can arrive after a newer one and would otherwise put back the
     * results of a query they have already moved on from.</p>
     */
    public record GoalListS2C(long terminalNonce, String query, List<GoalWire> goals) {
        public GoalListS2C {
            Objects.requireNonNull(query, "query");
            goals = List.copyOf(Objects.requireNonNull(goals, "goals"));
        }

        public static void encode(GoalListS2C message, FriendlyByteBuf buffer) {
            buffer.writeLong(message.terminalNonce);
            buffer.writeUtf(message.query, MAX_QUERY);
            if (message.goals.size() > MAX_GOALS) {
                throw new IllegalArgumentException("goal packet too large");
            }
            buffer.writeVarInt(message.goals.size());
            message.goals.forEach(goal -> goal.encode(buffer));
        }

        public static GoalListS2C decode(FriendlyByteBuf buffer) {
            long nonce = buffer.readLong();
            String query = buffer.readUtf(MAX_QUERY);
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_GOALS) throw new IllegalArgumentException("invalid goal count");
            java.util.ArrayList<GoalWire> goals = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) goals.add(GoalWire.decode(buffer));
            return new GoalListS2C(nonce, query, List.copyOf(goals));
        }

        public static void handle(
                GoalListS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.goalResults(message)));
            context.setPacketHandled(true);
        }
    }

    public record CreateProjectC2S(
            long terminalNonce,
            String target,
            int quantity,
            ProductionMode productionMode,
            PlayerExecutionMode executionMode) {
        public static void encode(CreateProjectC2S message, FriendlyByteBuf buffer) {
            buffer.writeLong(message.terminalNonce);
            buffer.writeUtf(message.target, MAX_TEXT);
            buffer.writeVarInt(message.quantity);
            buffer.writeEnum(message.productionMode);
            buffer.writeEnum(message.executionMode);
        }

        public static CreateProjectC2S decode(FriendlyByteBuf buffer) {
            return new CreateProjectC2S(buffer.readLong(), buffer.readUtf(MAX_TEXT),
                    buffer.readVarInt(), buffer.readEnum(ProductionMode.class),
                    buffer.readEnum(PlayerExecutionMode.class));
        }

        public static void handle(
                CreateProjectC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String sessionFailure = PlayerWorkflowNetwork.validateRequest(
                        sender, message.terminalNonce);
                if (sessionFailure != null) {
                    PlayerWorkflowNetwork.sendProjectResult(
                            sender, new ProjectResultS2C(false, sessionFailure, null));
                    return;
                }
                PlayerWorkflowService.CreateResult result = PlayerWorkflowService.createProject(
                        sender, message.target, message.quantity,
                        message.productionMode, message.executionMode);
                PlayerWorkflowNetwork.sendProjectResult(sender,
                        new ProjectResultS2C(result.success(), result.statusCode(),
                                ProjectWire.from(result.project())));
            });
            context.setPacketHandled(true);
        }
    }

    public record ProjectResultS2C(boolean success, String statusCode, ProjectWire project) {
        public static void encode(ProjectResultS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
            buffer.writeBoolean(message.project != null);
            if (message.project != null) message.project.encode(buffer);
        }

        public static ProjectResultS2C decode(FriendlyByteBuf buffer) {
            return new ProjectResultS2C(buffer.readBoolean(), buffer.readUtf(96),
                    buffer.readBoolean() ? ProjectWire.decode(buffer) : null);
        }

        public static void handle(
                ProjectResultS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.projectResult(message)));
            context.setPacketHandled(true);
        }
    }

    public record PreviewRequestC2S(
            String projectId,
            long projectNonce,
            int anchorX,
            int anchorY,
            int anchorZ,
            QuarterTurn orientation,
            LayoutVariant variant,
            boolean finalizeSelection) {
        public static void encode(PreviewRequestC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeInt(message.anchorX);
            buffer.writeInt(message.anchorY);
            buffer.writeInt(message.anchorZ);
            buffer.writeEnum(message.orientation);
            buffer.writeEnum(message.variant);
            buffer.writeBoolean(message.finalizeSelection);
        }

        public static PreviewRequestC2S decode(FriendlyByteBuf buffer) {
            return new PreviewRequestC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readEnum(QuarterTurn.class), buffer.readEnum(LayoutVariant.class),
                    buffer.readBoolean());
        }

        public static void handle(
                PreviewRequestC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rateFailure = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rateFailure != null) {
                    PlayerWorkflowNetwork.sendPreview(sender,
                            PreviewSnapshotS2C.failure(rateFailure));
                    return;
                }
                java.util.UUID projectId;
                try {
                    projectId = java.util.UUID.fromString(message.projectId);
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendPreview(sender,
                            PreviewSnapshotS2C.failure("PROJECT_ID_INVALID"));
                    return;
                }
                PlayerPreviewService.PreviewResult result = PlayerPreviewService.preview(
                        sender, projectId, message.projectNonce,
                        new BlockPos3i(message.anchorX, message.anchorY, message.anchorZ),
                        message.orientation, message.variant);
                if (message.finalizeSelection && result.success()) {
                    PlayerWorkflowService.CreateResult finalized =
                            PlayerWorkflowService.finalizePlacement(sender, result);
                    if (!finalized.success()) {
                        PlayerWorkflowNetwork.sendPreview(sender,
                                PreviewSnapshotS2C.failure(finalized.statusCode()));
                        return;
                    }
                    PlayerWorkflowNetwork.sendPreview(sender,
                            PreviewSnapshotS2C.from(result, finalized.project(), true));
                    return;
                }
                PlayerWorkflowNetwork.sendPreview(sender, PreviewSnapshotS2C.from(result, null, false));
            });
            context.setPacketHandled(true);
        }
    }

    public record PreviewCellWire(
            int x,
            int y,
            int z,
            String blockId,
            String role,
            PlayerPreviewService.PreviewCategory category,
            String reasonCode) {
        static PreviewCellWire from(PlayerPreviewService.PreviewCell cell) {
            return new PreviewCellWire(cell.position().x(), cell.position().y(), cell.position().z(),
                    cell.blockId().toString(), cell.role(), cell.category(), cell.reasonCode());
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
            buffer.writeUtf(blockId, MAX_TEXT);
            buffer.writeUtf(role, MAX_TEXT);
            buffer.writeEnum(category);
            buffer.writeUtf(reasonCode, 96);
        }

        static PreviewCellWire decode(FriendlyByteBuf buffer) {
            return new PreviewCellWire(buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                    buffer.readEnum(PlayerPreviewService.PreviewCategory.class), buffer.readUtf(96));
        }
    }

    public record PreviewSnapshotS2C(
            boolean success,
            String statusCode,
            String projectId,
            long projectNonce,
            int anchorX,
            int anchorY,
            int anchorZ,
            QuarterTurn orientation,
            LayoutVariant variant,
            List<PreviewCellWire> cells,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            String planHash,
            String snapshotHash,
            int placeCount,
            int reuseCount,
            int clearCount,
            int protectedCount,
            int containerCount,
            int unknownCount,
            int hazardCount,
            boolean safeForConfirmation,
            boolean finalized) {
        static PreviewSnapshotS2C from(
                PlayerPreviewService.PreviewResult result,
                PlayerWorkflowSavedData.ProjectEntry finalizedProject,
                boolean finalized) {
            if (!result.success()) return failure(result.statusCode());
            var bounds = result.bounds();
            return new PreviewSnapshotS2C(true, "OK", result.projectId().toString(),
                    finalizedProject == null ? result.projectNonce() : finalizedProject.nonce(),
                    result.anchor().x(), result.anchor().y(), result.anchor().z(),
                    result.orientation(), result.variant(),
                    result.cells().stream().map(PreviewCellWire::from).toList(),
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    result.planHash(), result.snapshotHash(), result.placeCount(), result.reuseCount(),
                    result.clearCount(), result.protectedCount(), result.containerCount(),
                    result.unknownCount(), result.hazardCount(), result.safeForConfirmation(), finalized);
        }

        static PreviewSnapshotS2C failure(String code) {
            return new PreviewSnapshotS2C(false, code, "", 0, 0, 0, 0,
                    QuarterTurn.ZERO, LayoutVariant.STANDARD, List.of(),
                    0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, 0, 0, 0, 0, false, false);
        }

        public static void encode(PreviewSnapshotS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
            if (!message.success) return;
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeInt(message.anchorX);
            buffer.writeInt(message.anchorY);
            buffer.writeInt(message.anchorZ);
            buffer.writeEnum(message.orientation);
            buffer.writeEnum(message.variant);
            if (message.cells.size() > PlayerPreviewService.MAX_PREVIEW_CELLS) {
                throw new IllegalArgumentException("preview packet exceeds cell budget");
            }
            buffer.writeVarInt(message.cells.size());
            message.cells.forEach(cell -> cell.encode(buffer));
            buffer.writeInt(message.minX);
            buffer.writeInt(message.minY);
            buffer.writeInt(message.minZ);
            buffer.writeInt(message.maxX);
            buffer.writeInt(message.maxY);
            buffer.writeInt(message.maxZ);
            buffer.writeUtf(message.planHash, 64);
            buffer.writeUtf(message.snapshotHash, 64);
            buffer.writeVarInt(message.placeCount);
            buffer.writeVarInt(message.reuseCount);
            buffer.writeVarInt(message.clearCount);
            buffer.writeVarInt(message.protectedCount);
            buffer.writeVarInt(message.containerCount);
            buffer.writeVarInt(message.unknownCount);
            buffer.writeVarInt(message.hazardCount);
            buffer.writeBoolean(message.safeForConfirmation);
            buffer.writeBoolean(message.finalized);
        }

        public static PreviewSnapshotS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            if (!success) return failure(status);
            String projectId = buffer.readUtf(36);
            long nonce = buffer.readLong();
            int anchorX = buffer.readInt();
            int anchorY = buffer.readInt();
            int anchorZ = buffer.readInt();
            QuarterTurn orientation = buffer.readEnum(QuarterTurn.class);
            LayoutVariant variant = buffer.readEnum(LayoutVariant.class);
            int size = buffer.readVarInt();
            if (size < 0 || size > PlayerPreviewService.MAX_PREVIEW_CELLS) {
                throw new IllegalArgumentException("invalid preview cell count");
            }
            java.util.ArrayList<PreviewCellWire> cells = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) cells.add(PreviewCellWire.decode(buffer));
            return new PreviewSnapshotS2C(true, status, projectId, nonce,
                    anchorX, anchorY, anchorZ, orientation, variant, List.copyOf(cells),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readUtf(64), buffer.readUtf(64),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
        }

        public static void handle(
                PreviewSnapshotS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.previewSnapshot(message)));
            context.setPacketHandled(true);
        }
    }

    public record CancelPreviewC2S(String projectId, long projectNonce) {
        public static void encode(CancelPreviewC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
        }

        public static CancelPreviewC2S decode(FriendlyByteBuf buffer) {
            return new CancelPreviewC2S(buffer.readUtf(36), buffer.readLong());
        }

        public static void handle(
                CancelPreviewC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                java.util.UUID projectId;
                try {
                    projectId = java.util.UUID.fromString(message.projectId);
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendControl(sender,
                            new ProjectControlResultS2C(false, "PROJECT_ID_INVALID"));
                    return;
                }
                // Abandon rather than cancelPreview. This packet has always meant "give up
                // on this project", and cancelPreview only ever accepted one stage of it —
                // so from every other stage the same button reported PROJECT_STAGE_MISMATCH
                // or was greyed out entirely.
                PlayerWorkflowService.CreateResult result = PlayerWorkflowService.abandon(
                        sender, projectId, message.projectNonce);
                PlayerWorkflowNetwork.sendControl(sender,
                        new ProjectControlResultS2C(result.success(), result.statusCode()));
            });
            context.setPacketHandled(true);
        }
    }

    public record ProjectControlResultS2C(boolean success, String statusCode) {
        public static void encode(ProjectControlResultS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
        }

        public static ProjectControlResultS2C decode(FriendlyByteBuf buffer) {
            return new ProjectControlResultS2C(buffer.readBoolean(), buffer.readUtf(96));
        }

        public static void handle(
                ProjectControlResultS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.controlResult(message)));
            context.setPacketHandled(true);
        }
    }

    public record StartRelocationC2S(String projectId, long projectNonce) {
        public static void encode(StartRelocationC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
        }

        public static StartRelocationC2S decode(FriendlyByteBuf buffer) {
            return new StartRelocationC2S(buffer.readUtf(36), buffer.readLong());
        }

        public static void handle(
                StartRelocationC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerRelocationService.start(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce);
                    if (!result.success()) {
                        PlayerWorkflowNetwork.sendRelocationFailure(sender, result.statusCode());
                    } else {
                        PlayerWorkflowNetwork.sendRelocationProgress(sender,
                                java.util.UUID.fromString(message.projectId), 0,
                                result.totalCandidates());
                    }
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendRelocationFailure(sender, "PROJECT_ID_INVALID");
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record RelocationProgressS2C(
            String projectId,
            int completed,
            int total,
            String statusCode) {
        public static void encode(RelocationProgressS2C message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeVarInt(message.completed);
            buffer.writeVarInt(message.total);
            buffer.writeUtf(message.statusCode, 96);
        }

        public static RelocationProgressS2C decode(FriendlyByteBuf buffer) {
            return new RelocationProgressS2C(buffer.readUtf(36), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readUtf(96));
        }

        public static void handle(
                RelocationProgressS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.relocationProgress(message)));
            context.setPacketHandled(true);
        }
    }

    public record RelocationCandidateWire(
            String candidateId,
            int x,
            int y,
            int z,
            QuarterTurn orientation,
            LayoutVariant variant,
            boolean safe,
            boolean current,
            long score,
            int protectedCount,
            int unknownCount,
            int containerCount,
            int hazardCount,
            int demolitionCount,
            int materialCost,
            int blockedBotPositions,
            int expansionPenalty,
            List<String> reasons) {
        static RelocationCandidateWire from(PlayerRelocationService.CandidateView value) {
            return new RelocationCandidateWire(value.candidateId(), value.anchor().x(),
                    value.anchor().y(), value.anchor().z(), value.orientation(), value.variant(),
                    value.safe(), value.current(), value.score(), value.protectedCount(),
                    value.unknownCount(), value.containerCount(), value.hazardCount(),
                    value.demolitionCount(), value.materialCost(), value.blockedBotPositions(),
                    value.expansionPenalty(), value.reasons());
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(candidateId, 96);
            buffer.writeInt(x); buffer.writeInt(y); buffer.writeInt(z);
            buffer.writeEnum(orientation); buffer.writeEnum(variant);
            buffer.writeBoolean(safe); buffer.writeBoolean(current);
            buffer.writeLong(score);
            buffer.writeVarInt(protectedCount); buffer.writeVarInt(unknownCount);
            buffer.writeVarInt(containerCount); buffer.writeVarInt(hazardCount);
            buffer.writeVarInt(demolitionCount); buffer.writeVarInt(materialCost);
            buffer.writeVarInt(blockedBotPositions); buffer.writeVarInt(expansionPenalty);
            if (reasons.size() > 8) throw new IllegalArgumentException("too many candidate reasons");
            buffer.writeVarInt(reasons.size());
            reasons.forEach(reason -> buffer.writeUtf(reason, 96));
        }

        static RelocationCandidateWire decode(FriendlyByteBuf buffer) {
            String id = buffer.readUtf(96);
            int x = buffer.readInt(); int y = buffer.readInt(); int z = buffer.readInt();
            QuarterTurn orientation = buffer.readEnum(QuarterTurn.class);
            LayoutVariant variant = buffer.readEnum(LayoutVariant.class);
            boolean safe = buffer.readBoolean(); boolean current = buffer.readBoolean();
            long score = buffer.readLong();
            int protectedCount = buffer.readVarInt(); int unknown = buffer.readVarInt();
            int containers = buffer.readVarInt(); int hazards = buffer.readVarInt();
            int demolition = buffer.readVarInt(); int material = buffer.readVarInt();
            int bots = buffer.readVarInt(); int expansion = buffer.readVarInt();
            int size = buffer.readVarInt();
            if (size < 1 || size > 8) throw new IllegalArgumentException("invalid reason count");
            java.util.ArrayList<String> reasons = new java.util.ArrayList<>(size);
            for (int index = 0; index < size; index++) reasons.add(buffer.readUtf(96));
            return new RelocationCandidateWire(id, x, y, z, orientation, variant, safe,
                    current, score, protectedCount, unknown, containers, hazards,
                    demolition, material, bots, expansion, List.copyOf(reasons));
        }
    }

    public record RelocationResultS2C(
            boolean success,
            String statusCode,
            String projectId,
            long projectNonce,
            String recommendedId,
            List<RelocationCandidateWire> candidates) {
        public static void encode(RelocationResultS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeUtf(message.recommendedId, 96);
            if (message.candidates.size() > 3) throw new IllegalArgumentException("too many UI candidates");
            buffer.writeVarInt(message.candidates.size());
            message.candidates.forEach(candidate -> candidate.encode(buffer));
        }

        public static RelocationResultS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            String project = buffer.readUtf(36);
            long nonce = buffer.readLong();
            String recommended = buffer.readUtf(96);
            int size = buffer.readVarInt();
            if (size < 0 || size > 3) throw new IllegalArgumentException("invalid candidate count");
            java.util.ArrayList<RelocationCandidateWire> candidates = new java.util.ArrayList<>(size);
            for (int index = 0; index < size; index++) candidates.add(RelocationCandidateWire.decode(buffer));
            return new RelocationResultS2C(success, status, project, nonce,
                    recommended, List.copyOf(candidates));
        }

        public static void handle(
                RelocationResultS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.relocationResult(message)));
            context.setPacketHandled(true);
        }
    }

    public record SelectRelocationCandidateC2S(
            String projectId,
            long projectNonce,
            String candidateId) {
        public static void encode(SelectRelocationCandidateC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeUtf(message.candidateId, 96);
        }

        public static SelectRelocationCandidateC2S decode(FriendlyByteBuf buffer) {
            return new SelectRelocationCandidateC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readUtf(96));
        }

        public static void handle(
                SelectRelocationCandidateC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerRelocationService.select(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce,
                            message.candidateId);
                    PlayerWorkflowNetwork.sendPreview(sender, result.success()
                            ? PreviewSnapshotS2C.from(result.preview(), result.project(), true)
                            : PreviewSnapshotS2C.failure(result.statusCode()));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendPreview(sender,
                            PreviewSnapshotS2C.failure("PROJECT_ID_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ReselectPlacementC2S(String projectId, long projectNonce) {
        public static void encode(ReselectPlacementC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
        }

        public static ReselectPlacementC2S decode(FriendlyByteBuf buffer) {
            return new ReselectPlacementC2S(buffer.readUtf(36), buffer.readLong());
        }

        public static void handle(
                ReselectPlacementC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerWorkflowService.reselectPlacement(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce);
                    PlayerWorkflowNetwork.sendProjectResult(sender,
                            new ProjectResultS2C(result.success(), result.statusCode(),
                                    ProjectWire.from(result.project())));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendProjectResult(sender,
                            new ProjectResultS2C(false, "PROJECT_ID_INVALID", null));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record RequestConfirmationC2S(String projectId, long projectNonce) {
        public static void encode(RequestConfirmationC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
        }

        public static RequestConfirmationC2S decode(FriendlyByteBuf buffer) {
            return new RequestConfirmationC2S(buffer.readUtf(36), buffer.readLong());
        }

        public static void handle(
                RequestConfirmationC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rateFailure = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rateFailure != null) {
                    PlayerWorkflowNetwork.sendConfirmation(sender,
                            ConfirmationSummaryS2C.failure(rateFailure));
                    return;
                }
                try {
                    var result = PlayerApprovalService.summarize(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce);
                    PlayerWorkflowNetwork.sendConfirmation(sender,
                            ConfirmationSummaryS2C.from(result));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendConfirmation(sender,
                            ConfirmationSummaryS2C.failure("PROJECT_ID_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ConfirmationSummaryS2C(
            boolean success,
            String statusCode,
            String projectId,
            long projectNonce,
            String target,
            int quantity,
            int anchorX,
            int anchorY,
            int anchorZ,
            QuarterTurn orientation,
            LayoutVariant layoutVariant,
            PlayerExecutionMode executionMode,
            String planHash,
            String snapshotHash,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int placeCount,
            int reuseCount,
            int clearCount,
            int protectedCount,
            int containerCount,
            int unknownCount,
            int hazardCount,
            String requiredInputs,
            int recommendedBots,
            String phaseEstimate,
            String riskLevel,
            boolean approvable,
            String safetyPolicy) {
        static ConfirmationSummaryS2C from(PlayerApprovalService.SummaryResult result) {
            if (!result.success()) return failure(result.statusCode());
            var summary = result.summary();
            var bounds = summary.bounds();
            return new ConfirmationSummaryS2C(true, "OK", summary.projectId().toString(),
                    summary.projectNonce(), summary.target().toString(),
                    Math.toIntExact(summary.quantity()), summary.anchor().x(), summary.anchor().y(),
                    summary.anchor().z(), summary.orientation(), summary.layoutVariant(),
                    summary.executionMode(), summary.planHash(), summary.snapshotHash(),
                    bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                    bounds.maxZ(), summary.placeCount(), summary.reuseCount(), summary.clearCount(),
                    summary.protectedCount(), summary.containerCount(), summary.unknownCount(),
                    summary.hazardCount(), summary.requiredInputs(), summary.recommendedBots(),
                    summary.phaseEstimate(), summary.riskLevel(), summary.approvable(),
                    summary.safetyPolicy());
        }

        static ConfirmationSummaryS2C failure(String code) {
            return new ConfirmationSummaryS2C(false, code, "", 0, "", 0,
                    0, 0, 0, QuarterTurn.ZERO, LayoutVariant.STANDARD,
                    PlayerExecutionMode.BOTS, "", "", 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, "", 0, "", "", false, "");
        }

        public static void encode(ConfirmationSummaryS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
            if (!message.success) return;
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeUtf(message.target, MAX_TEXT);
            buffer.writeVarInt(message.quantity);
            buffer.writeInt(message.anchorX); buffer.writeInt(message.anchorY); buffer.writeInt(message.anchorZ);
            buffer.writeEnum(message.orientation); buffer.writeEnum(message.layoutVariant);
            buffer.writeEnum(message.executionMode);
            buffer.writeUtf(message.planHash, 64); buffer.writeUtf(message.snapshotHash, 64);
            buffer.writeInt(message.minX); buffer.writeInt(message.minY); buffer.writeInt(message.minZ);
            buffer.writeInt(message.maxX); buffer.writeInt(message.maxY); buffer.writeInt(message.maxZ);
            buffer.writeVarInt(message.placeCount); buffer.writeVarInt(message.reuseCount);
            buffer.writeVarInt(message.clearCount); buffer.writeVarInt(message.protectedCount);
            buffer.writeVarInt(message.containerCount); buffer.writeVarInt(message.unknownCount);
            buffer.writeVarInt(message.hazardCount);
            buffer.writeUtf(message.requiredInputs, MAX_SUMMARY_TEXT);
            buffer.writeVarInt(message.recommendedBots);
            buffer.writeUtf(message.phaseEstimate, 32); buffer.writeUtf(message.riskLevel, 32);
            buffer.writeBoolean(message.approvable);
            buffer.writeUtf(message.safetyPolicy, 96);
        }

        public static ConfirmationSummaryS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            if (!success) return failure(status);
            return new ConfirmationSummaryS2C(true, status, buffer.readUtf(36), buffer.readLong(),
                    buffer.readUtf(MAX_TEXT), buffer.readVarInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readEnum(QuarterTurn.class),
                    buffer.readEnum(LayoutVariant.class), buffer.readEnum(PlayerExecutionMode.class),
                    buffer.readUtf(64), buffer.readUtf(64), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readUtf(MAX_SUMMARY_TEXT), buffer.readVarInt(), buffer.readUtf(32),
                    buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(96));
        }

        public static void handle(
                ConfirmationSummaryS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.confirmationSummary(message)));
            context.setPacketHandled(true);
        }
    }

    public record ApproveProjectC2S(
            String projectId,
            long projectNonce,
            String expectedPlanHash,
            String expectedSnapshotHash) {
        public static void encode(ApproveProjectC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36);
            buffer.writeLong(message.projectNonce);
            buffer.writeUtf(message.expectedPlanHash, 64);
            buffer.writeUtf(message.expectedSnapshotHash, 64);
        }

        public static ApproveProjectC2S decode(FriendlyByteBuf buffer) {
            return new ApproveProjectC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readUtf(64), buffer.readUtf(64));
        }

        public static void handle(
                ApproveProjectC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rateFailure = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rateFailure != null) {
                    PlayerWorkflowNetwork.sendApproval(sender, ApprovalResultS2C.failure(rateFailure));
                    return;
                }
                try {
                    var result = PlayerApprovalService.approve(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce,
                            message.expectedPlanHash, message.expectedSnapshotHash);
                    PlayerWorkflowNetwork.sendApproval(sender, ApprovalResultS2C.from(result));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendApproval(sender,
                            ApprovalResultS2C.failure("APPROVAL_REQUEST_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ApprovalResultS2C(
            boolean success,
            String statusCode,
            String tokenIdentity,
            long expiresAtMillis,
            ProjectWire project) {
        static ApprovalResultS2C from(PlayerApprovalService.ApprovalResult result) {
            return result.success()
                    ? new ApprovalResultS2C(true, "OK", result.token().tokenIdentity(),
                            result.token().expiresAt().toEpochMilli(), ProjectWire.from(result.project()))
                    : failure(result.statusCode());
        }

        static ApprovalResultS2C failure(String code) {
            return new ApprovalResultS2C(false, code, "", 0, null);
        }

        public static void encode(ApprovalResultS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.statusCode, 96);
            if (!message.success) return;
            buffer.writeUtf(message.tokenIdentity, MAX_TEXT);
            buffer.writeLong(message.expiresAtMillis);
            message.project.encode(buffer);
        }

        public static ApprovalResultS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            if (!success) return failure(status);
            return new ApprovalResultS2C(true, status, buffer.readUtf(MAX_TEXT),
                    buffer.readLong(), ProjectWire.decode(buffer));
        }

        public static void handle(
                ApprovalResultS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.approvalResult(message)));
            context.setPacketHandled(true);
        }
    }

    public record BindSalvageC2S(
            String projectId, long projectNonce, int x, int y, int z) {
        public static void encode(BindSalvageC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36); buffer.writeLong(message.projectNonce);
            buffer.writeInt(message.x); buffer.writeInt(message.y); buffer.writeInt(message.z);
        }

        public static BindSalvageC2S decode(FriendlyByteBuf buffer) {
            return new BindSalvageC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt());
        }

        public static void handle(
                BindSalvageC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerClearingService.bindSalvage(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce,
                            new BlockPos3i(message.x, message.y, message.z));
                    PlayerWorkflowNetwork.sendSalvageResult(sender,
                            SalvageResultS2C.from(result));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendSalvageResult(sender,
                            SalvageResultS2C.failure("SALVAGE_REQUEST_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SalvageResultS2C(
            boolean success, String statusCode, ProjectWire project) {
        static SalvageResultS2C from(PlayerClearingService.Result result) {
            return result.success()
                    ? new SalvageResultS2C(true, "OK", ProjectWire.from(result.project()))
                    : failure(result.statusCode());
        }

        static SalvageResultS2C failure(String code) {
            return new SalvageResultS2C(false, code, null);
        }

        public static void encode(SalvageResultS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success); buffer.writeUtf(message.statusCode, 96);
            if (message.success) message.project.encode(buffer);
        }

        public static SalvageResultS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            return success ? new SalvageResultS2C(true, status, ProjectWire.decode(buffer))
                    : failure(status);
        }

        public static void handle(
                SalvageResultS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.salvageResult(message)));
            context.setPacketHandled(true);
        }
    }

    public record StartClearingC2S(String projectId, long projectNonce) {
        public static void encode(StartClearingC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36); buffer.writeLong(message.projectNonce);
        }

        public static StartClearingC2S decode(FriendlyByteBuf buffer) {
            return new StartClearingC2S(buffer.readUtf(36), buffer.readLong());
        }

        public static void handle(
                StartClearingC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerClearingService.start(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce);
                    PlayerWorkflowNetwork.sendClearingStatus(sender,
                            ClearingStatusS2C.from(result));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendClearingStatus(sender,
                            ClearingStatusS2C.failure("CLEARING_REQUEST_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ClearingControlC2S(
            String projectId,
            long projectNonce,
            PlayerClearingService.Action action) {
        public static void encode(ClearingControlC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36); buffer.writeLong(message.projectNonce);
            buffer.writeEnum(message.action);
        }

        public static ClearingControlC2S decode(FriendlyByteBuf buffer) {
            return new ClearingControlC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readEnum(PlayerClearingService.Action.class));
        }

        public static void handle(
                ClearingControlC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                try {
                    var result = PlayerClearingService.control(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce,
                            message.action);
                    PlayerWorkflowNetwork.sendClearingStatus(sender,
                            ClearingStatusS2C.from(result));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendClearingStatus(sender,
                            ClearingStatusS2C.failure("CLEARING_CONTROL_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record ClearingStatusS2C(
            boolean success,
            String statusCode,
            ProjectWire project,
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
            boolean safeToCancel) {
        static ClearingStatusS2C from(PlayerClearingService.Result result) {
            return result.success()
                    ? from(result.project(), result.snapshot())
                    : failure(result.statusCode());
        }

        public static ClearingStatusS2C from(
                PlayerWorkflowSavedData.ProjectEntry project,
                SitePreparationCommand.PlayerClearingSnapshot snapshot) {
            return new ClearingStatusS2C(true, snapshot.statusCode(), ProjectWire.from(project),
                    snapshot.active(), snapshot.paused(), snapshot.prepared(), snapshot.phase(),
                    snapshot.completedTargets(), snapshot.totalTargets(), snapshot.mutations(),
                    snapshot.salvageCollected(), snapshot.salvageDelivered(),
                    snapshot.activeBots(), snapshot.safeToCancel());
        }

        static ClearingStatusS2C failure(String code) {
            return new ClearingStatusS2C(false, code, null, false, false, false, "IDLE",
                    0, 0, 0, 0, 0, 0, false);
        }

        public static void encode(ClearingStatusS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success); buffer.writeUtf(message.statusCode, 96);
            if (!message.success) return;
            message.project.encode(buffer);
            buffer.writeBoolean(message.active); buffer.writeBoolean(message.paused);
            buffer.writeBoolean(message.prepared); buffer.writeUtf(message.phase, 48);
            buffer.writeVarInt(message.completedTargets); buffer.writeVarInt(message.totalTargets);
            buffer.writeVarInt(message.mutations); buffer.writeVarInt(message.salvageCollected);
            buffer.writeVarInt(message.salvageDelivered); buffer.writeVarInt(message.activeBots);
            buffer.writeBoolean(message.safeToCancel);
        }

        public static ClearingStatusS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String status = buffer.readUtf(96);
            if (!success) return failure(status);
            return new ClearingStatusS2C(true, status, ProjectWire.decode(buffer),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readUtf(48), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readBoolean());
        }

        public static void handle(
                ClearingStatusS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT, () -> () -> PlayerWorkflowClient.clearingStatus(message)));
            context.setPacketHandled(true);
        }
    }

    public record BindMaterialSourceC2S(
            String projectId, long projectNonce, int x, int y, int z, Direction face) {
        public static void encode(BindMaterialSourceC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36); buffer.writeLong(message.projectNonce);
            buffer.writeInt(message.x); buffer.writeInt(message.y); buffer.writeInt(message.z);
            buffer.writeEnum(message.face);
        }

        public static BindMaterialSourceC2S decode(FriendlyByteBuf buffer) {
            return new BindMaterialSourceC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readEnum(Direction.class));
        }

        public static void handle(BindMaterialSourceC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rate = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rate != null) {
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender, MaterialSnapshotS2C.failure(rate));
                    return;
                }
                try {
                    var result = PlayerMaterialService.selectSource(sender,
                            java.util.UUID.fromString(message.projectId), message.projectNonce,
                            new BlockPos3i(message.x, message.y, message.z), message.face);
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender,
                            MaterialSnapshotS2C.from(result,
                                    PlayerWorkflowSavedData.forLevel(sender.serverLevel())
                                            .entry(sender.getUUID()).orElse(null)));
                } catch (IllegalArgumentException invalid) {
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender,
                            MaterialSnapshotS2C.failure("MATERIAL_SOURCE_REQUEST_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public enum MaterialAction { SNAPSHOT, RESET, CONFIRM, START, CANCEL }

    public record MaterialControlC2S(
            String projectId, long projectNonce, MaterialAction action, boolean allowSalvage) {
        public static void encode(MaterialControlC2S message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.projectId, 36); buffer.writeLong(message.projectNonce);
            buffer.writeEnum(message.action); buffer.writeBoolean(message.allowSalvage);
        }

        public static MaterialControlC2S decode(FriendlyByteBuf buffer) {
            return new MaterialControlC2S(buffer.readUtf(36), buffer.readLong(),
                    buffer.readEnum(MaterialAction.class), buffer.readBoolean());
        }

        public static void handle(MaterialControlC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rate = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rate != null) {
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender, MaterialSnapshotS2C.failure(rate));
                    return;
                }
                try {
                    UUID projectId = UUID.fromString(message.projectId);
                    PlayerMaterialService.SelectionResult result = switch (message.action) {
                        case SNAPSHOT -> PlayerMaterialService.snapshot(sender, projectId);
                        case RESET -> PlayerMaterialService.resetSources(
                                sender, projectId, message.projectNonce);
                        case CONFIRM -> PlayerMaterialService.confirm(
                                sender, projectId, message.projectNonce, message.allowSalvage);
                        case START -> {
                            var started = dev.stevecreate.agent.forge1201.command.PlayerConstructionService
                                    .start(sender, projectId, message.projectNonce);
                            if (!started.success()) yield PlayerMaterialService.SelectionResult
                                    .failure(started.statusCode());
                            yield PlayerMaterialService.snapshot(sender, projectId);
                        }
                        case CANCEL -> PlayerMaterialService.cancel(
                                sender, projectId, message.projectNonce);
                    };
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender,
                            MaterialSnapshotS2C.from(result,
                                    PlayerWorkflowSavedData.forLevel(sender.serverLevel())
                                            .entry(sender.getUUID()).orElse(null)));
                } catch (IllegalArgumentException invalid) {
                    LOGGER.warn("MATERIAL_CONTROL_INVALID player={} project={} nonce={} action={} reason={}",
                            sender.getUUID(), message.projectId(), message.projectNonce,
                            message.action, invalid.toString(), invalid);
                    PlayerWorkflowNetwork.sendMaterialSnapshot(sender,
                            MaterialSnapshotS2C.failure("MATERIAL_CONTROL_INVALID"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record MaterialLineWire(String item, long required, long available) {
        public MaterialLineWire {
            if (item == null || item.isBlank() || item.length() > MAX_TEXT
                    || required < 0 || available < 0) {
                throw new IllegalArgumentException("invalid material line");
            }
        }
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(item, MAX_TEXT); buffer.writeLong(required); buffer.writeLong(available);
        }
        static MaterialLineWire decode(FriendlyByteBuf buffer) {
            return new MaterialLineWire(buffer.readUtf(MAX_TEXT), buffer.readLong(), buffer.readLong());
        }
    }

    public record CompletionReportWire(long planned, long withdrawn, long consumed, long returned,
            long salvageTransferred, long observedOutput, long expectedOutput,
            long duplicateWithdrawals, long duplicateReturns, long unaccountedItems,
            long privateItemsTouched, boolean balanced) {
        public CompletionReportWire {
            if (planned < 0 || withdrawn < 0 || consumed < 0 || returned < 0
                    || salvageTransferred < 0 || observedOutput < 0 || expectedOutput < 0
                    || duplicateWithdrawals < 0 || duplicateReturns < 0 || unaccountedItems < 0
                    || privateItemsTouched < 0) {
                throw new IllegalArgumentException("invalid completion report wire");
            }
        }
        static CompletionReportWire from(
                dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.CompletionReport report) {
            return report == null ? null : new CompletionReportWire(report.planned(), report.withdrawn(),
                    report.consumed(), report.returned(), report.salvageTransferred(),
                    report.observedOutput(), report.expectedOutput(), report.duplicateWithdrawals(),
                    report.duplicateReturns(), report.unaccountedItems(), report.privateItemsTouched(),
                    report.balanced());
        }
        void encode(FriendlyByteBuf buffer) {
            buffer.writeLong(planned); buffer.writeLong(withdrawn); buffer.writeLong(consumed);
            buffer.writeLong(returned); buffer.writeLong(salvageTransferred);
            buffer.writeLong(observedOutput); buffer.writeLong(expectedOutput);
            buffer.writeLong(duplicateWithdrawals); buffer.writeLong(duplicateReturns);
            buffer.writeLong(unaccountedItems); buffer.writeLong(privateItemsTouched);
            buffer.writeBoolean(balanced);
        }
        static CompletionReportWire decode(FriendlyByteBuf buffer) {
            return new CompletionReportWire(buffer.readLong(), buffer.readLong(), buffer.readLong(),
                    buffer.readLong(), buffer.readLong(), buffer.readLong(), buffer.readLong(),
                    buffer.readLong(), buffer.readLong(), buffer.readLong(), buffer.readLong(),
                    buffer.readBoolean());
        }
    }

    public record MaterialSnapshotS2C(boolean success, String statusCode, ProjectWire project,
            int sourceCount, long reserved, boolean sufficient, boolean allowSalvage,
            List<MaterialLineWire> lines, CompletionReportWire report, String detail) {
        public MaterialSnapshotS2C {
            if (statusCode == null || statusCode.isBlank() || statusCode.length() > 96
                    || sourceCount < 0 || sourceCount > PlayerMaterialService.MAX_SOURCES
                    || reserved < 0 || lines == null || lines.size() > MAX_MATERIAL_LINES
                    || detail == null || detail.isBlank() || detail.length() > MAX_SUMMARY_TEXT
                    || (success && project == null) || (!success && project != null)) {
                throw new IllegalArgumentException("invalid material snapshot wire");
            }
            lines = List.copyOf(lines);
        }
        static MaterialSnapshotS2C from(PlayerMaterialService.SelectionResult result,
                PlayerWorkflowSavedData.ProjectEntry fallback) {
            if (!result.success() || result.summary() == null) {
                return failure(result.statusCode(), result.detail());
            }
            PlayerWorkflowSavedData.ProjectEntry project = result.project() == null
                    ? fallback : result.project();
            List<MaterialLineWire> lines = result.summary().required().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Object::toString)))
                    .map(value -> new MaterialLineWire(value.getKey().toString(), value.getValue(),
                            result.summary().available().getOrDefault(value.getKey(), 0L))).toList();
            return new MaterialSnapshotS2C(true, result.summary().statusCode(), ProjectWire.from(project),
                    result.summary().sourceCount(), result.summary().reserved(),
                    result.summary().sufficient(), result.summary().allowSalvage(), lines,
                    CompletionReportWire.from(result.summary().report()), result.detail());
        }

        static MaterialSnapshotS2C failure(String code) {
            return failure(code, code);
        }

        static MaterialSnapshotS2C failure(String code, String detail) {
            return new MaterialSnapshotS2C(false, code, null, 0, 0,
                    false, false, List.of(), null, detail == null || detail.isBlank() ? code : detail);
        }

        public static void encode(MaterialSnapshotS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success); buffer.writeUtf(message.statusCode, 96);
            buffer.writeUtf(message.detail, MAX_SUMMARY_TEXT);
            if (!message.success) return;
            message.project.encode(buffer); buffer.writeVarInt(message.sourceCount);
            buffer.writeLong(message.reserved); buffer.writeBoolean(message.sufficient);
            buffer.writeBoolean(message.allowSalvage);
            if (message.lines.size() > MAX_MATERIAL_LINES) throw new IllegalArgumentException("material packet too large");
            buffer.writeVarInt(message.lines.size()); message.lines.forEach(value -> value.encode(buffer));
            buffer.writeBoolean(message.report != null); if (message.report != null) message.report.encode(buffer);
        }

        public static MaterialSnapshotS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean(); String status = buffer.readUtf(96);
            String detail = buffer.readUtf(MAX_SUMMARY_TEXT);
            if (!success) return failure(status, detail);
            ProjectWire project = ProjectWire.decode(buffer); int sources = buffer.readVarInt();
            long reserved = buffer.readLong(); boolean sufficient = buffer.readBoolean();
            boolean salvage = buffer.readBoolean(); int size = buffer.readVarInt();
            if (size < 0 || size > MAX_MATERIAL_LINES) throw new IllegalArgumentException("invalid material line count");
            java.util.ArrayList<MaterialLineWire> lines = new java.util.ArrayList<>(size);
            for (int index = 0; index < size; index++) lines.add(MaterialLineWire.decode(buffer));
            CompletionReportWire report = buffer.readBoolean() ? CompletionReportWire.decode(buffer) : null;
            return new MaterialSnapshotS2C(true, status, project, sources, reserved,
                    sufficient, salvage, lines, report, detail);
        }

        public static void handle(MaterialSnapshotS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PlayerWorkflowClient.materialSnapshot(message)));
            context.setPacketHandled(true);
        }
    }

    public enum MetalPressAction { REFRESH, CANCEL }

    public record MetalPressControlC2S(MetalPressAction action) {
        public static void encode(MetalPressControlC2S message, FriendlyByteBuf buffer) {
            buffer.writeEnum(message.action);
        }

        public static MetalPressControlC2S decode(FriendlyByteBuf buffer) {
            return new MetalPressControlC2S(buffer.readEnum(MetalPressAction.class));
        }

        public static void handle(MetalPressControlC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rate = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rate != null) {
                    MetalPressOrderNetwork.sendStatus(sender,
                            MetalPressStatusS2C.failure(rate));
                    return;
                }
                String operation = "STATUS_REFRESHED";
                if (message.action == MetalPressAction.CANCEL) {
                    operation = MetalPressProductionService.cancel(sender).code();
                }
                MetalPressOrderNetwork.sendStatus(sender,
                        MetalPressStatusS2C.from(sender, operation));
            });
            context.setPacketHandled(true);
        }
    }

    /** Bounded, presentation-only projection of server-authoritative order and ledger evidence. */
    public record MetalPressStatusS2C(
            boolean success, String operationCode, String orderId, String stage, String statusCode,
            int progress, int sourceX, int sourceY, int sourceZ,
            int originX, int originY, int originZ,
            long planned, long withdrawn, long consumed, long returned,
            long energyConsumed, long outputCount, boolean paused, boolean safeToCancel,
            boolean reportPresent, int duplicateWithdrawals, int duplicateEnergy,
            int duplicateOutputs, int duplicateReturns, long unaccountedItems,
            long privateItemsTouched, boolean ledgerBalanced, boolean baselineRestored) {
        public MetalPressStatusS2C {
            if (operationCode == null || operationCode.isBlank()
                    || operationCode.length() > MAX_TEXT || progress < 0 || progress > 100
                    || planned < 0 || withdrawn < 0 || consumed < 0 || returned < 0
                    || energyConsumed < 0 || outputCount < 0 || duplicateWithdrawals < 0
                    || duplicateEnergy < 0 || duplicateOutputs < 0 || duplicateReturns < 0
                    || unaccountedItems < 0 || privateItemsTouched < 0
                    || (success && (orderId == null || orderId.length() != 36
                            || stage == null || stage.isBlank() || stage.length() > 48
                            || statusCode == null || statusCode.isBlank()
                            || statusCode.length() > MAX_SUMMARY_TEXT))) {
                throw new IllegalArgumentException("invalid Metal Press status packet");
            }
        }

        static MetalPressStatusS2C from(ServerPlayer player, String operationCode) {
            var result = MetalPressProductionService.status(player);
            if (!result.success() || result.order() == null) {
                return failure(operationCode.equals("STATUS_OPENED")
                        ? result.code() : operationCode);
            }
            var order = result.order();
            var material = result.material();
            long planned = material == null ? 0 : material.requirements().values().stream()
                    .mapToLong(Long::longValue).sum();
            long withdrawn = material == null ? 0 : material.transactions().stream()
                    .filter(value -> value.state()
                            != dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.PREPARED
                            && value.state()
                            != dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.RELEASED)
                    .mapToLong(value -> value.quantity()).sum();
            long consumed = material == null ? 0 : material.transactions().stream()
                    .filter(value -> value.state()
                            == dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.CONSUMED)
                    .mapToLong(value -> value.quantity()).sum();
            long returned = material == null ? 0 : material.transactions().stream()
                    .filter(value -> value.state()
                            == dev.stevecreate.agent.core.execution.construction.MaterialTransactionState.RETURNED)
                    .mapToLong(value -> value.quantity()).sum();
            var report = order.report().orElse(null);
            return new MetalPressStatusS2C(true, operationCode, order.orderId().toString(),
                    order.stage().name(), order.statusCode(), metalPressProgress(order.stage()),
                    order.materialSource().x(), order.materialSource().y(), order.materialSource().z(),
                    order.machineOrigin().x(), order.machineOrigin().y(), order.machineOrigin().z(),
                    planned, withdrawn, consumed, returned, order.measuredEnergyConsumedFe(),
                    order.exactOutputCount(),
                    order.stage() == dev.stevecreate.agent.core.industrial.MetalPressOrderStage.PAUSED,
                    !order.durableEffects().contains(
                            dev.stevecreate.agent.core.industrial.MetalPressDurableEffect.INPUT_ADMISSION)
                            && order.stage()
                            != dev.stevecreate.agent.core.industrial.MetalPressOrderStage.COMPLETED
                            && order.stage()
                            != dev.stevecreate.agent.core.industrial.MetalPressOrderStage.CANCELLED,
                    report != null,
                    report == null ? 0 : report.duplicateWithdrawals(),
                    report == null ? 0 : report.duplicateEnergySettlements(),
                    report == null ? 0 : report.duplicateOutputs(),
                    report == null ? 0 : report.duplicateReturns(),
                    report == null ? 0 : report.unaccountedItems(),
                    report == null ? 0 : report.privateItemsTouched(),
                    report != null && report.materialLedgerBalanced(),
                    report != null && report.baselineRestored());
        }

        static MetalPressStatusS2C failure(String code) {
            return new MetalPressStatusS2C(false, code, "", "", "", 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false, false, 0, 0, 0, 0, 0, 0, false, false);
        }

        public static void encode(MetalPressStatusS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success); buffer.writeUtf(message.operationCode, MAX_TEXT);
            if (!message.success) return;
            buffer.writeUtf(message.orderId, 36); buffer.writeUtf(message.stage, 48);
            buffer.writeUtf(message.statusCode, MAX_SUMMARY_TEXT); buffer.writeVarInt(message.progress);
            buffer.writeInt(message.sourceX); buffer.writeInt(message.sourceY); buffer.writeInt(message.sourceZ);
            buffer.writeInt(message.originX); buffer.writeInt(message.originY); buffer.writeInt(message.originZ);
            buffer.writeLong(message.planned); buffer.writeLong(message.withdrawn);
            buffer.writeLong(message.consumed); buffer.writeLong(message.returned);
            buffer.writeLong(message.energyConsumed); buffer.writeLong(message.outputCount);
            buffer.writeBoolean(message.paused); buffer.writeBoolean(message.safeToCancel);
            buffer.writeBoolean(message.reportPresent); buffer.writeVarInt(message.duplicateWithdrawals);
            buffer.writeVarInt(message.duplicateEnergy); buffer.writeVarInt(message.duplicateOutputs);
            buffer.writeVarInt(message.duplicateReturns); buffer.writeLong(message.unaccountedItems);
            buffer.writeLong(message.privateItemsTouched); buffer.writeBoolean(message.ledgerBalanced);
            buffer.writeBoolean(message.baselineRestored);
        }

        public static MetalPressStatusS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean(); String operation = buffer.readUtf(MAX_TEXT);
            if (!success) return failure(operation);
            return new MetalPressStatusS2C(true, operation, buffer.readUtf(36), buffer.readUtf(48),
                    buffer.readUtf(MAX_SUMMARY_TEXT), buffer.readVarInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readLong(), buffer.readLong(), buffer.readLong(), buffer.readLong(),
                    buffer.readLong(), buffer.readLong(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readLong(), buffer.readLong(), buffer.readBoolean(),
                    buffer.readBoolean());
        }

        public static void handle(MetalPressStatusS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PlayerWorkflowClient.metalPressStatus(message)));
            context.setPacketHandled(true);
        }

        private static int metalPressProgress(
                dev.stevecreate.agent.core.industrial.MetalPressOrderStage stage) {
            return switch (stage) {
                case MATERIAL_SOURCE_SELECTION -> 2;
                case MATERIALS_RESERVED -> 8;
                case MATERIALS_WITHDRAWN -> 14;
                case MATERIALS_DELIVERED -> 20;
                case STRUCTURE_BUILT -> 30;
                case MULTIBLOCK_FORMED -> 38;
                case MOLD_INSTALLED -> 45;
                case POWER_NETWORK_BUILT -> 55;
                case POWER_VERIFIED -> 65;
                case INPUT_QUEUED, PROCESSING -> 75;
                case ENERGY_SETTLED -> 82;
                case OUTPUT_OBSERVED -> 88;
                case TEARDOWN -> 91;
                case BASELINE_RESTORED -> 94;
                case MATERIALS_RETURNED -> 97;
                case REPORT_GENERATED -> 99;
                case COMPLETED -> 100;
                case PAUSED, CANCELLED -> 0;
            };
        }
    }
}
