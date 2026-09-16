package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shows the player which nearby containers hold something.
 *
 * <p>Without this, discovery existed only for the acceptance gate, which is the same as
 * not existing: a player still had to know where every chest was in order to name it.
 *
 * <p>It reports and stops there. Nothing is selected, reserved or taken, and the player
 * still names each source they want an order to draw on — being shown a chest is not
 * consent to empty it. The listing is worded to make that plain rather than reading like
 * a list of things already claimed.
 */
public final class WarehouseDiscoveryCommand {
    private static final int DEFAULT_RADIUS = 8;
    private static final int MAX_LISTED = 10;

    private WarehouseDiscoveryCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("warehouse")
                .then(Commands.literal("scan")
                        .executes(context -> scan(context.getSource(), DEFAULT_RADIUS))
                        .then(Commands.argument("radius",
                                        IntegerArgumentType.integer(1, WarehouseDiscovery.MAX_RADIUS))
                                .executes(context -> scan(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius"))))));
    }

    private static int scan(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("WAREHOUSE_SCAN_REQUIRES_A_PLAYER"));
            return 0;
        }
        List<WarehouseDiscovery.Candidate> found = WarehouseDiscovery.candidates(
                player.serverLevel(), player.blockPosition(), radius, List.of());
        if (found.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "WAREHOUSE_SCAN radius=" + radius + " found=0"), false);
            return 1;
        }
        Map<ResourceId, Long> combined = WarehouseDiscovery.combined(found);
        source.sendSuccess(() -> Component.literal("WAREHOUSE_SCAN radius=" + radius
                + " found=" + found.size() + " distinctItems=" + combined.size()
                + " (nothing is reserved; select each source you want to use)"), false);
        found.stream().limit(MAX_LISTED).forEach(candidate ->
                source.sendSuccess(() -> Component.literal("  " + candidate.position()
                        + " " + candidate.totalItems() + " items " + candidate.contents()), false));
        if (found.size() > MAX_LISTED) {
            source.sendSuccess(() -> Component.literal(
                    "  ... " + (found.size() - MAX_LISTED) + " more"), false);
        }
        return found.size();
    }
}
