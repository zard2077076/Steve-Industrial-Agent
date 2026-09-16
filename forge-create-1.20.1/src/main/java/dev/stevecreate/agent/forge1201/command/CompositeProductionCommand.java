package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.industrial.CompositePlayerOrderCatalogV1;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.player.net.CompositeOrderNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Compact operator/diagnostic surface for the reviewed Composite player orders. */
public final class CompositeProductionCommand {
    private CompositeProductionCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("composite")
                .then(createCommand())
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
                .then(Commands.literal("catalog").executes(context -> catalog(context.getSource()))));
    }

    /** Permissionless self-service surface; it can only address the issuing player. */
    public static LiteralArgumentBuilder<CommandSourceStack> playerCommand() {
        return Commands.literal("steveagent")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("composite")
                        .then(createCommand())
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(context.getSource())))
                        .then(Commands.literal("catalog")
                                .executes(context -> catalog(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("create")
                .then(Commands.argument("order_type", com.mojang.brigadier.arguments
                                .StringArgumentType.string())
                        .then(Commands.argument("material_source", BlockPosArgument.blockPos())
                                .then(Commands.argument("site_origin", BlockPosArgument.blockPos())
                                        .then(Commands.argument("mode", com.mojang.brigadier.arguments
                                                        .StringArgumentType.word())
                                                .executes(context -> create(context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                .getString(context, "order_type"),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "material_source"),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "site_origin"),
                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                .getString(context, "mode")))
                                                .then(Commands.argument("secondary_source",
                                                                BlockPosArgument.blockPos())
                                                        .executes(context -> create(context.getSource(),
                                                                com.mojang.brigadier.arguments.StringArgumentType
                                                                        .getString(context, "order_type"),
                                                                BlockPosArgument.getLoadedBlockPos(context,
                                                                        "material_source"),
                                                                BlockPosArgument.getLoadedBlockPos(context,
                                                                        "site_origin"),
                                                                com.mojang.brigadier.arguments.StringArgumentType
                                                                        .getString(context, "mode"),
                                                                BlockPosArgument.getLoadedBlockPos(context,
                                                                        "secondary_source"))))))));
    }

    private static int create(
            CommandSourceStack source,
            String orderType,
            net.minecraft.core.BlockPos materialSource,
            net.minecraft.core.BlockPos siteOrigin,
            String mode) {
        return create(source, orderType, materialSource, siteOrigin, mode, null);
    }

    private static int create(
            CommandSourceStack source,
            String orderType,
            net.minecraft.core.BlockPos materialSource,
            net.minecraft.core.BlockPos siteOrigin,
            String mode,
            net.minecraft.core.BlockPos secondarySource) {
        try {
            ExecutionMode executionMode = mode(mode);
            if (executionMode == null) {
                source.sendFailure(Component.literal("模式无效，可用：direct / bots / hybrid"));
                return 0;
            }
            java.util.List<net.minecraft.core.BlockPos> sources = secondarySource == null
                    ? java.util.List.of(materialSource)
                    : java.util.List.of(materialSource, secondarySource);
            var result = PlayerCompositeOrderService.create(source.getPlayerOrException(),
                    sources, siteOrigin, ResourceId.parse(orderType), executionMode);
            if (!result.success()) {
                source.sendFailure(Component.literal("Composite 订单未启动：" + result.code()));
                if (source.getEntity() instanceof ServerPlayer player) {
                    CompositeOrderNetwork.sendFailure(player, result.code());
                }
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Composite 订单已创建 · "
                    + result.orderType() + "\n"
                    + "全部阶段与路线材料已精确预留并实物投放；执行 "
                    + "/steveagent composite status 查看进度。"), false);
            CompositeOrderNetwork.openStatus(source.getPlayerOrException());
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Composite 订单拒绝："
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        try {
            var result = PlayerCompositeOrderService.status(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("Composite：" + result.code()));
                if (source.getEntity() instanceof ServerPlayer player) {
                    var completion = PlayerCompositeOrderService.completion(player);
                    if (completion.success()) {
                        CompositeOrderNetwork.sendCompletion(player,
                                dev.stevecreate.agent.forge1201.player.net.CompositeOrderPackets
                                        .CompositeCompletionS2C.from(player, "STATUS_OPENED"));
                    } else {
                        CompositeOrderNetwork.sendFailure(player, result.code());
                    }
                }
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Composite 订单 "
                    + result.projectId().toString().substring(0, 8) + "\n"
                    + "节点状态：" + result.snapshot().nodeStatuses()), false);
            CompositeOrderNetwork.openStatus(source.getPlayerOrException());
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Composite 状态读取失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int cancel(CommandSourceStack source) {
        try {
            var result = PlayerCompositeOrderService.cancel(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("Composite 取消失败：" + result.code()));
                if (source.getEntity() instanceof ServerPlayer player) {
                    CompositeOrderNetwork.sendFailure(player, result.code());
                }
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "Composite 订单已取消；已安装基础设施保留待检查，订单已暂停。"), false);
            CompositeOrderNetwork.openStatus(source.getPlayerOrException());
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Composite 取消失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int catalog(CommandSourceStack source) {
        StringBuilder text = new StringBuilder("可下单的 Composite 图：");
        CompositePlayerOrderCatalogV1.entries().forEach(spec -> text.append('\n')
                .append(spec.orderType()).append(" → ").append(spec.target()).append(" ×")
                .append(spec.targetQuantity()).append("\n  需预留：").append(spec.externalProcessInputs())
                .append(" + 基础设施 ").append(spec.infrastructureMaterials())
                .append("\n  回收：").append(spec.intermediateSalvage()));
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return 1;
    }

    private static ExecutionMode mode(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "direct" -> ExecutionMode.DIRECT;
            case "bots" -> ExecutionMode.BOTS;
            case "hybrid" -> ExecutionMode.HYBRID;
            default -> null;
        };
    }
}
