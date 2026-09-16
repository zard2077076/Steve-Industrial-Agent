package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Development-only world preparation for a real Composite client acceptance run. */
public final class CompositeClientAcceptanceCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CompositeClientAcceptanceCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("acceptance")
                .then(Commands.literal("composite-fixture")
                        .then(Commands.argument("order_type", StringArgumentType.string())
                                        .executes(context -> prepare(context.getSource(),
                                        StringArgumentType.getString(context, "order_type"), null))
                                .then(Commands.argument("nonce", StringArgumentType.word())
                                        .executes(context -> prepare(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "order_type"),
                                                StringArgumentType.getString(context, "nonce"))))));
    }

    private static int prepare(
            CommandSourceStack source, String orderTypeText, String fixtureNonce) {
        try {
            AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositeClientAcceptanceCommand");
            ServerPlayer player = source.getPlayerOrException();
            ResourceId orderType = ResourceId.parse(orderTypeText);
            String nonce = fixtureNonce == null ? "00000000000000000000000000000000" : fixtureNonce;
            net.minecraft.core.BlockPos origin = player.blockPosition();
            CompositeClientAcceptanceFixture.Prepared prepared =
                    CompositeClientAcceptanceFixture.prepare(
                            player.serverLevel(), orderType, origin, nonce);
            LOGGER.info("COMPOSITE_CLIENT_FIXTURE_PREPARED orderType={} target={} quantity={} "
                            + "origin={} primary={} secondary={} nonce={}",
                    prepared.orderType(), prepared.target(), prepared.targetQuantity(),
                    prepared.origin(), prepared.primarySource(), prepared.secondarySource(),
                    prepared.fixtureNonce());
            String create = "/steveagent composite create \"" + orderType + "\" "
                    + position(prepared.primarySource()) + " " + position(prepared.origin())
                    + " bots " + position(prepared.secondarySource());
            source.sendSuccess(() -> Component.literal(
                    "Composite 客户端夹具已准备（不会自动下单）\n"
                            + "图=" + prepared.orderType() + " target=" + prepared.target()
                            + " ×" + prepared.targetQuantity() + " derived=" + prepared.derived()
                            + "\n主材料箱=" + prepared.primarySource()
                            + "\n副材料箱=" + prepared.secondarySource()
                            + "\n账单=" + prepared.requirements()
                            + "\nfixtureNonce=" + prepared.fixtureNonce()
                            + "\n真实玩家下一步：" + create), false);
            return 1;
        } catch (Exception failure) {
            LOGGER.warn("COMPOSITE_CLIENT_FIXTURE_REFUSED orderType={} nonce={} type={} detail={}",
                    orderTypeText, fixtureNonce,
                    failure.getClass().getSimpleName(), failure.getMessage());
            source.sendFailure(Component.literal("Composite 客户端夹具拒绝："
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            return 0;
        }
    }

    private static String position(net.minecraft.core.BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
