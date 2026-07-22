package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.RuntimeBindingCommandFormatter;
import dev.stevecreate.agent.adapter.api.RuntimeImplementationBindingService;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgePlanningService;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimePlanningCommandReport;
import dev.stevecreate.agent.adapter.api.RuntimePlanningResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeVerifiedPlanningResult;
import dev.stevecreate.agent.core.binding.BindingConstraints;
import dev.stevecreate.agent.core.binding.BindingFailure;
import dev.stevecreate.agent.core.binding.BindingFailureCode;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.BindingStage;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineCapabilityCatalog;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateRuntimeMachineImplementationCatalog;
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

/** Server-authoritative, read-only command that plans and verifies concrete Create bindings. */
public final class CreateRuntimeBindingCommand {
    private static final ResourceId ADAPTER_ID = ResourceId.parse(
            "steve_industrial:create_runtime_1_20_1_6_0_6");
    private static final RuntimeKnowledgePlanningService PLANNING =
            new RuntimeKnowledgePlanningService();
    private static final RuntimeImplementationBindingService BINDING =
            new RuntimeImplementationBindingService();
    private static final RuntimeBindingCommandFormatter FORMATTER =
            new RuntimeBindingCommandFormatter();
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateRuntimeBindingCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("bind")
                .then(Commands.literal("create")
                        .then(Commands.argument("target_resource", ResourceLocationArgument.id())
                                .then(Commands.argument("quantity", LongArgumentType.longArg(
                                                1, ProductionGoal.MAX_TARGET_QUANTITY))
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
        if (server == null || !server.isSameThread()) {
            return emit(source, failure(
                    BindingFailureCode.WRONG_THREAD,
                    targetId,
                    server == null ? "server=missing" : "server_thread=required",
                    "Runtime binding must execute on the authoritative server thread"));
        }
        ServerLevel level = source.getLevel();
        if (level == null) {
            return emit(source, failure(
                    BindingFailureCode.WORLD_CONTEXT_NOT_REQUIRED,
                    targetId,
                    "server_level=missing",
                    "A level is needed only to capture the read-only runtime recipe snapshot"));
        }
        if (!ModList.get().isLoaded("create")) {
            return emit(source, failure(
                    BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                    targetId,
                    "required_mod=create",
                    "Create is not loaded"));
        }
        RuntimeRecipeCatalogResult recipeResult =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(level).snapshot();
        if (!(recipeResult instanceof RuntimeRecipeCatalogResult.Success recipeSuccess)) {
            return emit(source, failure(
                    BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING,
                    targetId,
                    "runtime_recipe_snapshot=available",
                    recipeResult.toString()));
        }
        RuntimeRecipeCatalogSnapshot recipes = recipeSuccess.snapshot();
        RuntimeMachineCapabilityCatalogResult capabilityResult =
                new CreateRuntimeMachineCapabilityCatalog().snapshot(recipes);
        if (!(capabilityResult instanceof RuntimeMachineCapabilityCatalogResult.Success
                capabilitySuccess)) {
            return emit(source, failure(
                    BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH,
                    targetId,
                    "runtime_capability_snapshot=available",
                    capabilityResult.toString()));
        }
        RuntimeMachineCapabilityCatalogSnapshot capabilities = capabilitySuccess.snapshot();
        RuntimeMachineImplementationCatalogResult implementationResult =
                new CreateRuntimeMachineImplementationCatalog().snapshot(
                        recipes, capabilities, false);
        if (implementationResult instanceof RuntimeMachineImplementationCatalogResult.Failure failed) {
            return emit(source, new BindingResult.Failure(failed.failure()));
        }
        RuntimeMachineImplementationCatalogSnapshot implementations =
                ((RuntimeMachineImplementationCatalogResult.Success) implementationResult).snapshot();
        ProductionGoal goal = new ProductionGoal(
                targetId, GenericResourceType.ITEM, quantity, Set.of(), Set.of(),
                Optional.of(ProductionGoal.MAX_PROCESSING_DEPTH), MaterialConstraints.none(),
                List.of(PlanningStrategyPreference.MINIMIZE_STEPS,
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES), Map.of());
        RuntimePlanningResult planned = PLANNING.plan(recipes, capabilities, goal);
        if (!(planned instanceof RuntimePlanningResult.Success planningSuccess)) {
            return emit(source, failure(
                    BindingFailureCode.IMPLEMENTATION_NOT_FOUND,
                    targetId,
                    "verified_logical_plan=available",
                    planned.toString()));
        }
        RuntimeVerifiedPlanningResult runtimePlan = planningSuccess.result();
        BindingConstraints constraints = BindingConstraints.forRuntime(
                ADAPTER_ID, recipes.runtime().industrialModVersions(), recipes.runtimeFingerprint());
        return emit(source, BINDING.bind(runtimePlan, implementations, constraints));
    }

    private static int emit(CommandSourceStack source, BindingResult result) {
        RuntimePlanningCommandReport report = FORMATTER.format(result);
        report.userLines().forEach(line -> {
            if (report.success()) {
                source.sendSuccess(() -> Component.literal(line), false);
            } else {
                source.sendFailure(Component.literal(line));
            }
        });
        LOGGER.info("CREATE_RUNTIME_BIND_RESULT {}", report.structuredLog());
        return report.commandReturn();
    }

    private static BindingResult failure(
            BindingFailureCode code,
            ResourceId target,
            String constraint,
            String detail) {
        return new BindingResult.Failure(new BindingFailure(
                code, BindingStage.COMMAND, Optional.empty(), Optional.empty(),
                Optional.of(target), List.of(), Optional.of(ADAPTER_ID), "runtime:unavailable",
                constraint, List.of("binding_command:" + code.name().toLowerCase()), detail,
                code == BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                "Retry after restoring the matching read-only runtime snapshots"));
    }
}
