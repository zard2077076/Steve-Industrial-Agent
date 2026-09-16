package dev.stevecreate.agent.forge1201.player.net;

import dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.player.client.PlayerWorkflowClient;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Bounded wire projection for a player-owned Composite order. */
public final class CompositeOrderPackets {
    public static final int MAX_TEXT = 256;
    public static final int MAX_NODES = 64;
    public static final int MAX_BUFFERS = 128;
    public static final int MAX_REPORT_ROWS = 512;

    private CompositeOrderPackets() {}

    public enum CompositeAction { REFRESH, CANCEL }

    public record CompositeControlC2S(CompositeAction action) {
        public CompositeControlC2S {
            Objects.requireNonNull(action, "action");
        }

        public static void encode(CompositeControlC2S message, FriendlyByteBuf buffer) {
            buffer.writeEnum(message.action);
        }

        public static CompositeControlC2S decode(FriendlyByteBuf buffer) {
            return new CompositeControlC2S(buffer.readEnum(CompositeAction.class));
        }

        public static void handle(CompositeControlC2S message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender == null) return;
                String rate = PlayerWorkflowNetwork.validateProjectTraffic(sender);
                if (rate != null) {
                    CompositeOrderNetwork.sendStatus(sender, CompositeStatusS2C.failure(rate));
                    return;
                }
                String operation = "STATUS_REFRESHED";
                if (message.action == CompositeAction.CANCEL) {
                    operation = PlayerCompositeOrderService.cancel(sender).code();
                }
                CompositeOrderNetwork.sendStatus(sender,
                        CompositeStatusS2C.from(sender, operation));
            });
            context.setPacketHandled(true);
        }
    }

    public record NodeWire(String nodeId, String status) {
        public NodeWire {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(status, "status");
            if (nodeId.length() > MAX_TEXT || status.length() > 48) {
                throw new IllegalArgumentException("Composite node wire too large");
            }
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(nodeId, MAX_TEXT);
            buffer.writeUtf(status, 48);
        }

        static NodeWire decode(FriendlyByteBuf buffer) {
            return new NodeWire(buffer.readUtf(MAX_TEXT), buffer.readUtf(48));
        }
    }

    public record BufferWire(String edgeId, String resource, long quantity) {
        public BufferWire {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(resource, "resource");
            if (edgeId.length() > MAX_TEXT || resource.length() > MAX_TEXT || quantity < 0) {
                throw new IllegalArgumentException("Invalid Composite buffer wire");
            }
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(edgeId, MAX_TEXT);
            buffer.writeUtf(resource, MAX_TEXT);
            buffer.writeVarLong(quantity);
        }

        static BufferWire decode(FriendlyByteBuf buffer) {
            return new BufferWire(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                    buffer.readVarLong());
        }
    }

    /** One exact resource row from the durable Composite settlement. */
    public record CompletionRowWire(
            String resource,
            long planned,
            long withdrawn,
            long consumed,
            long returned,
            long output,
            long energy,
            long fluid) {
        public CompletionRowWire {
            Objects.requireNonNull(resource, "resource");
            if (resource.isBlank() || resource.length() > MAX_TEXT
                    || planned < 0 || withdrawn < 0 || consumed < 0 || returned < 0
                    || output < 0 || energy < 0 || fluid < 0) {
                throw new IllegalArgumentException("Invalid Composite completion row");
            }
        }

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(resource, MAX_TEXT);
            buffer.writeVarLong(planned); buffer.writeVarLong(withdrawn);
            buffer.writeVarLong(consumed); buffer.writeVarLong(returned);
            buffer.writeVarLong(output); buffer.writeVarLong(energy); buffer.writeVarLong(fluid);
        }

        static CompletionRowWire decode(FriendlyByteBuf buffer) {
            return new CompletionRowWire(buffer.readUtf(MAX_TEXT), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong());
        }
    }

    /**
     * Immutable, server-authored settlement evidence.  It is separate from the live
     * status packet because a completed wrapper no longer has an executor snapshot to
     * poll, while its durable report must remain visible after a restart.
     */
    public record CompositeCompletionS2C(
            boolean success,
            String operationCode,
            String projectId,
            String orderType,
            String target,
            String stage,
            long generation,
            List<CompletionRowWire> rows,
            long salvageTransferred,
            long duplicateWithdrawals,
            long duplicateReturns,
            long duplicateEnergySettlements,
            long duplicateOutputs,
            long unaccountedItems,
            long privateItemsTouched,
            boolean materialLedgerBalanced,
            boolean baselineRestored,
            String evidenceHash) {
        public CompositeCompletionS2C {
            Objects.requireNonNull(operationCode, "operationCode");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(orderType, "orderType");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(evidenceHash, "evidenceHash");
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            if (operationCode.length() > MAX_TEXT || projectId.length() > 36
                    || orderType.length() > MAX_TEXT || target.length() > MAX_TEXT
                    || stage.length() > MAX_TEXT || evidenceHash.length() > 64
                    || generation < 0 || rows.size() > MAX_REPORT_ROWS
                    || salvageTransferred < 0 || duplicateWithdrawals < 0
                    || duplicateReturns < 0 || duplicateEnergySettlements < 0
                    || duplicateOutputs < 0 || unaccountedItems < 0
                    || privateItemsTouched < 0) {
                throw new IllegalArgumentException("Invalid Composite completion packet");
            }
            if (success && (!projectId.matches("[0-9a-f-]{36}")
                    || !evidenceHash.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Composite completion identity is invalid");
            }
        }

        public static CompositeCompletionS2C from(ServerPlayer player, String operationCode) {
            PlayerCompositeOrderService.CompletionResult result =
                    PlayerCompositeOrderService.completion(player);
            if (!result.success() || result.order() == null || result.report() == null) {
                return failure(operationCode.equals("COMPLETED")
                        ? result.code() : operationCode);
            }
            var order = result.order();
            var report = result.report();
            Set<ResourceId> resources = new LinkedHashSet<>();
            resources.addAll(report.plannedMaterials().keySet());
            resources.addAll(report.withdrawnMaterials().keySet());
            resources.addAll(report.consumedMaterials().keySet());
            resources.addAll(report.returnedMaterials().keySet());
            resources.addAll(report.outputs().keySet());
            resources.addAll(report.energyConsumed().keySet());
            resources.addAll(report.fluidConsumed().keySet());
            if (resources.size() > MAX_REPORT_ROWS) {
                return failure("COMPOSITE_COMPLETION_REPORT_TOO_LARGE");
            }
            List<CompletionRowWire> rows = resources.stream()
                    .sorted(Comparator.comparing(ResourceId::toString))
                    .map(resource -> new CompletionRowWire(resource.toString(),
                            report.plannedMaterials().getOrDefault(resource, 0L),
                            report.withdrawnMaterials().getOrDefault(resource, 0L),
                            report.consumedMaterials().getOrDefault(resource, 0L),
                            report.returnedMaterials().getOrDefault(resource, 0L),
                            report.outputs().getOrDefault(resource, 0L),
                            report.energyConsumed().getOrDefault(resource, 0L),
                            report.fluidConsumed().getOrDefault(resource, 0L)))
                    .toList();
            return new CompositeCompletionS2C(true, operationCode, order.orderId().toString(),
                    order.orderType().toString(), order.target().toString(), order.stage(),
                    order.generation(), rows, report.salvageTransferred(),
                    report.duplicateWithdrawals(), report.duplicateReturns(),
                    report.duplicateEnergySettlements(), report.duplicateOutputs(),
                    report.unaccountedItems(), report.privateItemsTouched(),
                    report.materialLedgerBalanced(), report.baselineRestored(), report.evidenceHash());
        }

        static CompositeCompletionS2C failure(String code) {
            return new CompositeCompletionS2C(false,
                    code == null ? "COMPOSITE_COMPLETION_FAILED" : code,
                    "", "", "", "", 0, List.of(), 0, 0, 0, 0, 0, 0, 0,
                    false, false, "");
        }

        public static void encode(CompositeCompletionS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success); buffer.writeUtf(message.operationCode, MAX_TEXT);
            if (!message.success) return;
            buffer.writeUtf(message.projectId, 36); buffer.writeUtf(message.orderType, MAX_TEXT);
            buffer.writeUtf(message.target, MAX_TEXT); buffer.writeUtf(message.stage, MAX_TEXT);
            buffer.writeVarLong(message.generation); buffer.writeVarInt(message.rows.size());
            message.rows.forEach(value -> value.encode(buffer));
            buffer.writeVarLong(message.salvageTransferred);
            buffer.writeVarLong(message.duplicateWithdrawals); buffer.writeVarLong(message.duplicateReturns);
            buffer.writeVarLong(message.duplicateEnergySettlements); buffer.writeVarLong(message.duplicateOutputs);
            buffer.writeVarLong(message.unaccountedItems); buffer.writeVarLong(message.privateItemsTouched);
            buffer.writeBoolean(message.materialLedgerBalanced); buffer.writeBoolean(message.baselineRestored);
            buffer.writeUtf(message.evidenceHash, 64);
        }

        public static CompositeCompletionS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean(); String operation = buffer.readUtf(MAX_TEXT);
            if (!success) return failure(operation);
            String project = buffer.readUtf(36); String orderType = buffer.readUtf(MAX_TEXT);
            String target = buffer.readUtf(MAX_TEXT); String stage = buffer.readUtf(MAX_TEXT);
            long generation = buffer.readVarLong(); int size = buffer.readVarInt();
            if (size < 0 || size > MAX_REPORT_ROWS) {
                throw new IllegalArgumentException("Invalid Composite completion row count");
            }
            ArrayList<CompletionRowWire> rows = new ArrayList<>(size);
            for (int index = 0; index < size; index++) rows.add(CompletionRowWire.decode(buffer));
            return new CompositeCompletionS2C(true, operation, project, orderType, target, stage,
                    generation, rows, buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(64));
        }

        public static void handle(CompositeCompletionS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PlayerWorkflowClient.compositeCompletion(message)));
            context.setPacketHandled(true);
        }
    }

    /**
     * Snapshot-only packet. It deliberately carries no executor handle, material source,
     * or server coordinates: the screen is a status view, while all authority remains in
     * the player order service and its durable envelope.
     */
    public record CompositeStatusS2C(
            boolean success,
            String operationCode,
            String projectId,
            String graphId,
            String graphFingerprint,
            long generation,
            List<NodeWire> nodes,
            List<BufferWire> buffers) {
        public CompositeStatusS2C {
            Objects.requireNonNull(operationCode, "operationCode");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(graphFingerprint, "graphFingerprint");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            buffers = List.copyOf(Objects.requireNonNull(buffers, "buffers"));
            if (operationCode.length() > MAX_TEXT || projectId.length() > 36
                    || graphId.length() > MAX_TEXT || graphFingerprint.length() > 64
                    || generation < 0 || nodes.size() > MAX_NODES || buffers.size() > MAX_BUFFERS) {
                throw new IllegalArgumentException("Invalid Composite status packet");
            }
            if (success && !projectId.matches("[0-9a-f-]{36}")) {
                throw new IllegalArgumentException("Composite project id must be UUID text");
            }
        }

        static CompositeStatusS2C from(ServerPlayer player, String operationCode) {
            PlayerCompositeOrderService.StatusResult result =
                    PlayerCompositeOrderService.status(player);
            if (!result.success() || result.snapshot() == null) {
                return failure(operationCode.equals("STATUS_OPENED")
                        ? result.code() : operationCode);
            }
            CompositeProductionSnapshot snapshot = result.snapshot();
            List<NodeWire> nodes = snapshot.nodeStatuses().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                    .limit(MAX_NODES)
                    .map(entry -> new NodeWire(entry.getKey().toString(), entry.getValue().name()))
                    .toList();
            List<BufferWire> buffers = snapshot.bufferQuantities().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                    .limit(MAX_BUFFERS)
                    .map(entry -> new BufferWire(entry.getKey().toString(),
                            snapshot.bufferResources().get(entry.getKey()).toString(), entry.getValue()))
                    .toList();
            return new CompositeStatusS2C(true, operationCode, result.projectId().toString(),
                    snapshot.graphId().toString(), snapshot.graphFingerprint(), snapshot.generation(),
                    nodes, buffers);
        }

        static CompositeStatusS2C failure(String code) {
            return new CompositeStatusS2C(false, code == null ? "COMPOSITE_STATUS_FAILED" : code,
                    "", "", "", 0, List.of(), List.of());
        }

        public static void encode(CompositeStatusS2C message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.operationCode, MAX_TEXT);
            if (!message.success) return;
            buffer.writeUtf(message.projectId, 36);
            buffer.writeUtf(message.graphId, MAX_TEXT);
            buffer.writeUtf(message.graphFingerprint, 64);
            buffer.writeVarLong(message.generation);
            buffer.writeVarInt(message.nodes.size());
            message.nodes.forEach(value -> value.encode(buffer));
            buffer.writeVarInt(message.buffers.size());
            message.buffers.forEach(value -> value.encode(buffer));
        }

        public static CompositeStatusS2C decode(FriendlyByteBuf buffer) {
            boolean success = buffer.readBoolean();
            String operation = buffer.readUtf(MAX_TEXT);
            if (!success) return failure(operation);
            String project = buffer.readUtf(36);
            String graph = buffer.readUtf(MAX_TEXT);
            String fingerprint = buffer.readUtf(64);
            long generation = buffer.readVarLong();
            int nodeCount = buffer.readVarInt();
            if (nodeCount < 0 || nodeCount > MAX_NODES) {
                throw new IllegalArgumentException("Invalid Composite node count");
            }
            java.util.ArrayList<NodeWire> nodes = new java.util.ArrayList<>(nodeCount);
            for (int index = 0; index < nodeCount; index++) nodes.add(NodeWire.decode(buffer));
            int bufferCount = buffer.readVarInt();
            if (bufferCount < 0 || bufferCount > MAX_BUFFERS) {
                throw new IllegalArgumentException("Invalid Composite buffer count");
            }
            java.util.ArrayList<BufferWire> buffers = new java.util.ArrayList<>(bufferCount);
            for (int index = 0; index < bufferCount; index++) buffers.add(BufferWire.decode(buffer));
            return new CompositeStatusS2C(true, operation, project, graph, fingerprint,
                    generation, nodes, buffers);
        }

        public static void handle(CompositeStatusS2C message,
                Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PlayerWorkflowClient.compositeStatus(message)));
            context.setPacketHandled(true);
        }
    }
}
