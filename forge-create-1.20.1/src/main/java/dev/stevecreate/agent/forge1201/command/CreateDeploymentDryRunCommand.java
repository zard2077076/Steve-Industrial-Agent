package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunCommandFormatter;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunSection;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606DeploymentDryRunService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/** Typed, server-authoritative PW-12 read-only deploy command family. */
public final class CreateDeploymentDryRunCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DeploymentDryRunCommandFormatter FORMATTER =
            new DeploymentDryRunCommandFormatter();

    private CreateDeploymentDryRunCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("deploy");
        for (DeploymentDryRunSection section : DeploymentDryRunSection.values()) {
            root.then(Commands.literal(section.name().toLowerCase())
                    .then(arguments(section)));
        }
        return root;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> arguments(
            DeploymentDryRunSection section) {
        return Commands.argument("target_resource", ResourceLocationArgument.id())
                .then(Commands.argument("quantity", LongArgumentType.longArg(
                                1, ProductionGoal.MAX_TARGET_QUANTITY))
                        .executes(context -> execute(
                                context.getSource(), section,
                                ResourceLocationArgument.getId(context, "target_resource"),
                                LongArgumentType.getLong(context, "quantity"), QuarterTurn.ZERO))
                        .then(orientation(section, "zero", QuarterTurn.ZERO))
                        .then(orientation(section, "clockwise_90", QuarterTurn.CLOCKWISE_90))
                        .then(orientation(section, "clockwise_270", QuarterTurn.CLOCKWISE_270)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> orientation(
            DeploymentDryRunSection section,
            String literal,
            QuarterTurn orientation) {
        return Commands.literal(literal).executes(context -> execute(
                context.getSource(), section,
                ResourceLocationArgument.getId(context, "target_resource"),
                LongArgumentType.getLong(context, "quantity"), orientation));
    }

    private static int execute(
            CommandSourceStack source,
            DeploymentDryRunSection section,
            ResourceLocation target,
            long quantity,
            QuarterTurn orientation) {
        int anchorY = Boolean.getBoolean("steve_industrial.r09.packProfile")
                ? Math.min(source.getLevel().getMaxBuildHeight() - 24, 240)
                : (int) Math.floor(source.getPosition().y);
        BlockPos3i anchor = new BlockPos3i(
                (int) Math.floor(source.getPosition().x), anchorY,
                (int) Math.floor(source.getPosition().z));
        var result = CreateV606DeploymentDryRunService.preview(
                source.getLevel(), ResourceId.parse(target.toString()), quantity, anchor, orientation);
        if (result instanceof CreateV606DeploymentDryRunService.Failure failed) {
            var failure = failed.failure();
            String line = "Deploy " + section.name().toLowerCase() + " refused code="
                    + failure.code() + " stage=" + failure.stage() + " world="
                    + failure.worldIdentity() + " environment=" + failure.environmentType()
                    + " gameDir=" + failure.gameDirectoryIdentity() + " target=" + failure.target()
                    + " previewHash=" + failure.previewHash() + " region=" + failure.region()
                    + " policy=" + failure.policy() + " risk=" + failure.risk()
                    + " approval=" + failure.approval() + " backup=" + failure.backupIdentity()
                    + " runtime=" + failure.runtimeFingerprint() + " trace=" + failure.trace()
                    + " reason=" + failure.reason() + " userActionRequired="
                    + failure.userActionRequired() + " safeNextStep=" + failure.safeNextStep()
                    + " formalWorldExecutable=false worldMutation=false sessionCreated=false";
            source.sendFailure(Component.literal(line));
            LOGGER.info("DEPLOYMENT_DRY_RUN_RESULT deploymentDryRun={status=FAIL,{}}", line);
            return 0;
        }
        var report = FORMATTER.format(
                section, ((CreateV606DeploymentDryRunService.Success) result).report());
        report.userLines().forEach(line -> source.sendSuccess(
                () -> Component.literal(line), false));
        LOGGER.info("DEPLOYMENT_DRY_RUN_RESULT {}", report.structuredLog());
        return 1;
    }
}
