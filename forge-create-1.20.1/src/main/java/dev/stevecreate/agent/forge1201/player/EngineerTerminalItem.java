package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.forge1201.player.net.MetalPressOrderNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.command.MetalPressPlayerInteractionService;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Opens a server-authoritative project UI and never mutates the world itself. */
public final class EngineerTerminalItem extends Item {
    public EngineerTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.isShiftKeyDown()) {
                MetalPressOrderNetwork.openStatus(serverPlayer);
                return InteractionResultHolder.consume(held);
            }
            PlayerWorkflowNetwork.openTerminal(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide) {
            InteractionResult result = MetalPressPlayerInteractionService.useOn(context);
            if (result.consumesAction()) return result;
        } else if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
            List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.steve_create_agent.engineer_terminal.open")
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.steve_create_agent.engineer_terminal.source")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.steve_create_agent.engineer_terminal.site")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.steve_create_agent.engineer_terminal.order")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, level, lines, flag);
    }
}
