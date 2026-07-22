package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Administrator-only production setup for the current world. */
public final class SetupCommand {
    private SetupCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("setup")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("validate").executes(context -> validate(context.getSource())))
                .then(Commands.literal("show-config").executes(context -> show(context.getSource())))
                .then(Commands.literal("mark-test-world")
                        .requires(source -> source.hasPermission(4))
                        .executes(context -> mark(context.getSource())))
                .then(Commands.literal("unmark-test-world")
                        .requires(source -> source.hasPermission(4))
                        .executes(context -> unmark(context.getSource())));
    }

    private static int status(CommandSourceStack source) {
        var marker = PilotWorldMarkerSavedData.forLevel(source.getLevel()).marker();
        source.sendSuccess(() -> Component.literal("Setup status marked=" + marker.isPresent()
                + " schema=" + marker.map(PilotWorldMarkerSavedData.Marker::schema).orElse(0)
                + " generation=" + marker.map(PilotWorldMarkerSavedData.Marker::generation).orElse("none")
                + " directPilotAuthority=false backupRequired=true"), false);
        return 1;
    }

    private static int validate(CommandSourceStack source) {
        PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(source.getLevel(), true);
        if (result instanceof PublicAlphaRuntime.Failure failure) {
            source.sendFailure(Component.literal("Setup validation FAIL code=" + failure.code()
                    + " safeNextStep=/industrialagent setup show-config"));
            return 0;
        }
        var authorization = ((PublicAlphaRuntime.Success) result).authorization();
        source.sendSuccess(() -> Component.literal("Setup validation PASS schema="
                + authorization.schemaVersion() + " migratedFromV0=" + authorization.migratedFromV0()
                + " world=" + authorization.worldIdentity() + " marker="
                + authorization.markerGeneration() + " paths=redacted pilotAuthority=false"), false);
        return 1;
    }

    private static int show(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Setup config " + PublicAlphaRuntime.summary()), false);
        return 1;
    }

    private static int mark(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Test-world marking requires an in-world administrator"));
            return 0;
        }
        if (!source.getLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            source.sendFailure(Component.literal("Mark the test world from its overworld dimension"));
            return 0;
        }
        PublicAlphaRuntime.Result result = PublicAlphaRuntime.resolve(source.getLevel(), false);
        if (result instanceof PublicAlphaRuntime.Failure failure) {
            source.sendFailure(Component.literal("Test-world mark refused code=" + failure.code()));
            return 0;
        }
        try {
            String identity = PublicAlphaRuntime.worldIdentity(source.getLevel());
            PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(source.getLevel());
            if (data.marker().isPresent()) {
                source.sendFailure(Component.literal("Test world is already marked; unmark only when no pilot is active"));
                return 0;
            }
            data.mark(new PilotWorldMarkerSavedData.Marker(PilotWorldMarkerSavedData.SCHEMA,
                    identity, UUID.randomUUID().toString(), "overworld-pilot-only",
                    source.getServer().isDedicatedServer()
                            ? "dedicated-server-admin-confirmed" : "singleplayer-owner-confirmed",
                    "player:" + player.getUUID(), source.getLevel().getGameTime()));
            source.sendSuccess(() -> Component.literal("Test world marked identity=" + identity
                    + " schema=1 backupStillRequired=true regionConfirmationStillRequired=true"
                    + " directPilotAuthority=false"), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Test-world mark failed closed: " + failure.getMessage()));
            return 0;
        }
    }

    private static int unmark(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Test-world unmark requires an in-world administrator"));
            return 0;
        }
        if (PilotDeploymentCommand.hasAnyActiveSession()) {
            source.sendFailure(Component.literal("Unmark refused: cancel/finish and safely clean the active pilot first"));
            return 0;
        }
        PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(source.getLevel());
        if (data.marker().isEmpty()) {
            source.sendFailure(Component.literal("Current world is not marked"));
            return 0;
        }
        data.unmark();
        source.sendSuccess(() -> Component.literal(
                "Test-world marker removed; all prior backup evidence is now stale; worldMutation=false"), true);
        return 1;
    }
}
