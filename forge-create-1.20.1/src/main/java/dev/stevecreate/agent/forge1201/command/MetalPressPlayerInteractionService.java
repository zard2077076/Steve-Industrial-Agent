package dev.stevecreate.agent.forge1201.command;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/** Small terminal gesture layer; every material/world decision is still revalidated by the service. */
public final class MetalPressPlayerInteractionService {
    private static final String SOURCE_POS = "SteveIeMetalPressSource";
    private static final String SOURCE_DIMENSION = "SteveIeMetalPressSourceDimension";
    private static final String SOURCE_TICK = "SteveIeMetalPressSourceTick";
    private static final long SELECTION_TTL_TICKS = 12_000;

    private MetalPressPlayerInteractionService() {}

    public static InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = context.getClickedPos();
        if (player.serverLevel().getBlockEntity(clicked) instanceof Container) {
            var tag = player.getPersistentData();
            tag.putLong(SOURCE_POS, clicked.asLong());
            tag.putString(SOURCE_DIMENSION, player.serverLevel().dimension().location().toString());
            tag.putLong(SOURCE_TICK, player.serverLevel().getGameTime());
            player.displayClientMessage(Component.literal("✓ IE 材料箱已选择 ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(position(clicked)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" · 再潜行右击施工地面开始订单")
                            .withStyle(ChatFormatting.GRAY)), true);
            return InteractionResult.CONSUME;
        }
        BlockPos source = selectedSource(player);
        if (source == null) {
            player.displayClientMessage(Component.literal("请先潜行右击一个材料箱")
                    .withStyle(ChatFormatting.GOLD), true);
            return InteractionResult.CONSUME;
        }
        BlockPos origin = clicked.relative(context.getClickedFace());
        var started = MetalPressProductionService.create(player, source, origin);
        if (!started.success()) {
            player.sendSystemMessage(Component.literal("[Steve · IE] 订单未启动\n")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(friendlyFailure(started.code()))
                            .withStyle(ChatFormatting.YELLOW)));
            return InteractionResult.CONSUME;
        }
        clearSelection(player);
        player.sendSystemMessage(Component.literal("[Steve · IE] Metal Press 订单已启动\n")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("材料 16 件已精确预留 · 铁板 ×1 · 2400 FE\n")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Bot 正在取料；手持终端潜行右击空气查看进度")
                        .withStyle(ChatFormatting.GRAY)));
        return InteractionResult.CONSUME;
    }

    private static BlockPos selectedSource(ServerPlayer player) {
        var tag = player.getPersistentData();
        if (!tag.contains(SOURCE_POS) || !tag.contains(SOURCE_DIMENSION)
                || !tag.getString(SOURCE_DIMENSION).equals(
                        player.serverLevel().dimension().location().toString())
                || player.serverLevel().getGameTime() - tag.getLong(SOURCE_TICK)
                        > SELECTION_TTL_TICKS) {
            clearSelection(player);
            return null;
        }
        return BlockPos.of(tag.getLong(SOURCE_POS));
    }

    private static void clearSelection(ServerPlayer player) {
        var tag = player.getPersistentData();
        tag.remove(SOURCE_POS);
        tag.remove(SOURCE_DIMENSION);
        tag.remove(SOURCE_TICK);
    }

    private static String friendlyFailure(String code) {
        if (code.startsWith("WAREHOUSE_MATERIALS_INSUFFICIENT")) return "材料不足；补齐后重新选择材料箱";
        if (code.startsWith("ORDER_SITE_NOT_EMPTY")) return "施工范围被占用：" + code.substring(code.indexOf(':') + 1);
        return switch (code) {
            case "WORLD_NOT_AUTHORIZED" -> "当前世界尚未通过工程安全授权";
            case "MACHINE_ORIGIN_OUT_OF_RANGE" -> "施工点离你太远（最多 32 格）";
            case "MATERIAL_SOURCE_OVERLAPS_ORDER_SITE" -> "材料箱与施工范围重叠";
            case "PLAYER_ALREADY_HAS_ACTIVE_IE_ORDER" -> "你已有未结束的 IE 订单";
            default -> code;
        };
    }

    private static String position(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }
}
