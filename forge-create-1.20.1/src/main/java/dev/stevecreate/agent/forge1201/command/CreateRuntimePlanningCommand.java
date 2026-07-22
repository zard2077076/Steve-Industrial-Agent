package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeStage;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimePlanningCommandFormatter;
import dev.stevecreate.agent.adapter.api.RuntimePlanningCommandReport;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineCapabilityCatalog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/** Server-authoritative, read-only command adapter for runtime Create logical planning. */
public final class CreateRuntimePlanningCommand {
    private static final ResourceId ADAPTER_ID = ResourceId.parse(
            "steve_industrial:create_runtime_1_20_1_6_0_6");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RuntimeKnowledgePlanningService PLANNING =
            new RuntimeKnowledgePlanningService();
    private static final RuntimePlanningCommandFormatter FORMATTER =
            new RuntimePlanningCommandFormatter();

    private CreateRuntimePlanningCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("plan")
                .then(Commands.literal("create")
                        .then(Commands.argument("target_resource", ResourceLocationArgument.id())
                                .then(Commands.argument(
                                                "quantity",
                                                LongArgumentType.longArg(
                                                        1,
                                                        ProductionGoal.MAX_TARGET_QUANTITY))
                                        .executes(context -> execute(
                                                context.getSource(),
                                                ResourceLocationArgument.getId(
                                                        context, "target_resource"),
                                                LongArgumentType.getLong(context, "quantity"))))));
    }

    private static int execute(
            CommandSourceStack source,
            ResourceLocation target,
            long quantity) {
        ResourceId targetId = ResourceId.parse(target.toString());
        MinecraftServer server = source.getServer();
        if (server == null) {
            return emit(source, failure(
                    RuntimeKnowledgeFailureCode.WORLD_NOT_AVAILABLE,
                    targetId,
                    "server:missing",
                    "No authoritative server is available for runtime planning"));
        }
        if (!server.isSameThread()) {
            return emit(source, failure(
                    RuntimeKnowledgeFailureCode.WRONG_THREAD,
                    targetId,
                    "server_thread:required",
                    "Runtime planning command must execute on the authoritative server thread"));
        }
        ServerLevel level = source.getLevel();
        if (level == null) {
            return emit(source, failure(
                    RuntimeKnowledgeFailureCode.WORLD_NOT_AVAILABLE,
                    targetId,
                    "server_level:missing",
                    "No authoritative server world is available for runtime planning"));
        }
        if (!ModList.get().isLoaded("create")) {
            return emit(source, failure(
                    RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE,
                    targetId,
                    "required_mod:create",
                    "The Create Adapter is unavailable because Create is not loaded"));
        }

        RuntimeRecipeCatalogResult recipeResult =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(level).snapshot();
        if (recipeResult instanceof RuntimeRecipeCatalogResult.Failure failed) {
            return emit(source, new RuntimePlanningResult.Failure(failed.failure()));
        }
        RuntimeRecipeCatalogSnapshot recipes =
                ((RuntimeRecipeCatalogResult.Success) recipeResult).snapshot();
        RuntimeMachineCapabilityCatalogResult capabilityResult =
                new CreateRuntimeMachineCapabilityCatalog().snapshot(recipes);
        if (capabilityResult instanceof RuntimeMachineCapabilityCatalogResult.Failure failed) {
            return emit(source, new RuntimePlanningResult.Failure(failed.failure()));
        }
        RuntimeMachineCapabilityCatalogSnapshot capabilities =
                ((RuntimeMachineCapabilityCatalogResult.Success) capabilityResult).snapshot();
        ProductionGoal goal = new ProductionGoal(
                targetId,
                GenericResourceType.ITEM,
                quantity,
                Set.of(),
                Set.of(),
                Optional.of(ProductionGoal.MAX_PROCESSING_DEPTH),
                MaterialConstraints.none(),
                List.of(
                        PlanningStrategyPreference.MINIMIZE_STEPS,
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES),
                Map.of());
        return emit(source, PLANNING.plan(recipes, capabilities, goal));
    }

    private static int emit(CommandSourceStack source, RuntimePlanningResult result) {
        RuntimePlanningCommandReport report = FORMATTER.format(result);
        for (String line : report.userLines()) {
            if (report.success()) {
                source.sendSuccess(() -> Component.literal(line), false);
            } else {
                source.sendFailure(Component.literal(line));
            }
        }
        LOGGER.info("CREATE_RUNTIME_PLAN_RESULT {}", report.structuredLog());
        return report.commandReturn();
    }

    private static RuntimePlanningResult failure(
            RuntimeKnowledgeFailureCode code,
            ResourceId target,
            String trace,
            String detail) {
        return new RuntimePlanningResult.Failure(new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.COMMAND,
                Optional.empty(),
                Optional.of(target),
                Optional.empty(),
                ADAPTER_ID,
                "runtime:unavailable",
                List.of(trace),
                detail));
    }
}
