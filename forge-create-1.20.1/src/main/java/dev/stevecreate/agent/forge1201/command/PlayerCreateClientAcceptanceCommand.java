package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.WorkflowStage;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Development-only preparation command for the ordinary-player Create client flow. */
public final class PlayerCreateClientAcceptanceCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerCreateClientAcceptanceCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("acceptance")
                .then(Commands.literal("create-fixture")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .then(Commands.argument("quantity", LongArgumentType.longArg(1, 64))
                                        .executes(context -> prepare(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "target"),
                                                LongArgumentType.getLong(context, "quantity"),
                                                null))
                                        .then(Commands.argument("nonce", StringArgumentType.word())
                                                .executes(context -> prepare(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "target"),
                                                        LongArgumentType.getLong(context, "quantity"),
                                                        StringArgumentType.getString(context, "nonce")))))));
    }

    private static int prepare(
            CommandSourceStack source, String targetText, long quantity, String fixtureNonce) {
        try {
            AcceptanceRuntimeGuard.requireDevelopmentRuntime(
                    "PlayerCreateClientAcceptanceCommand");
            ServerPlayer player = source.getPlayerOrException();
            PlayerWorkflowSavedData.ProjectEntry existing =
                    PlayerWorkflowSavedData.forLevel(player.serverLevel())
                            .entry(player.getUUID()).orElse(null);
            if (existing != null && existing.stage() != WorkflowStage.COMPLETED
                    && existing.stage() != WorkflowStage.CANCELLED
                    && existing.stage() != WorkflowStage.REFUSED) {
                throw new IllegalStateException(
                        "C03_FIXTURE_ACTIVE_PROJECT:" + existing.projectId()
                                + ":stage=" + existing.stage());
            }
            ResourceId target = ResourceId.parse(targetText);
            String nonce = fixtureNonce == null ? "00000000000000000000000000000000" : fixtureNonce;
            PlayerCreateClientAcceptanceFixture.Prepared prepared =
                    PlayerCreateClientAcceptanceFixture.prepare(
                            player.serverLevel(), target, quantity, player.blockPosition(), nonce);
            LOGGER.info("C03_CLIENT_FIXTURE_PREPARED target={} quantity={} origin={} source={} "
                            + "salvage={} planHash={} nonce={}",
                    prepared.target(), prepared.quantity(), prepared.origin(), prepared.source(),
                    prepared.salvage(), prepared.materialPlanHash(), prepared.fixtureNonce());
            source.sendSuccess(() -> Component.literal(
                    "Create 玩家客户端夹具已准备（不会自动下单）\n"
                            + "target=" + prepared.target() + " ×" + prepared.quantity()
                            + " placementCount=" + prepared.placementCount()
                            + " routeCells=" + prepared.routeCellCount() + "\n"
                            + "材料箱=" + prepared.source() + "\n"
                            + "回收箱=" + prepared.salvage() + "（空）\n"
                            + "账单=" + prepared.requirements() + "\n"
                            + "materialPlanHash=" + prepared.materialPlanHash() + "\n"
                            + "fixtureNonce=" + prepared.fixtureNonce() + "\n"
                            + "真实玩家下一步：手持工程终端，打开终端并选择 " + prepared.target() + "；"
                            + "施工点为 " + prepared.origin()), false);
            return 1;
        } catch (Exception failure) {
            LOGGER.warn("C03_CLIENT_FIXTURE_REFUSED target={} quantity={} nonce={} type={} detail={}",
                    targetText, quantity, fixtureNonce,
                    failure.getClass().getSimpleName(), failure.getMessage());
            source.sendFailure(Component.literal("Create 玩家客户端夹具拒绝："
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage()));
            return 0;
        }
    }
}
