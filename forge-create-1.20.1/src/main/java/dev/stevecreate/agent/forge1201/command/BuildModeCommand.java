package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Per-operator construction mode selection; explicit pilot arguments take precedence. */
public final class BuildModeCommand {
    private static final UUID CONSOLE = new UUID(0, 0);
    private static final Map<UUID, ExecutionMode> MODES = new HashMap<>();

    private BuildModeCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("build")
                .then(Commands.literal("mode")
                        .then(mode("direct", ExecutionMode.DIRECT))
                        .then(mode("bots", ExecutionMode.BOTS))
                        .then(mode("hybrid", ExecutionMode.HYBRID))
                        .then(Commands.literal("status").executes(context ->
                                status(context.getSource())))));
    }

    static ExecutionMode modeFor(CommandSourceStack source) {
        return MODES.getOrDefault(identity(source), ExecutionMode.DIRECT);
    }

    static void clearServerState() {
        MODES.clear();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(
            String literal,
            ExecutionMode mode) {
        return Commands.literal(literal).executes(context -> set(context.getSource(), mode));
    }

    private static int set(CommandSourceStack source, ExecutionMode mode) {
        MODES.put(identity(source), mode);
        source.sendSuccess(() -> Component.literal(
                "Build mode=" + mode.serializedName()
                        + " scope=operator safePolicyEnforced=true explicitPilotOverride=true"),
                false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ExecutionMode mode = modeFor(source);
        source.sendSuccess(() -> Component.literal(
                "Build mode status mode=" + mode.serializedName()
                        + " default=direct hybridPolicy=safe-defaults highRisk=refused"),
                false);
        return 1;
    }

    private static UUID identity(CommandSourceStack source) {
        return source.getEntity() == null ? CONSOLE : source.getEntity().getUUID();
    }
}
