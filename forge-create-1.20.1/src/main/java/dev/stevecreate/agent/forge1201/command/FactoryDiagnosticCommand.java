package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.diagnostic.FactoryDiagnosis;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthReport;
import dev.stevecreate.agent.core.diagnostic.FactoryObservationState;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Explicit read-only factory diagnosis commands. */
public final class FactoryDiagnosticCommand {
    private FactoryDiagnosticCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("diagnose")
                .then(Commands.literal("project")
                        .executes(context -> diagnoseProject(context.getSource())))
                .then(Commands.literal("warehouse")
                        .then(Commands.argument("centre", BlockPosArgument.blockPos())
                                .executes(context -> diagnoseWarehouse(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "centre"))))));
    }

    private static int diagnoseProject(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Project diagnosis is player-only"));
            return 0;
        }
        FactoryHealthReport report = FactoryDiagnosticService.diagnoseProject(player).orElse(null);
        if (report == null) {
            source.sendFailure(Component.literal("No active or terminal player project was found"));
            return 0;
        }
        send(source, report);
        return 1;
    }

    private static int diagnoseWarehouse(CommandSourceStack source, net.minecraft.core.BlockPos centre) {
        FactoryHealthReport report = FactoryDiagnosticService
                .diagnoseWarehouse(source.getLevel(), centre).orElse(null);
        if (report == null) {
            source.sendFailure(Component.literal("No durable warehouse order exists at that position"));
            return 0;
        }
        send(source, report);
        return 1;
    }

    private static void send(CommandSourceStack source, FactoryHealthReport report) {
        source.sendSuccess(() -> Component.literal("Factory diagnosis " + report.status()
                + " | readOnly=true | worldMutations=" + report.worldMutations()
                + " | automaticRepair=false"), false);
        for (FactoryDiagnosis finding : report.findings()) {
            source.sendSuccess(() -> Component.literal("- " + finding.code()
                    + " | evidence=" + finding.evidenceCode()
                    + " | source=" + finding.source()
                    + " | action=" + finding.recommendation()), false);
        }
        report.observations().stream()
                .filter(value -> value.state() == FactoryObservationState.HEALTHY
                        || value.state() == FactoryObservationState.NOT_APPLICABLE)
                .forEach(value -> source.sendSuccess(() -> Component.literal("- "
                        + value.category() + " " + value.state()
                        + " | evidence=" + value.evidenceCode()
                        + " | source=" + value.source()
                        + (value.metrics().isEmpty() ? "" : " | metrics=" + value.metrics())), false));
        if (!report.unknownCategories().isEmpty()) {
            String unknown = report.unknownCategories().stream().sorted()
                    .map(Enum::name).collect(Collectors.joining(","));
            source.sendSuccess(() -> Component.literal("- UNKNOWN: " + unknown
                    + " (not reported as healthy)"), false);
        }
        source.sendSuccess(() -> Component.literal(
                "No repair, reroute, item movement, block change or order transition was attempted."), false);
    }
}
