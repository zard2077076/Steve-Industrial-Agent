package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;

/** Compact permission-gated operator/diagnostic surface. */
public final class MetalPressProductionCommand {
    private MetalPressProductionCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("ie")
                .then(Commands.literal("metalpress")
                        .then(Commands.literal("create")
                                .then(Commands.argument("material_source", BlockPosArgument.blockPos())
                                        .then(Commands.argument("machine_origin", BlockPosArgument.blockPos())
                                                .executes(context -> create(context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "material_source"),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "machine_origin"))))))
                        .then(Commands.literal("status").executes(context ->
                                status(context.getSource())))
                        .then(Commands.literal("cancel").executes(context ->
                                cancel(context.getSource()))))
                .then(Commands.literal("alloysmelter")
                        .then(Commands.literal("create")
                                .then(Commands.argument("material_source", BlockPosArgument.blockPos())
                                        .then(Commands.argument("machine_origin", BlockPosArgument.blockPos())
                                                .executes(context -> createAlloy(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "material_source"),
                                                        BlockPosArgument.getLoadedBlockPos(context,
                                                                "machine_origin"))))))
                        .then(Commands.literal("status").executes(context ->
                                statusAlloy(context.getSource())))
                        .then(Commands.literal("cancel").executes(context ->
                                cancelAlloy(context.getSource())))));
    }

    /** Permissionless self-service status/cancel surface; it can only address the issuing player. */
    public static LiteralArgumentBuilder<CommandSourceStack> playerCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("steveagent")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("metalpress")
                        .then(Commands.literal("status").executes(context ->
                                status(context.getSource())))
                        .then(Commands.literal("cancel").executes(context ->
                                cancel(context.getSource()))))
                .then(Commands.literal("alloysmelter")
                        .then(Commands.literal("status").executes(context ->
                                statusAlloy(context.getSource())))
                        .then(Commands.literal("cancel").executes(context ->
                                cancelAlloy(context.getSource()))))
                .then(Commands.literal("order")
                        .then(Commands.literal("status").executes(context -> {
                            IndustrialPlayerOrderService.sendStatus(
                                    context.getSource().getPlayerOrException());
                            return 1;
                        })));
        FactoryMaintenanceCommand.attach(root);
        return root;
    }

    private static int create(CommandSourceStack source,
            net.minecraft.core.BlockPos materialSource,
            net.minecraft.core.BlockPos machineOrigin) {
        try {
            var result = MetalPressProductionService.create(source.getPlayerOrException(),
                    materialSource, machineOrigin);
            if (!result.success()) {
                source.sendFailure(Component.literal("IE Metal Press 订单未启动：" + result.code()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("IE Metal Press 订单已创建 · "
                    + shortId(result.orderId()) + "\n"
                    + "材料已精确预留：16 件 · 输出：铁板 ×1 · 能耗：2400 FE\n"
                    + "Bot 正在从已选材料箱取料；执行 /industrialagent ie metalpress status 查看进度。"),
                    false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE Metal Press 订单拒绝："
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        try {
            var result = MetalPressProductionService.status(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("IE Metal Press：" + result.code()));
                return 0;
            }
            var order = result.order();
            long reserved = result.material() == null ? 0 : result.material().reservations().stream()
                    .mapToLong(value -> value.quantity()).sum();
            long returned = result.material() == null ? 0 : result.material().transactions().stream()
                    .filter(value -> value.state() == dev.stevecreate.agent.core.execution.construction
                            .MaterialTransactionState.RETURNED)
                    .mapToLong(value -> value.quantity()).sum();
            int progress = progress(order.stage());
            source.sendSuccess(() -> Component.literal("IE Metal Press · " + shortId(order.orderId())
                    + "\n进度 " + progress + "% · " + friendly(order.stage())
                    + "\n材料预留 " + reserved + "/16 · 已返还 " + returned
                    + "\n实际耗电 " + order.measuredEnergyConsumedFe() + "/2400 FE · 产出 "
                    + order.exactOutputCount() + "/1\n状态：" + order.statusCode()), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE Metal Press 状态读取失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int cancel(CommandSourceStack source) {
        try {
            var result = MetalPressProductionService.cancel(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("IE Metal Press 取消失败：" + result.code()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "IE Metal Press 订单已取消；可恢复现场已恢复，未消耗材料已返还。"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE Metal Press 取消失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int createAlloy(CommandSourceStack source,
            net.minecraft.core.BlockPos materialSource,
            net.minecraft.core.BlockPos machineOrigin) {
        try {
            var result = AlloySmelterProductionService.create(
                    source.getPlayerOrException(), materialSource, machineOrigin);
            if (!result.success()) {
                source.sendFailure(Component.literal("IE 合金炉订单未启动：" + result.code()));
                return 0;
            }
            long planned = result.material().requirements().values().stream()
                    .mapToLong(Long::longValue).sum();
            source.sendSuccess(() -> Component.literal("IE 合金炉订单已创建 · "
                    + shortId(result.orderId()) + "\n材料已精确预留：" + planned
                    + " 件 · 输出：黄铜锭 ×2 · 能源：煤炭 ×1（不虚构 FE）\n"
                    + "Bot 正在从玩家指定材料箱取料；执行 "
                    + "/industrialagent ie alloysmelter status 查看进度。"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE 合金炉订单拒绝："
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            return 0;
        }
    }

    private static int statusAlloy(CommandSourceStack source) {
        try {
            var result = AlloySmelterProductionService.status(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("IE 合金炉：" + result.code()));
                return 0;
            }
            var order = result.order();
            long planned = result.material() == null ? 0
                    : result.material().requirements().values().stream()
                            .mapToLong(Long::longValue).sum();
            long returned = result.material() == null ? 0
                    : result.material().transactions().stream().filter(value ->
                            value.state() == dev.stevecreate.agent.core.execution.construction
                                    .MaterialTransactionState.RETURNED)
                            .mapToLong(value -> value.quantity()).sum();
            source.sendSuccess(() -> Component.literal("IE 合金炉 · " + shortId(order.orderId())
                    + "\n进度 " + alloyProgress(order.stage()) + "% · "
                    + alloyFriendly(order.stage())
                    + "\n材料计划 " + planned + " · 已返还 " + returned
                    + "\n能源账：煤炭 ×1（材料账本） · FE 0 · 产出报告："
                    + (order.report().isPresent() ? "已生成" : "待生成")
                    + "\n状态：" + order.stage()), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE 合金炉状态读取失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int cancelAlloy(CommandSourceStack source) {
        try {
            var result = AlloySmelterProductionService.cancel(source.getPlayerOrException());
            if (!result.success()) {
                source.sendFailure(Component.literal("IE 合金炉取消失败：" + result.code()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "IE 合金炉订单已取消；现场基线已恢复，未消耗材料已精确返还。"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("IE 合金炉取消失败：" + failure.getMessage()));
            return 0;
        }
    }

    private static int alloyProgress(String stage) {
        if (stage.startsWith("PAUSED")) return 0;
        return switch (stage) {
            case "MATERIALS_RESERVED" -> 8;
            case "MATERIALS_DELIVERED" -> 22;
            case "STRUCTURE_BUILT" -> 36;
            case "MULTIBLOCK_FORMED" -> 48;
            case "PROCESSING" -> 68;
            case "OUTPUT_OBSERVED" -> 82;
            case "TEARDOWN" -> 88;
            case "BASELINE_RESTORED" -> 93;
            case "MATERIALS_RETURNED" -> 97;
            case "REPORT_GENERATED" -> 99;
            case "COMPLETED" -> 100;
            default -> 0;
        };
    }

    private static String alloyFriendly(String stage) {
        if (stage.startsWith("PAUSED")) return "已安全暂停";
        return switch (stage) {
            case "MATERIALS_RESERVED" -> "Bot 正在精确取料";
            case "MATERIALS_DELIVERED" -> "材料已送达";
            case "STRUCTURE_BUILT" -> "八块合金砖已搭建";
            case "MULTIBLOCK_FORMED" -> "工程师锤成型完成";
            case "PROCESSING" -> "真实燃煤加工中";
            case "OUTPUT_OBSERVED" -> "唯一黄铜锭产出已验证";
            case "TEARDOWN" -> "Bot 正在拆除";
            case "BASELINE_RESTORED" -> "现场基线已恢复";
            case "MATERIALS_RETURNED" -> "工具与剩余材料已返还";
            case "REPORT_GENERATED" -> "完成报告已生成";
            case "COMPLETED" -> "订单完成";
            default -> stage;
        };
    }

    private static int progress(dev.stevecreate.agent.core.industrial.MetalPressOrderStage stage) {
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

    private static String friendly(
            dev.stevecreate.agent.core.industrial.MetalPressOrderStage stage) {
        return switch (stage) {
            case MATERIAL_SOURCE_SELECTION -> "等待选择材料箱";
            case MATERIALS_RESERVED -> "Bot 正在取料";
            case MATERIALS_WITHDRAWN -> "Bot 正在运输";
            case MATERIALS_DELIVERED -> "材料已送达";
            case STRUCTURE_BUILT -> "结构搭建完成";
            case MULTIBLOCK_FORMED -> "工程师锤成型完成";
            case MOLD_INSTALLED -> "模具已安装";
            case POWER_NETWORK_BUILT -> "真实 FE 电网已搭建";
            case POWER_VERIFIED -> "机器已通电";
            case INPUT_QUEUED -> "铁锭已投料";
            case PROCESSING -> "正在压制铁板";
            case ENERGY_SETTLED -> "2400 FE 已核算";
            case OUTPUT_OBSERVED -> "唯一铁板已验证";
            case TEARDOWN -> "Bot 正在拆除";
            case BASELINE_RESTORED -> "现场基线已恢复";
            case MATERIALS_RETURNED -> "剩余材料已返还";
            case REPORT_GENERATED -> "完成报告已生成";
            case COMPLETED -> "订单完成";
            case PAUSED -> "已安全暂停";
            case CANCELLED -> "已取消";
        };
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
