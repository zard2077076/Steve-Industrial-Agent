package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseOrderService;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData;
import dev.stevecreate.agent.forge1201.warehouse.WarehouseRuntimeSavedData.RuntimeKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Asking a warehouse to keep something in stock, and being told when it will not.
 *
 * <p>Everything behind this worked and nothing could reach it. The scheduler, the
 * dispatch and the restart-durable registration were all in place, and the only callers
 * were acceptance fixtures — so unattended production was real inside a gate and
 * unavailable to anyone playing.
 *
 * <p>The instruction is durable by construction. A standing order that evaporated on the
 * next restart would be worse than no order at all, because the warehouse would look like
 * it was still working.
 */
public final class WarehouseProductionCommand {
    /** Bound on how far a warehouse may look, matching the discovery scan. */
    private static final int MAX_RADIUS = 16;

    private WarehouseProductionCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        // Built up in named steps rather than one nested expression: the argument chain
        // is six deep and a misplaced parenthesis in that shape is invisible.
        var withMode = Commands.argument("mode", StringArgumentType.word())
                .executes(context -> maintain(context.getSource(),
                        BlockPosArgument.getLoadedBlockPos(context, "warehouse"),
                        IntegerArgumentType.getInteger(context, "radius"),
                        BlockPosArgument.getLoadedBlockPos(context, "site"),
                        StringArgumentType.getString(context, "order_type"),
                        IntegerArgumentType.getInteger(context, "target_stock"),
                        StringArgumentType.getString(context, "mode")));
        var withStock = Commands.argument("target_stock", IntegerArgumentType.integer(1, 64))
                .then(withMode);
        var withOrderType = Commands.argument("order_type", StringArgumentType.string())
                .then(withStock);
        var withSite = Commands.argument("site", BlockPosArgument.blockPos()).then(withOrderType);
        var withRadius = Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                .then(withSite);
        var maintain = Commands.literal("maintain")
                .then(Commands.argument("warehouse", BlockPosArgument.blockPos()).then(withRadius));
        var stop = Commands.literal("stop")
                .then(Commands.argument("warehouse", BlockPosArgument.blockPos())
                        .executes(context -> stop(context.getSource(),
                                BlockPosArgument.getLoadedBlockPos(context, "warehouse"))));
        var transfer = Commands.literal("transfer")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .then(Commands.argument("resource", StringArgumentType.string())
                                        .then(Commands.argument("amount",
                                                        IntegerArgumentType.integer(1, 256))
                                                .executes(context -> transfer(context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(
                                                                context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(
                                                                context, "to"),
                                                        StringArgumentType.getString(
                                                                context, "resource"),
                                                        IntegerArgumentType.getInteger(
                                                                context, "amount")))))));
        root.then(Commands.literal("warehouse").then(maintain).then(stop).then(transfer));
    }

    private static int maintain(
            CommandSourceStack source,
            BlockPos warehouse,
            int radius,
            BlockPos site,
            String orderType,
            int targetStock,
            String mode) {
        ServerLevel level = source.getLevel();
        ExecutionMode executionMode;
        try {
            executionMode = ExecutionMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            source.sendFailure(Component.literal("Unknown execution mode " + mode));
            return 0;
        }
        ResourceId graph = ResourceId.parse(orderType);
        boolean alloy = graph.equals(AlloySmelterProductionService.ORDER_TYPE);
        ResourceId product;
        Set<ResourceId> recipes;
        Set<ResourceId> adapters;
        long verifiedBatchOutput;
        int maximumRetries;
        RuntimeKind runtimeKind;
        if (alloy) {
            if (executionMode != ExecutionMode.BOTS) {
                source.sendFailure(Component.literal(
                        "The physical Alloy Smelter warehouse path requires BOTS mode"));
                return 0;
            }
            var reviewed = AlloySmelterProductionService.requirements(level);
            if (!reviewed.success()) {
                source.sendFailure(Component.literal(
                        "The reviewed Alloy Smelter order is unavailable: " + reviewed.code()));
                return 0;
            }
            if (targetStock < reviewed.outputCount()) {
                source.sendFailure(Component.literal("Alloy Smelter batches make "
                        + reviewed.outputCount() + "; target stock must be at least that large"));
                return 0;
            }
            product = reviewed.output();
            recipes = Set.of(reviewed.recipeId());
            adapters = Set.of(dev.stevecreate.agent.forge1201.adapter
                    .immersiveengineering.internal.v1020.ImmersiveEngineeringV1020Adapter
                            .ADAPTER_ID);
            verifiedBatchOutput = reviewed.outputCount();
            // A physical project that pauses cannot be replaced safely by another one;
            // fail the standing instruction closed instead of overlapping projects.
            maximumRetries = 0;
            runtimeKind = RuntimeKind.ALLOY_SMELTER;
        } else {
            // Quoted before anything is stored. A standing instruction whose graph cannot be
            // planned would sit in storage authorising batches that always fail.
            var preview = PlayerCompositeOrderService.preview(level, graph, site);
            if (!preview.success()) {
                source.sendFailure(Component.literal(
                        "That site cannot build " + orderType + ": " + preview.code()));
                return 0;
            }
            if (preview.requirements().isEmpty()) {
                source.sendFailure(Component.literal("That order type needs no material"));
                return 0;
            }
            product = targetProduct(level, graph);
            recipes = Set.of(graph);
            adapters = Set.of(ResourceId.parse("steve_industrial:adapter/create_v606"));
            verifiedBatchOutput = 1;
            maximumRetries = 3;
            runtimeKind = RuntimeKind.COMPOSITE;
        }

        ResourceId warehouseId = ResourceId.parse("steve_industrial:warehouse/"
                + warehouse.getX() + "_" + warehouse.getY() + "_" + warehouse.getZ());
        WarehouseOrderService.registerDurableRuntime(level,
                new WarehouseRuntimeSavedData.Registration(warehouseId, cell(warehouse), radius,
                        cell(site), graph, executionMode, runtimeKind, Optional.empty()));

        ResourceId orderId = ResourceId.parse("steve_industrial:order/" + warehouseId.path()
                .replace('/', '_'));
        WarehouseOrderSavedData.forLevel(level).put(new ProductionOrder(
                orderId,
                ResourceId.parse("steve_industrial:owner/" + source.getTextName()
                        .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_")),
                warehouseId,
                new WarehouseResourceKey(GenericResourceType.ITEM, product,
                        WarehouseResourceKey.EMPTY_COMPONENT_SHA256),
                targetStock, verifiedBatchOutput, 1, graph, recipes,
                adapters,
                List.of(ResourceId.parse("steve_industrial:site/" + warehouseId.path()
                        .replace('/', '_'))),
                maximumRetries, 0, 0, Optional.empty(), ProductionOrderStatus.ACTIVE,
                "PLAYER_REQUESTED", 1));

        source.sendSuccess(() -> Component.literal("Maintaining " + targetStock + " of "
                + product + " from the warehouse at "
                + warehouse.toShortString() + ", building at " + site.toShortString()
                + " in " + executionMode + " mode. This survives a restart."), true);
        return 1;
    }

    /**
     * Moves stock between two warehouses by courier.
     *
     * <p>Inter-factory routing is the same operation as fetching material to a site: a
     * bot taking from a set of containers to a chest. What makes it inter-factory is only
     * that both ends are warehouses.</p>
     */
    private static int transfer(
            CommandSourceStack source, BlockPos from, BlockPos to, String resource, int amount) {
        ServerLevel level = source.getLevel();
        var actor = source.getPlayer();
        if (actor == null) {
            source.sendFailure(Component.literal("A transfer needs a player to attribute it to"));
            return 0;
        }
        var sources = WarehouseDiscovery.candidates(level, from, 4, List.of()).stream()
                .map(candidate -> new BlockPos(candidate.position().x(),
                        candidate.position().y(), candidate.position().z()))
                .toList();
        if (sources.isEmpty()) {
            source.sendFailure(Component.literal("Nothing stocked at " + from.toShortString()));
            return 0;
        }
        var worldIdentity = CompositePlayerOrderReloadProbe.worldIdentity(level);
        if (worldIdentity.isEmpty()) {
            source.sendFailure(Component.literal("This world is not authorised for agent work"));
            return 0;
        }
        String code = WarehouseOrderService.beginTransfer(actor, sources, to, to.above(),
                ResourceId.parse(resource), amount, worldIdentity.get());
        if (!"TRANSFER_STARTED".equals(code)) {
            source.sendFailure(Component.literal("The transfer could not start: " + code));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Carrying " + amount + " " + resource
                + " from " + from.toShortString() + " to " + to.toShortString()), true);
        return 1;
    }

    private static int stop(CommandSourceStack source, BlockPos warehouse) {
        ServerLevel level = source.getLevel();
        ResourceId warehouseId = ResourceId.parse("steve_industrial:warehouse/"
                + warehouse.getX() + "_" + warehouse.getY() + "_" + warehouse.getZ());
        WarehouseRuntimeSavedData.forLevel(level).remove(warehouseId);
        WarehouseOrderService.unregisterRuntime(warehouseId);
        source.sendSuccess(() -> Component.literal(
                "Stopped unattended production at " + warehouse.toShortString()), true);
        return 1;
    }

    /** What the graph makes, which is what the warehouse is being asked to keep. */
    private static ResourceId targetProduct(ServerLevel level, ResourceId graph) {
        var resolved = CompositeOrderResolver.resolve(level, graph, 1);
        return resolved.success() ? resolved.spec().target() : graph;
    }

    private static BlockPos3i cell(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }
}
