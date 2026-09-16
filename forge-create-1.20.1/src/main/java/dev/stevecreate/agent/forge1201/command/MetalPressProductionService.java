package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedger;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.industrial.EntityLogisticsBindingV1;
import dev.stevecreate.agent.core.industrial.EntityLogisticsSettlementV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceBindingV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceRecoveryV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceRuntimeV1;
import dev.stevecreate.agent.core.industrial.MetalPressCompletionReport;
import dev.stevecreate.agent.core.industrial.MetalPressDurableEffect;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStateMachine;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.GlobalInventoryGraph;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseEndpointType;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSystem;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringOrderIdentities;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020Adapter;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.StoredOrder;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderService;
import dev.stevecreate.agent.forge1201.industrial.IndustrialResourceCheckpointSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Entry;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Reservation;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Transaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Server-authoritative player order joining the frozen material ledger to real IE execution. */
public final class MetalPressProductionService {
    // Named through the IE-free holder on purpose: this class is initialized by the plain
    // server tick, and loading the adapter here crashed a server that has no IE installed
    // before tick()'s own ModList guard could return.
    public static final ResourceId ORDER_TYPE =
            ImmersiveEngineeringOrderIdentities.METAL_PRESS_ORDER_TYPE;
    public static final ResourceId RECIPE = id("immersiveengineering:metalpress/plate_iron");
    public static final ResourceId OUTPUT = id("immersiveengineering:plate_iron");
    private static final ResourceId IRON = id("minecraft:iron_ingot");
    private static final ResourceId WIRE = id("immersiveengineering:wirecoil_copper");
    private static final int MAX_ORIGIN_DISTANCE_SQUARED = 32 * 32;
    private static final String BUILDER_TAG = "steve_ie_metal_press_builder";
    private static final String ORDER_TAG = "ie_metal_press_order_";
    private static final ResourceId FE = id("immersiveengineering:fe");
    private static final ResourceId ENERGY_SOURCE = id(
            "steve_industrial:energy/metal_press_generator");
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private static final Map<ResourceId, Long> REQUIREMENTS = Map.ofEntries(
            Map.entry(id("immersiveengineering:steel_scaffolding_standard"), 2L),
            Map.entry(id("immersiveengineering:heavy_engineering"), 1L),
            Map.entry(id("immersiveengineering:rs_engineering"), 1L),
            Map.entry(id("immersiveengineering:conveyor_basic"), 2L),
            Map.entry(id("minecraft:piston"), 1L),
            Map.entry(id("immersiveengineering:hammer"), 1L),
            Map.entry(id("immersiveengineering:mold_plate"), 1L),
            Map.entry(IRON, 1L),
            Map.entry(id("immersiveengineering:thermoelectric_generator"), 1L),
            Map.entry(id("immersiveengineering:connector_lv"), 2L),
            Map.entry(WIRE, 1L),
            Map.entry(id("minecraft:blue_ice"), 1L),
            Map.entry(id("minecraft:magma_block"), 1L));

    private static final Set<ResourceId> STRUCTURE = Set.of(
            id("immersiveengineering:steel_scaffolding_standard"),
            id("immersiveengineering:heavy_engineering"),
            id("immersiveengineering:rs_engineering"),
            id("immersiveengineering:conveyor_basic"), id("minecraft:piston"));
    private static final Set<ResourceId> POWER_INSTALLATION = Set.of(
            id("immersiveengineering:thermoelectric_generator"),
            id("immersiveengineering:connector_lv"),
            id("minecraft:blue_ice"), id("minecraft:magma_block"));
    private static final Set<ResourceId> CONSUMED = Set.of(IRON, WIRE);

    private MetalPressProductionService() {}

    public static StartResult create(
            ServerPlayer player, BlockPos sourcePosition, BlockPos origin) {
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) return StartResult.failure("SERVER_THREAD_REQUIRED");
        if (!ModList.get().isLoaded("immersiveengineering")) {
            return StartResult.failure("IMMERSIVE_ENGINEERING_NOT_LOADED");
        }
        String runtime = ModList.get().getModContainerById("immersiveengineering")
                .map(value -> value.getModInfo().getVersion().toString()).orElse("");
        if (!runtime.contains("10.2.0-183")) return StartResult.failure("IE_RUNTIME_UNSUPPORTED");
        PilotWorldMarkerSavedData.Marker marker = PilotWorldMarkerSavedData.forLevel(level)
                .marker().orElse(null);
        if (marker == null) return StartResult.failure("WORLD_NOT_AUTHORIZED");
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return StartResult.failure("ORDER_DIMENSION_UNSUPPORTED");
        }
        if (!player.mayBuild()) return StartResult.failure("PLAYER_BUILD_PERMISSION_DENIED");
        if (player.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D,
                origin.getZ() + 0.5D) > MAX_ORIGIN_DISTANCE_SQUARED) {
            return StartResult.failure("MACHINE_ORIGIN_OUT_OF_RANGE");
        }
        if (MetalPressOrderSavedData.forLevel(level).orders().values().stream().anyMatch(value ->
                value.order().playerId().equals(player.getUUID())
                        && value.order().stage() != MetalPressOrderStage.COMPLETED
                        && value.order().stage() != MetalPressOrderStage.CANCELLED)) {
            return StartResult.failure("PLAYER_ALREADY_HAS_ACTIVE_IE_ORDER");
        }
        if (level.getRecipeManager().byKey(ResourceLocation.parse(RECIPE.toString())).isEmpty()) {
            return StartResult.failure("METAL_PRESS_RECIPE_UNAVAILABLE");
        }
        if (!ImmersiveEngineeringV1020Adapter.metalPressResourceRegistration()
                .supports(ORDER_TYPE, RECIPE)) {
            return StartResult.failure("IE_RESOURCE_RECOVERY_REGISTRATION_MISSING");
        }
        BlockPos staging = origin.offset(-3, 0, -2).immutable();
        List<BlockPos> mutable = new ArrayList<>(
                ImmersiveEngineeringV1020MetalPressProduction.baselinePositions(level, origin));
        mutable.add(staging);
        mutable = mutable.stream().distinct().sorted(positionOrder()).toList();
        if (mutable.contains(sourcePosition) || mutable.size() > MetalPressOrderSavedData.MAX_BASELINE_BLOCKS) {
            return StartResult.failure("MATERIAL_SOURCE_OVERLAPS_ORDER_SITE");
        }
        for (BlockPos position : mutable) {
            if (!level.hasChunkAt(position)) return StartResult.failure("ORDER_CHUNK_NOT_LOADED");
            BlockState state = level.getBlockState(position);
            if ((!state.isAir() && !state.canBeReplaced()) || level.getBlockEntity(position) != null) {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                return StartResult.failure("ORDER_SITE_NOT_EMPTY:" + position.getX() + ","
                        + position.getY() + "," + position.getZ() + ":"
                        + (blockId == null ? "unregistered" : blockId));
            }
        }
        UUID orderId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String planHash = planHash(runtime);
        List<BaselineBlock> baseline = mutable.stream().map(position -> new BaselineBlock(
                pos(position), NbtUtils.writeBlockState(level.getBlockState(position)))).toList();
        String baselineHash = baselineHash(level, baseline);
        var opened = PlayerMaterialService.openStandalonePlan(player, projectId,
                REQUIREMENTS, planHash, runtime);
        if (!opened.success()) return StartResult.failure(opened.statusCode());
        var selected = PlayerMaterialService.selectStandaloneSource(player, projectId,
                pos(sourcePosition), Direction.UP);
        if (!selected.success()) return StartResult.failure(selected.statusCode());
        var reserved = PlayerMaterialService.confirmStandalone(player, projectId,
                marker.worldIdentity());
        if (!reserved.success()) {
            StringBuilder missing = new StringBuilder();
            PlayerMaterialService.Summary summary = selected.summary();
            if (summary != null) summary.required().forEach((item, quantity) -> {
                long available = summary.available().getOrDefault(item, 0L);
                if (available < quantity) missing.append('|').append(item).append(':')
                        .append(available).append('/').append(quantity).append(":payloads=")
                        .append(selected.entry().sources().stream()
                                .flatMap(source -> source.slots().stream())
                                .filter(slot -> slot.identity().itemId().equals(item))
                                .map(slot -> slot.identity().payloadSha256())
                                .distinct().toList());
            });
            return StartResult.failure(reserved.statusCode() + missing);
        }
        if (!placeStaging(level, staging)) return StartResult.failure("STAGING_CHEST_FAILED");
        Entry material = reserved.entry().withLogistics(
                new PlayerMaterialSavedData.Logistics(pos(staging), pos(staging)),
                Instant.now().toEpochMilli(), "IE_ORDER_LOGISTICS_BOUND");
        PlayerMaterialSavedData.forLevel(level).put(material);
        material = PlayerConstructionService.prepareWithdrawal(level, material,
                MaterialExecutorKind.BOT);
        DeploymentBoundingBox bounds = bounds(mutable, sourcePosition, player.blockPosition());
        PlayerConstructionService.MaterialCourier courier;
        try {
            courier = PlayerConstructionService.MaterialCourier.spawnForOwner(level, projectId,
                    material, pos(staging), pos(player.blockPosition()), bounds, player)
                    .retainAfterDelivery();
        } catch (RuntimeException failure) {
            return StartResult.failure("MATERIAL_COURIER_START_REFUSED");
        }
        long now = Instant.now().toEpochMilli();
        MetalPressProductionOrder order = MetalPressProductionOrder.create(orderId, projectId,
                player.getUUID(), id(level.dimension().location().toString()), pos(origin),
                pos(sourcePosition), RECIPE, planHash, runtime, baselineHash, now);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MATERIALS_RESERVED, "BOT_FETCHING_PLAYER_MATERIALS", now);
        var identity = new IndustrialPlayerOrderService.OrderIdentity(projectId, player.getUUID(),
                id(level.dimension().location().toString()), pos(origin), baselineHash, now);
        var bound = IndustrialPlayerOrderService.bindReviewedOrder(player, identity, ORDER_TYPE,
                OUTPUT, RECIPE, planHash, runtime, marker.worldIdentity());
        if (!bound.success()) {
            courier.cancelAndReturn();
            restoreBaseline(level, new StoredOrder(order, baseline));
            return StartResult.failure(bound.code());
        }
        MetalPressOrderSavedData.forLevel(level).put(new StoredOrder(order, baseline));
        save(level);
        ACTIVE.put(orderId, new Active(orderId, player.getUUID(), projectId, origin.immutable(),
                staging, bounds, courier, courier.entityId(), null, -1, 0, player, false));
        return new StartResult(true, "ORDER_STARTED", orderId, order, material);
    }

    public static void tick(MinecraftServer server) {
        if (!ModList.get().isLoaded("immersiveengineering")) return;
        recoverMissing(server);
        for (Active active : List.copyOf(ACTIVE.values())) {
            ServerPlayer player = active.livePlayer != null
                    ? active.livePlayer : server.getPlayerList().getPlayer(active.playerId);
            if (player == null) continue;
            try { tickActive(player, active); }
            catch (RuntimeException failure) {
                String detail = failure instanceof OrderFailure && failure.getMessage() != null
                        ? failure.getMessage() : failure.getClass().getSimpleName();
                MetalPressOrderStage stage = MetalPressOrderSavedData.forLevel(player.serverLevel())
                        .order(active.orderId).map(value -> value.order().stage())
                        .orElse(MetalPressOrderStage.PAUSED);
                pause(player.serverLevel(), active.orderId,
                        "ORDER_EXCEPTION:" + stage + ":" + detail);
                ACTIVE.remove(active.orderId);
            }
        }
    }

    private static void tickActive(ServerPlayer player, Active active) {
        ServerLevel level = player.serverLevel();
        StoredOrder stored = MetalPressOrderSavedData.forLevel(level)
                .order(active.orderId).orElseThrow();
        MetalPressProductionOrder order = stored.order();
        if (order.stage() == MetalPressOrderStage.PAUSED
                || order.stage() == MetalPressOrderStage.CANCELLED
                || order.stage() == MetalPressOrderStage.COMPLETED) {
            ACTIVE.remove(active.orderId);
            return;
        }
        if (active.courier != null) {
            PlayerConstructionService.CourierTick result = active.courier.tick();
            if (result == PlayerConstructionService.CourierTick.PROGRESS) return;
            if (result == PlayerConstructionService.CourierTick.FAILED) {
                pause(level, active.orderId,
                        "MATERIAL_COURIER_FAILED:" + active.courier.failureCode());
                ACTIVE.remove(active.orderId);
                return;
            }
            order = effect(level, order, MetalPressDurableEffect.MATERIAL_WITHDRAWAL,
                    1, "MATERIAL_WITHDRAWAL_BATCH_RECORDED");
            order = checkpoint(level, order, MetalPressOrderStage.MATERIALS_DELIVERED,
                    "BOT_DELIVERED_ALL_MATERIALS");
            active.courier = null;
            active.builderId = ensureBuilder(level, active, player).getUUID();
            return;
        }
        ConstructionBotEntity builder = ensureBuilder(level, active, player);
        BlockPos work = workCell(level, active.origin);
        if (!at(builder, work)) {
            builder.advanceToward(work);
            return;
        }
        Entry material = PlayerMaterialSavedData.forLevel(level).entry(active.projectId).orElseThrow();
        switch (order.stage()) {
            case MATERIALS_WITHDRAWN -> {
                if (!allTransactions(material, MaterialTransactionState.DELIVERED)) {
                    throw new OrderFailure("MATERIAL_DELIVERY_RECONCILIATION_REQUIRED");
                }
                checkpoint(level, order, MetalPressOrderStage.MATERIALS_DELIVERED,
                        "RECOVERED_ALL_MATERIALS_DELIVERED");
            }
            case MATERIALS_DELIVERED -> {
                if (ImmersiveEngineeringV1020MetalPressProduction.rawStructurePresent(
                        level, active.origin)) {
                    checkpoint(level, order, MetalPressOrderStage.STRUCTURE_BUILT,
                            "RECOVERED_7_BLOCK_STRUCTURE");
                    return;
                }
                removeInstalledFromStaging(level, active.staging, material, STRUCTURE);
                var result = ImmersiveEngineeringV1020MetalPressProduction.placeStructure(
                        level, active.origin);
                if (!result.success() || result.placedBlocks() != 7) throw new OrderFailure(result.code());
                checkpoint(level, order, MetalPressOrderStage.STRUCTURE_BUILT,
                        "BOT_BUILT_7_BLOCK_STRUCTURE");
            }
            case STRUCTURE_BUILT -> {
                if (ImmersiveEngineeringV1020MetalPressProduction.multiblockFormed(
                        level, active.origin)) {
                    checkpoint(level, order, MetalPressOrderStage.MULTIBLOCK_FORMED,
                            "RECOVERED_FORMED_MULTIBLOCK");
                    return;
                }
                builder.setItemSlot(EquipmentSlot.MAINHAND, removeFromStaging(level, active.staging,
                        material, id("immersiveengineering:hammer"), 1));
                var result = ImmersiveEngineeringV1020MetalPressProduction.form(level, active.origin,
                        builder.getItemBySlot(EquipmentSlot.MAINHAND));
                if (!result.success()) throw new OrderFailure(result.code());
                builder.setItemSlot(EquipmentSlot.OFFHAND,
                        builder.getItemBySlot(EquipmentSlot.MAINHAND).copy());
                builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                checkpoint(level, order, MetalPressOrderStage.MULTIBLOCK_FORMED,
                        "BOT_ENGINEER_HAMMER_FORMED_MULTIBLOCK");
            }
            case MULTIBLOCK_FORMED -> {
                if (ImmersiveEngineeringV1020MetalPressProduction.plateMoldInstalled(
                        level, active.origin, stack(id("immersiveengineering:mold_plate"), 1))) {
                    checkpoint(level, order, MetalPressOrderStage.MOLD_INSTALLED,
                            "RECOVERED_INSTALLED_PLATE_MOLD");
                    return;
                }
                ItemStack mold = removeFromStaging(level, active.staging, material,
                        id("immersiveengineering:mold_plate"), 1);
                builder.setItemSlot(EquipmentSlot.MAINHAND, mold.copy());
                var result = ImmersiveEngineeringV1020MetalPressProduction.insertMold(
                        level, active.origin, mold);
                builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                if (!result.success()) throw new OrderFailure(result.code());
                checkpoint(level, order, MetalPressOrderStage.MOLD_INSTALLED,
                        "BOT_INSTALLED_PLATE_MOLD");
            }
            case MOLD_INSTALLED -> {
                var existingPower = ImmersiveEngineeringV1020MetalPressProduction.observePower(
                        level, active.origin);
                if (existingPower.externalConnections() == 1) {
                    consumeTransaction(level, active.projectId, WIRE);
                    checkpoint(level, order, MetalPressOrderStage.POWER_NETWORK_BUILT,
                            "RECOVERED_REAL_THERMOELECTRIC_LV_NETWORK");
                    return;
                }
                removeInstalledFromStaging(level, active.staging, material, POWER_INSTALLATION);
                ItemStack wire = removeFromStaging(level, active.staging, material, WIRE, 1);
                builder.setItemSlot(EquipmentSlot.MAINHAND, wire.copy());
                var result = ImmersiveEngineeringV1020MetalPressProduction.buildPowerNetwork(
                        level, active.origin, wire);
                builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                if (!result.success() || result.consumedWireCoils() != 1) {
                    throw new OrderFailure(result.code());
                }
                consumeTransaction(level, active.projectId, WIRE);
                checkpoint(level, order, MetalPressOrderStage.POWER_NETWORK_BUILT,
                        "BOT_BUILT_REAL_THERMOELECTRIC_LV_NETWORK");
            }
            case POWER_NETWORK_BUILT -> {
                var power = ImmersiveEngineeringV1020MetalPressProduction.observePower(
                        level, active.origin);
                if (!power.verified() || power.storedEnergyFe() < order.expectedEnergyFe()) return;
                order = checkpoint(level, order, MetalPressOrderStage.POWER_VERIFIED,
                        "MACHINE_RECEIVED_REAL_FE:" + power.storedEnergyFe());
                var stopped = ImmersiveEngineeringV1020MetalPressProduction.stopThermalGeneration(
                        level, active.origin);
                if (!stopped.success()) throw new OrderFailure(stopped.code());
                active.lastEnergy = -1;
                active.stableTicks = 0;
            }
            case POWER_VERIFIED -> {
                int energy = ImmersiveEngineeringV1020MetalPressProduction.inspect(level, active.origin)
                        .orElseThrow().state().getEnergy().getEnergyStored();
                var resources = IndustrialResourceCheckpointSavedData.forLevel(level);
                boolean bindingExists = resources.binding(projectId(active.projectId)).isPresent();
                if (bindingExists && energy < order.expectedEnergyFe()) {
                    if (!ImmersiveEngineeringV1020MetalPressProduction
                            .thermalGenerationActive(level, active.origin)) {
                        var started = ImmersiveEngineeringV1020MetalPressProduction
                                .startThermalGeneration(level, active.origin);
                        if (!started.success()) throw new OrderFailure(started.code());
                    }
                    active.lastEnergy = -1;
                    active.stableTicks = 0;
                    return;
                }
                if (bindingExists && ImmersiveEngineeringV1020MetalPressProduction
                        .thermalGenerationActive(level, active.origin)) {
                    var stopped = ImmersiveEngineeringV1020MetalPressProduction
                            .stopThermalGeneration(level, active.origin);
                    if (!stopped.success()) throw new OrderFailure(stopped.code());
                    active.lastEnergy = -1;
                    active.stableTicks = 0;
                    return;
                }
                active.stableTicks = energy == active.lastEnergy ? active.stableTicks + 1 : 0;
                active.lastEnergy = energy;
                if (active.stableTicks < 10) return;
                // Freeze the electrical snapshot only after the stopped generator and IE wire
                // network have drained every queued transfer. Otherwise a shutdown in this
                // window persists a different storage value than the resource binding recorded.
                if (!bindingExists) {
                    ensureResourceBinding(level, active, order, material);
                    return;
                }
                ItemStack iron = removeFromStaging(level, active.staging, material, IRON, 1);
                builder.setItemSlot(EquipmentSlot.MAINHAND, iron.copy());
                var fed = ImmersiveEngineeringV1020MetalPressProduction.feedInput(
                        level, active.origin, iron);
                builder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                if (!fed.success()) throw new OrderFailure(fed.code());
                consumeTransaction(level, active.projectId, IRON);
                order = effect(level, order, MetalPressDurableEffect.INPUT_ADMISSION,
                        fed.energyAtInput(), "BOT_ADMITTED_ONE_IRON_INGOT");
                checkpoint(level, order, MetalPressOrderStage.PROCESSING,
                        "METAL_PRESS_PROCESSING");
            }
            case INPUT_QUEUED -> checkpoint(level, order,
                    MetalPressOrderStage.PROCESSING, "METAL_PRESS_PROCESSING");
            case PROCESSING -> {
                var observed = ImmersiveEngineeringV1020MetalPressProduction.observeUniqueOutput(
                        level, active.origin, stack(OUTPUT, 1), Math.toIntExact(order.energyAtInputFe()));
                if (observed.outputCount() == 0 && active.recoveredProcessing) {
                    if (!ImmersiveEngineeringV1020MetalPressProduction
                            .thermalGenerationActive(level, active.origin)) {
                        var started = ImmersiveEngineeringV1020MetalPressProduction
                                .startThermalGeneration(level, active.origin);
                        if (!started.success()) throw new OrderFailure(started.code());
                    }
                    return;
                }
                if (observed.code().equals("OUTPUT_NOT_YET_OBSERVED")) return;
                if (active.recoveredProcessing) {
                    if (observed.outputCount() != 1) throw new OrderFailure(observed.code());
                    if (ImmersiveEngineeringV1020MetalPressProduction
                            .thermalGenerationActive(level, active.origin)) {
                        var stopped = ImmersiveEngineeringV1020MetalPressProduction
                                .stopThermalGeneration(level, active.origin);
                        if (!stopped.success()) throw new OrderFailure(stopped.code());
                    }
                } else if (!observed.verified()) {
                    throw new OrderFailure(observed.code());
                }
                order = effect(level, order, MetalPressDurableEffect.ENERGY_SETTLEMENT,
                        order.expectedEnergyFe(), active.recoveredProcessing
                                ? "PERSISTED_IE_PROCESS_COMPLETED_2400_FE_SETTLED"
                                : "REAL_2400_FE_SETTLED");
                settleResourceEnergy(level, order);
                // Stop here deliberately. ENERGY_SETTLED is a durable crash boundary:
                // the real machine has spent 2400 FE and produced one plate, but that
                // entity has not yet been claimed or delivered. Recovery therefore
                // never has to guess whether it should spend energy or mint output.
            }
            case ENERGY_SETTLED -> {
                long delivered = deliveredOutputCount(level, active.orderId);
                if (delivered == 0) {
                    // ENERGY_SETTLED is itself the durable proof of the exact 2400 FE
                    // observation. IE 10.2 does not persist the press's internal FE
                    // buffer, so recomputing the delta after a JVM restart is invalid.
                    // The claim primitive still requires exactly one live output item.
                    deliverOutput(level, active,
                            ImmersiveEngineeringV1020MetalPressProduction.claimUniqueOutput(
                                    level, active.origin, stack(OUTPUT, 1)));
                    delivered = 1;
                }
                if (delivered != 1) throw new OrderFailure("DELIVERED_OUTPUT_NOT_UNIQUE");
                settleResourceEnergy(level, order);
                effect(level, order, MetalPressDurableEffect.OUTPUT_CLAIM,
                        1, "RECOVERED_UNIQUE_IRON_PLATE_CLAIM");
            }
            case OUTPUT_OBSERVED -> checkpoint(level, order,
                    MetalPressOrderStage.TEARDOWN, "BOT_TEARDOWN_STARTED");
            case TEARDOWN -> {
                restoreBaseline(level, stored);
                if (!baselineMatches(level, stored)) throw new OrderFailure("BASELINE_RESTORE_MISMATCH");
                effect(level, order, MetalPressDurableEffect.BASELINE_RESTORE,
                        1, "BASELINE_RESTORED");
            }
            case BASELINE_RESTORED -> {
                if (!returnLeasedMaterials(player, active.projectId)) {
                    throw new OrderFailure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
                }
                effect(level, order, MetalPressDurableEffect.MATERIAL_RETURN,
                        1, "LEASED_MATERIALS_RETURNED");
            }
            case MATERIALS_RETURNED -> generateReports(player, level, active, order);
            case REPORT_GENERATED -> {
                syncCommonReport(player, order);
                MetalPressProductionOrder completed = checkpoint(level, order,
                        MetalPressOrderStage.COMPLETED, "ORDER_COMPLETED");
                builder.discard();
                PlayerConstructionService.discardCourierEntities(level, active.projectId);
                ACTIVE.remove(completed.orderId());
            }
            default -> { }
        }
    }

    private static void recoverMissing(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        ServerLevel level = server.overworld();
        for (StoredOrder stored : MetalPressOrderSavedData.forLevel(level).orders().values()) {
            MetalPressProductionOrder order = stored.order();
            if (ACTIVE.containsKey(order.orderId()) || order.stage() == MetalPressOrderStage.COMPLETED
                    || order.stage() == MetalPressOrderStage.CANCELLED
                    || order.stage() == MetalPressOrderStage.PAUSED) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(order.playerId());
            if (player == null) continue;
            recoverStored(player, stored);
        }
    }

    /** Test/login bridge; production still discovers online owners through {@link #recoverMissing}. */
    static boolean recoverForOwner(ServerPlayer player) {
        StoredOrder stored = MetalPressOrderSavedData.forLevel(player.serverLevel()).orders().values()
                .stream().filter(value -> value.order().playerId().equals(player.getUUID()))
                .max(Comparator.comparingLong(value -> value.order().updatedAt())).orElse(null);
        return stored != null && (ACTIVE.containsKey(stored.order().orderId())
                || recoverStored(player, stored));
    }

    private static boolean recoverStored(ServerPlayer player, StoredOrder stored) {
        ServerLevel level = player.serverLevel();
        MetalPressProductionOrder order = stored.order();
        if (ACTIVE.containsKey(order.orderId()) || order.stage() == MetalPressOrderStage.COMPLETED
                || order.stage() == MetalPressOrderStage.CANCELLED
                || order.stage() == MetalPressOrderStage.PAUSED) return false;
        Entry entry = PlayerMaterialSavedData.forLevel(level).entry(order.projectId()).orElse(null);
        if (entry == null || entry.logistics() == null) {
            pause(level, order.orderId(), "MATERIAL_RECONCILIATION_REQUIRED");
            return false;
        }
        if (IndustrialResourceCheckpointSavedData.forLevel(level)
                .binding(projectId(order.projectId())).isPresent()) {
            String code = restoreResourceBinding(level, stored);
            if (!code.equals("RESOURCE_RUNTIME_RESTORED")) {
                pause(level, order.orderId(), "RESOURCE_RELOAD_RECONCILIATION_REQUIRED:" + code);
                return false;
            }
        }
        BlockPos staging = block(entry.logistics().staging());
        List<BlockPos> positions = stored.baseline().stream().map(value ->
                block(value.position())).toList();
        DeploymentBoundingBox bounds = bounds(positions, block(order.materialSource()),
                player.blockPosition());
        Active active = new Active(order.orderId(), order.playerId(), order.projectId(),
                block(order.machineOrigin()), staging, bounds, null,
                persistedCourierId(level, order.projectId()), null, -1, 0, player,
                order.stage() == MetalPressOrderStage.PROCESSING);
        if (active.recoveredProcessing) {
            var process = ImmersiveEngineeringV1020MetalPressProduction.processingSnapshot(
                    level, active.origin, Math.toIntExact(order.energyAtInputFe()));
            if (!process.machinePresent() || process.queueSize() != 1 || process.processTick() < 1) {
                pause(level, order.orderId(), "PROCESSING_QUEUE_RELOAD_RECONCILIATION_REQUIRED");
                return false;
            }
            if (!process.recipeId().equals(order.recipeId().toString())) {
                pause(level, order.orderId(), "PROCESSING_RECIPE_RELOAD_RECONCILIATION_REQUIRED");
                return false;
            }
        }
        if (order.stage() == MetalPressOrderStage.MATERIALS_RESERVED) {
            try {
                if (entry.transactions().stream().allMatch(value ->
                        value.state() == MaterialTransactionState.DELIVERED)) {
                    order = effect(level, order, MetalPressDurableEffect.MATERIAL_WITHDRAWAL,
                            1, "RECOVERED_MATERIAL_WITHDRAWAL_BATCH");
                    checkpoint(level, order, MetalPressOrderStage.MATERIALS_DELIVERED,
                            "RECOVERED_ALL_MATERIALS_DELIVERED");
                } else {
                    active.courier = PlayerConstructionService.MaterialCourier.resume(level,
                            order.projectId(), entry, pos(staging), bounds)
                            .retainAfterDelivery();
                }
            } catch (RuntimeException failure) {
                pause(level, order.orderId(), "MATERIAL_COURIER_RECOVERY_DIVERGED");
                return false;
            }
        }
        if (order.stage() == MetalPressOrderStage.MATERIALS_WITHDRAWN
                && !allTransactions(entry, MaterialTransactionState.DELIVERED)) {
            pause(level, order.orderId(), "MATERIAL_DELIVERY_RECONCILIATION_REQUIRED");
            return false;
        }
        ACTIVE.put(order.orderId(), active);
        return true;
    }

    public static StatusResult status(ServerPlayer player) {
        StoredOrder stored = MetalPressOrderSavedData.forLevel(player.serverLevel()).orders().values()
                .stream().filter(value -> value.order().playerId().equals(player.getUUID()))
                .max(Comparator.comparingLong(value -> value.order().updatedAt())).orElse(null);
        return stored == null ? new StatusResult(false, "ORDER_NOT_FOUND", null, null)
                : new StatusResult(true, stored.order().statusCode(), stored.order(),
                        PlayerMaterialSavedData.forLevel(player.serverLevel())
                                .entry(stored.order().projectId()).orElse(null));
    }

    /** Exact active courier readback for diagnostics; no courier or executor handle escapes. */
    public static java.util.Optional<PlayerConstructionService.LogisticsDiagnosticSnapshot>
            logisticsDiagnostic(UUID orderId) {
        Active active = ACTIVE.get(Objects.requireNonNull(orderId, "orderId"));
        return active == null || active.courier == null ? java.util.Optional.empty()
                : java.util.Optional.of(active.courier.diagnosticSnapshot());
    }

    public static StartResult cancel(ServerPlayer player) {
        StatusResult status = status(player);
        if (!status.success || status.order == null) return StartResult.failure("ORDER_NOT_FOUND");
        MetalPressProductionOrder order = status.order;
        if (order.stage() == MetalPressOrderStage.COMPLETED
                || order.stage() == MetalPressOrderStage.CANCELLED) {
            return StartResult.failure("ORDER_ALREADY_TERMINAL");
        }
        if (order.durableEffects().contains(MetalPressDurableEffect.INPUT_ADMISSION)) {
            return StartResult.failure("ORDER_COMMITTED_CANNOT_CANCEL_AFTER_INPUT");
        }
        StoredOrder stored = MetalPressOrderSavedData.forLevel(player.serverLevel())
                .order(order.orderId()).orElseThrow();
        Active active = ACTIVE.remove(order.orderId());
        Entry entry = PlayerMaterialSavedData.forLevel(player.serverLevel())
                .entry(order.projectId()).orElse(null);
        if (entry == null) return StartResult.failure("MATERIAL_LEDGER_MISSING");
        PlayerConstructionService.MaterialCourier courier = active == null ? null : active.courier;
        boolean withdrawn = entry.transactions().stream().anyMatch(value ->
                value.state() == MaterialTransactionState.WITHDRAWN);
        if (courier == null && withdrawn) {
            try {
                BlockPos staging = block(entry.logistics().staging());
                List<BlockPos> positions = stored.baseline().stream()
                        .map(value -> block(value.position())).toList();
                courier = PlayerConstructionService.MaterialCourier.resume(player.serverLevel(),
                        order.projectId(), entry, pos(staging),
                        bounds(positions, block(order.materialSource()), player.blockPosition()));
            } catch (RuntimeException failure) {
                pause(player.serverLevel(), order.orderId(), "WITHDRAWN_MATERIAL_RECOVERY_REQUIRED");
                return StartResult.failure("WITHDRAWN_MATERIAL_RECOVERY_REQUIRED");
            }
        }
        if (courier != null && !courier.cancelAndReturn()) {
            pause(player.serverLevel(), order.orderId(), "RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
            return StartResult.failure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
        }
        releasePreparedTransactions(player.serverLevel(), order.projectId());
        if (!canReturnLeasedMaterials(player, order.projectId())) {
            pause(player.serverLevel(), order.orderId(), "RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
            return StartResult.failure("RETURN_PENDING_SOURCE_FULL_OR_CHANGED");
        }
        restoreBaseline(player.serverLevel(), stored);
        if (!returnLeasedMaterials(player, order.projectId())) {
            pause(player.serverLevel(), order.orderId(), "RETURN_RECONCILIATION_REQUIRED");
            return StartResult.failure("RETURN_RECONCILIATION_REQUIRED");
        }
        // A courier failure removes the in-memory Active handle before the player can
        // cancel. Remove the persisted project-tagged courier as well, otherwise each
        // retry leaves an orphan Bot in the acceptance world and reload recovery sees
        // more than one candidate.
        PlayerConstructionService.discardCourierEntities(player.serverLevel(), order.projectId());
        discardOrderBots(player.serverLevel(), order.orderId());
        MetalPressProductionOrder cancelled = checkpoint(player.serverLevel(), order,
                MetalPressOrderStage.CANCELLED, "ORDER_CANCELLED_AND_BASELINE_RESTORED");
        IndustrialPlayerOrderService.cancel(player, order.projectId(), true);
        IndustrialResourceCheckpointSavedData.forLevel(player.serverLevel())
                .remove(projectId(order.projectId()));
        save(player.serverLevel());
        return new StartResult(true, "ORDER_CANCELLED", cancelled.orderId(), cancelled,
                PlayerMaterialSavedData.forLevel(player.serverLevel())
                        .entry(order.projectId()).orElse(null));
    }

    public static void clearServerState() { ACTIVE.clear(); }

    private static void generateReports(
            ServerPlayer player, ServerLevel level, Active active, MetalPressProductionOrder order) {
        Entry entry = PlayerMaterialSavedData.forLevel(level).entry(active.projectId).orElseThrow();
        long planned = entry.requirements().values().stream().mapToLong(Long::longValue).sum();
        long withdrawn = entry.transactions().stream().filter(value -> value.state()
                        != MaterialTransactionState.PREPARED
                        && value.state() != MaterialTransactionState.RELEASED)
                .mapToLong(Transaction::quantity).sum();
        long consumed = entry.transactions().stream().filter(value ->
                value.state() == MaterialTransactionState.CONSUMED)
                .mapToLong(Transaction::quantity).sum();
        long returned = entry.transactions().stream().filter(value ->
                value.state() == MaterialTransactionState.RETURNED)
                .mapToLong(Transaction::quantity).sum();
        long duplicateWithdrawals = duplicates(entry, MaterialTransactionState.WITHDRAWN);
        long duplicateReturns = duplicates(entry, MaterialTransactionState.RETURNED);
        long unaccounted = Math.abs(withdrawn - consumed - returned);
        boolean balanced = planned == withdrawn && unaccounted == 0
                && duplicateWithdrawals == 0 && duplicateReturns == 0;
        var materialReport = new PlayerMaterialSavedData.CompletionReport(planned, withdrawn,
                consumed, returned, 0, 1, 1, duplicateWithdrawals, duplicateReturns,
                unaccounted, 0, balanced);
        Entry reported = entry.withTransactions(entry.transactions(), Instant.now().toEpochMilli(),
                balanced ? "MATERIAL_LEDGER_BALANCED" : "MATERIAL_LEDGER_RECONCILIATION_REQUIRED",
                materialReport);
        PlayerMaterialSavedData.forLevel(level).put(reported);
        MetalPressCompletionReport report = new MetalPressCompletionReport(planned, withdrawn,
                consumed, returned, order.measuredEnergyConsumedFe(), order.exactOutputCount(),
                Math.toIntExact(duplicateWithdrawals), 0, 0, Math.toIntExact(duplicateReturns),
                unaccounted, 0, balanced, baselineMatches(level,
                        MetalPressOrderSavedData.forLevel(level).order(order.orderId()).orElseThrow()),
                order.baselineHash());
        settleResourceBinding(level, active, order);
        MetalPressProductionOrder reportedOrder = MetalPressOrderStateMachine.recordReport(
                order, report, now(order));
        put(level, reportedOrder);
        save(level);
        syncCommonReport(player, reportedOrder);
    }

    private static void syncCommonReport(
            ServerPlayer player, MetalPressProductionOrder order) {
        MetalPressCompletionReport report = order.report().orElseThrow(
                () -> new OrderFailure("METAL_PRESS_REPORT_MISSING"));
        var synced = IndustrialPlayerOrderService.completeWithReport(
                player, order.projectId(), report.toIndustrialReport());
        if (!synced.success()) throw new OrderFailure(synced.code());
    }

    private static void ensureResourceBinding(
            ServerLevel level, Active active, MetalPressProductionOrder order, Entry material) {
        ResourceId project = projectId(active.projectId);
        var data = IndustrialResourceCheckpointSavedData.forLevel(level);
        if (data.binding(project).isPresent()) return;
        if (active.courierId == null || active.builderId == null) {
            throw new OrderFailure("RESOURCE_LOGISTICS_WORKERS_NOT_READY");
        }
        String worldIdentity = PilotWorldMarkerSavedData.forLevel(level).marker()
                .map(PilotWorldMarkerSavedData.Marker::worldIdentity)
                .orElseThrow(() -> new OrderFailure("WORLD_NOT_AUTHORIZED"));
        var electrical = ImmersiveEngineeringV1020MetalPressProduction.observeResourceNetwork(
                level, active.origin, worldIdentity, 0).orElseThrow(
                        () -> new OrderFailure("RESOURCE_ELECTRICAL_ENDPOINT_NOT_OBSERVED"));
        ResourceId owner = ownerId(active.playerId);
        ResourceId warehouseId = id("steve_industrial:warehouse/metal_press_"
                + active.projectId.toString().replace("-", ""));
        Source source = material.sources().stream().findFirst().orElseThrow(
                () -> new OrderFailure("RESOURCE_WAREHOUSE_SOURCE_MISSING"));
        Map<WarehouseResourceKey, Long> contents = new LinkedHashMap<>();
        source.slots().forEach(slot -> contents.merge(new WarehouseResourceKey(
                GenericResourceType.ITEM, slot.identity().itemId(),
                slot.identity().payloadSha256()), slot.quantity(), Math::addExact));
        ResourceId endpointId = id("steve_industrial:warehouse_endpoint/"
                + source.position().x() + "_" + source.position().y() + "_" + source.position().z());
        long capacity = level.getBlockEntity(block(source.position())) instanceof Container container
                ? Math.multiplyExact((long) container.getContainerSize(), container.getMaxStackSize())
                : contents.values().stream().mapToLong(Long::longValue).sum();
        WarehouseEndpointSnapshot endpoint = new WarehouseEndpointSnapshot(endpointId, warehouseId,
                owner, worldIdentity, order.dimension(), source.position(), Optional.of(Direction6.UP),
                id(source.blockEntityType()), WarehouseEndpointType.ITEM_CONTAINER, contents,
                capacity, source.inventoryHash(), 0, Long.MAX_VALUE, true);
        GlobalInventoryGraph warehouse = new GlobalInventoryGraph(warehouseId, owner, worldIdentity,
                order.dimension(), List.of(endpoint), List.of(), 0);
        List<WarehouseReservationRequest> requests = material.reservations().stream()
                .sorted(Comparator.comparing(value -> value.reservationId().toString()))
                .map(value -> new WarehouseReservationRequest(requestId(value.reservationId()),
                        project, owner, new WarehouseResourceKey(GenericResourceType.ITEM,
                                value.identity().itemId(), value.identity().payloadSha256()),
                        value.quantity(), List.of(endpointId), Long.MAX_VALUE, 0))
                .toList();
        List<ResourceId> assignments = material.reservations().stream()
                .sorted(Comparator.comparing(value -> value.reservationId().toString()))
                .map(value -> assignmentId(value.reservationId())).toList();
        EntityLogisticsBindingV1 logistics = new EntityLogisticsBindingV1(
                id("steve_industrial:fleet/metal_press_"
                        + active.projectId.toString().replace("-", "")),
                id("steve_industrial:task_graph/metal_press_"
                        + active.projectId.toString().replace("-", "")),
                sha256("metal-press-logistics-v1|" + assignments),
                List.of(botId(active.courierId), botId(active.builderId)), assignments, 0,
                level.getGameTime());
        IndustrialResourceBindingV1 binding = new IndustrialResourceBindingV1(project, owner,
                worldIdentity, order.dimension(), warehouse, requests, logistics,
                Optional.of(electrical), Optional.empty(), Map.of(FE, 2_400L), Map.of());
        WarehouseReservationSystem reservations = new WarehouseReservationSystem(warehouse);
        long tick = Math.max(1, level.getGameTime());
        var allocated = reservations.reserveAtomically(requests, tick);
        if (!allocated.success()) throw new OrderFailure(allocated.failureCode());
        for (WarehouseAllocation allocation : allocated.allocations()) {
            reservations.advance(allocation.allocationId(), WarehouseReservationStatus.WITHDRAWN,
                    ++tick);
            reservations.advance(allocation.allocationId(), WarehouseReservationStatus.DELIVERED,
                    ++tick);
            MaterialTransactionState state = material.transactions().stream()
                    .filter(value -> requestId(value.reservationId()).equals(allocation.requestId()))
                    .findFirst().map(Transaction::state).orElse(MaterialTransactionState.DELIVERED);
            if (state == MaterialTransactionState.CONSUMED) {
                reservations.advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED,
                        ++tick);
            }
        }
        ProjectEnergyLedger energy = new ProjectEnergyLedger();
        energy.prepare(energyTransactionId(active.projectId), project,
                id("steve_industrial:task/metal_press_process"), ENERGY_SOURCE, FE, 2_400, tick);
        IndustrialResourceRuntimeV1 runtime = new IndustrialResourceRuntimeV1(binding, reservations,
                energy, new ProjectFluidLedger(), Optional.empty(), tick);
        data.put(binding, runtime.checkpoint(tick));
        save(level);
    }

    private static void settleResourceEnergy(ServerLevel level, MetalPressProductionOrder order) {
        ResourceId project = projectId(order.projectId());
        var data = IndustrialResourceCheckpointSavedData.forLevel(level);
        var binding = data.binding(project).orElseThrow(
                () -> new OrderFailure("RESOURCE_BINDING_MISSING"));
        var checkpoint = data.checkpoint(project).orElseThrow(
                () -> new OrderFailure("RESOURCE_CHECKPOINT_MISSING"));
        WarehouseReservationSystem warehouse = WarehouseReservationSystem.restore(
                binding.warehouse(), checkpoint.warehouse());
        ProjectEnergyLedger energy = ProjectEnergyLedger.restore(checkpoint.energy());
        long tick = Math.max(level.getGameTime(), checkpoint.savedTick());
        energy.settle(energyTransactionId(order.projectId()), tick);
        IndustrialResourceRuntimeV1 runtime = new IndustrialResourceRuntimeV1(binding, warehouse,
                energy, ProjectFluidLedger.restore(checkpoint.fluid()),
                checkpoint.logisticsSettlement(), tick);
        data.put(binding, runtime.checkpoint(tick));
        save(level);
    }

    private static void settleResourceBinding(
            ServerLevel level, Active active, MetalPressProductionOrder order) {
        ResourceId project = projectId(active.projectId);
        var data = IndustrialResourceCheckpointSavedData.forLevel(level);
        IndustrialResourceBindingV1 binding = data.binding(project).orElseThrow(
                () -> new OrderFailure("RESOURCE_BINDING_MISSING"));
        var checkpoint = data.checkpoint(project).orElseThrow(
                () -> new OrderFailure("RESOURCE_CHECKPOINT_MISSING"));
        WarehouseReservationSystem warehouse = WarehouseReservationSystem.restore(
                binding.warehouse(), checkpoint.warehouse());
        Entry material = PlayerMaterialSavedData.forLevel(level).entry(active.projectId).orElseThrow();
        long tick = Math.max(level.getGameTime(), checkpoint.savedTick());
        for (WarehouseAllocation allocation : warehouse.allocations().values()) {
            if (allocation.status() != WarehouseReservationStatus.DELIVERED) continue;
            MaterialTransactionState state = material.transactions().stream()
                    .filter(value -> requestId(value.reservationId()).equals(allocation.requestId()))
                    .findFirst().map(Transaction::state).orElseThrow();
            if (state == MaterialTransactionState.CONSUMED) {
                warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.CONSUMED,
                        ++tick);
            } else if (state == MaterialTransactionState.RETURNED) {
                warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.RETURN_PENDING,
                        ++tick);
                warehouse.advance(allocation.allocationId(), WarehouseReservationStatus.RETURNED,
                        ++tick);
            } else throw new OrderFailure("RESOURCE_WAREHOUSE_NOT_TERMINAL:" + state);
        }
        EntityLogisticsSettlementV1 logistics = new EntityLogisticsSettlementV1(
                binding.entityLogistics().sessionId(),
                binding.entityLogistics().taskGraphFingerprint(),
                binding.entityLogistics().assignmentIds(), 0, 0, 0);
        ProjectEnergyLedger energy = ProjectEnergyLedger.restore(checkpoint.energy());
        ProjectFluidLedger fluid = ProjectFluidLedger.restore(checkpoint.fluid());
        var settled = IndustrialResourceRecoveryV1.settle(
                binding, warehouse, logistics, energy, fluid);
        if (!settled.balanced()) throw new OrderFailure(settled.code());
        IndustrialResourceRuntimeV1 runtime = new IndustrialResourceRuntimeV1(binding, warehouse,
                energy, fluid, Optional.of(logistics), tick);
        data.put(binding, runtime.checkpoint(tick));
        save(level);
    }

    private static String restoreResourceBinding(ServerLevel level, StoredOrder stored) {
        MetalPressProductionOrder order = stored.order();
        ResourceId project = projectId(order.projectId());
        var data = IndustrialResourceCheckpointSavedData.forLevel(level);
        IndustrialResourceBindingV1 binding = data.binding(project).orElse(null);
        var checkpoint = data.checkpoint(project).orElse(null);
        if (binding == null || checkpoint == null) return "RESOURCE_CHECKPOINT_MISSING";
        try {
            GlobalInventoryGraph warehouse = observeBoundWarehouse(level, binding);
            EntityLogisticsBindingV1 logistics = observeBoundLogistics(level, binding);
            var electrical = ImmersiveEngineeringV1020MetalPressProduction.observeResourceNetwork(
                    level, block(order.machineOrigin()), binding.worldIdentity(),
                    binding.electricalNetwork().map(value -> value.generation()).orElse(0L))
                    .orElse(null);
            var restored = IndustrialResourceRuntimeV1.restore(binding, checkpoint, warehouse,
                    logistics, electrical, null,
                    Math.max(level.getGameTime(), checkpoint.savedTick()));
            if (!restored.restored()) {
                if (restored.code().equals("ELECTRICAL_CONTENTS_DRIFT")
                        && electrical != null && binding.electricalNetwork().isPresent()) {
                    var transaction = checkpoint.energy().transactions().values().stream()
                            .findFirst().orElse(null);
                    if (transaction != null) {
                        var initial = binding.electricalNetwork().orElseThrow().nodes()
                                .get(transaction.sourceEndpointId());
                        var observed = electrical.nodes().get(transaction.sourceEndpointId());
                        return restored.code() + ":initial="
                                + (initial == null ? "missing" : initial.storedEnergy())
                                + ":observed="
                                + (observed == null ? "missing" : observed.storedEnergy())
                                + ":state=" + transaction.state();
                    }
                }
                return restored.code();
            }
            IndustrialResourceRuntimeV1 runtime = restored.runtime().orElseThrow();
            data.put(binding, runtime.checkpoint(
                    Math.max(level.getGameTime(), checkpoint.savedTick())));
            save(level);
            return restored.code();
        } catch (RuntimeException failure) {
            String detail = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            return "RESOURCE_LIVE_REOBSERVATION_DIVERGED:" + detail;
        }
    }

    private static GlobalInventoryGraph observeBoundWarehouse(
            ServerLevel level, IndustrialResourceBindingV1 binding) {
        List<WarehouseEndpointSnapshot> endpoints = new ArrayList<>();
        for (WarehouseEndpointSnapshot expected : binding.warehouse().endpoints().values()) {
            BlockPos position = block(expected.position());
            if (!(level.getBlockEntity(position) instanceof Container container)) {
                throw new OrderFailure("RESOURCE_WAREHOUSE_ENDPOINT_MISSING");
            }
            ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                    level.getBlockEntity(position).getType());
            ResourceId observedType = id(type == null ? "minecraft:chest" : type.toString());
            if (!observedType.equals(expected.blockEntityType())) {
                throw new OrderFailure("RESOURCE_WAREHOUSE_ENDPOINT_CHANGED");
            }
            Map<WarehouseResourceKey, Long> contents = new LinkedHashMap<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                MaterialIdentity identity = identity(stack);
                contents.merge(new WarehouseResourceKey(GenericResourceType.ITEM,
                        identity.itemId(), identity.payloadSha256()),
                        (long) stack.getCount(), Math::addExact);
            }
            long capacity = Math.multiplyExact(
                    (long) container.getContainerSize(), container.getMaxStackSize());
            endpoints.add(new WarehouseEndpointSnapshot(expected.endpointId(),
                    expected.warehouseId(), expected.ownerId(), expected.worldIdentity(),
                    expected.dimension(), expected.position(), expected.accessFace(), observedType,
                    expected.endpointType(), contents, capacity,
                    sha256(position + "|" + contents), expected.generation(),
                    expected.expiresAtEpochMillis(), true));
        }
        return new GlobalInventoryGraph(binding.warehouse().warehouseId(), binding.ownerId(),
                binding.worldIdentity(), binding.dimension(), endpoints,
                List.copyOf(binding.warehouse().edges().values()),
                binding.warehouse().generation());
    }

    private static EntityLogisticsBindingV1 observeBoundLogistics(
            ServerLevel level, IndustrialResourceBindingV1 binding) {
        List<ResourceId> observed = new ArrayList<>();
        for (ResourceId worker : binding.entityLogistics().workerIds()) {
            String expected = worker.path().substring(worker.path().lastIndexOf('/') + 1);
            boolean present = false;
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ConstructionBotEntity bot && bot.isAlive()
                        && entity.getUUID().toString().equals(expected)) {
                    present = true;
                    break;
                }
            }
            if (!present) throw new OrderFailure("RESOURCE_LOGISTICS_WORKER_MISSING");
            observed.add(worker);
        }
        EntityLogisticsBindingV1 expected = binding.entityLogistics();
        return new EntityLogisticsBindingV1(expected.sessionId(), expected.taskGraphId(),
                expected.taskGraphFingerprint(), observed, expected.assignmentIds(),
                expected.generation(), level.getGameTime());
    }

    private static ResourceId projectId(UUID value) {
        return id("player_project:" + value.toString().replace("-", ""));
    }
    private static ResourceId ownerId(UUID value) { return id("player:" + value); }
    private static ResourceId botId(UUID value) { return id("steve_industrial:bot/" + value); }
    private static ResourceId requestId(UUID value) {
        return id("steve_industrial:material_request/" + value);
    }
    private static ResourceId assignmentId(UUID value) {
        return id("steve_industrial:assignment/" + value);
    }
    private static ResourceId energyTransactionId(UUID value) {
        return id("steve_industrial:energy_transaction/" + value);
    }

    private static boolean returnLeasedMaterials(ServerPlayer player, UUID projectId) {
        ServerLevel level = player.serverLevel();
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry entry = data.entry(projectId).orElse(null);
        if (entry == null) return false;
        Map<UUID, Source> sources = new LinkedHashMap<>();
        entry.sources().forEach(value -> sources.put(value.sourceId(), value));
        Map<UUID, Transaction> transactions = new LinkedHashMap<>();
        entry.transactions().forEach(value -> transactions.put(value.reservationId(), value));
        for (Reservation reservation : entry.reservations()) {
            if (CONSUMED.contains(reservation.identity().itemId())) continue;
            Transaction transaction = transactions.get(reservation.reservationId());
            if (transaction == null) return false;
            if (transaction.state() == MaterialTransactionState.RETURNED
                    || transaction.state() == MaterialTransactionState.RELEASED) continue;
            if (transaction.state() != MaterialTransactionState.DELIVERED
                    && transaction.state() != MaterialTransactionState.RETURN_PENDING) return false;
            Source source = sources.get(reservation.sourceId());
            if (source == null || !(level.getBlockEntity(block(source.position()))
                    instanceof Container destination)) return false;
            ItemStack current = destination.getItem(reservation.slot());
            int capacity = current.isEmpty() ? item(reservation.identity().itemId()).getMaxStackSize()
                    : identity(current).equals(reservation.identity())
                            ? current.getMaxStackSize() - current.getCount() : 0;
            if (capacity < reservation.quantity()) return false;
        }
        Entry currentEntry = entry;
        for (Reservation reservation : entry.reservations()) {
            if (CONSUMED.contains(reservation.identity().itemId())) continue;
            Transaction transaction = transactions.get(reservation.reservationId());
            if (transaction != null && (transaction.state() == MaterialTransactionState.RETURNED
                    || transaction.state() == MaterialTransactionState.RELEASED)) continue;
            Source source = sources.get(reservation.sourceId());
            Container destination = (Container) level.getBlockEntity(block(source.position()));
            ItemStack returned = stack(reservation.identity().itemId(),
                    Math.toIntExact(reservation.quantity()));
            ItemStack current = destination.getItem(reservation.slot());
            if (current.isEmpty()) destination.setItem(reservation.slot(), returned);
            else current.grow(returned.getCount());
            destination.setChanged();
            currentEntry = PlayerConstructionService.advanceTransaction(currentEntry,
                    reservation.reservationId(), MaterialTransactionState.RETURNED,
                    level.getGameTime(), "IE_ORDER_MATERIAL_RETURNED");
            data.put(currentEntry);
            save(level);
        }
        return true;
    }

    private static boolean canReturnLeasedMaterials(ServerPlayer player, UUID projectId) {
        ServerLevel level = player.serverLevel();
        Entry entry = PlayerMaterialSavedData.forLevel(level).entry(projectId).orElse(null);
        if (entry == null) return false;
        Map<UUID, Source> sources = new LinkedHashMap<>();
        entry.sources().forEach(value -> sources.put(value.sourceId(), value));
        Map<UUID, Transaction> transactions = new LinkedHashMap<>();
        entry.transactions().forEach(value -> transactions.put(value.reservationId(), value));
        for (Reservation reservation : entry.reservations()) {
            if (CONSUMED.contains(reservation.identity().itemId())) continue;
            Transaction transaction = transactions.get(reservation.reservationId());
            if (transaction == null) return false;
            if (transaction.state() == MaterialTransactionState.RETURNED
                    || transaction.state() == MaterialTransactionState.RELEASED) continue;
            if (transaction.state() != MaterialTransactionState.DELIVERED
                    && transaction.state() != MaterialTransactionState.RETURN_PENDING) return false;
            Source source = sources.get(reservation.sourceId());
            if (source == null || !(level.getBlockEntity(block(source.position()))
                    instanceof Container destination)) return false;
            ItemStack current = destination.getItem(reservation.slot());
            int capacity = current.isEmpty() ? item(reservation.identity().itemId()).getMaxStackSize()
                    : identity(current).equals(reservation.identity())
                            ? current.getMaxStackSize() - current.getCount() : 0;
            if (capacity < reservation.quantity()) return false;
        }
        return true;
    }

    private static void releasePreparedTransactions(ServerLevel level, UUID projectId) {
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry current = data.entry(projectId).orElseThrow();
        for (Transaction transaction : List.copyOf(current.transactions())) {
            if (transaction.state() != MaterialTransactionState.PREPARED) continue;
            current = PlayerConstructionService.advanceTransaction(current,
                    transaction.reservationId(), MaterialTransactionState.RELEASED,
                    level.getGameTime(), "IE_ORDER_RESERVATION_RELEASED");
            data.put(current);
            save(level);
        }
    }

    private static void consumeTransaction(ServerLevel level, UUID projectId, ResourceId item) {
        PlayerMaterialSavedData data = PlayerMaterialSavedData.forLevel(level);
        Entry entry = data.entry(projectId).orElseThrow();
        List<UUID> reservations = entry.reservations().stream().filter(value ->
                value.identity().itemId().equals(item)).map(Reservation::reservationId).toList();
        Entry current = entry;
        for (UUID reservation : reservations) {
            Transaction existing = current.transactions().stream().filter(value ->
                    value.reservationId().equals(reservation)).findFirst().orElseThrow();
            if (existing.state() == MaterialTransactionState.CONSUMED) continue;
            current = PlayerConstructionService.advanceTransaction(current, reservation,
                    MaterialTransactionState.CONSUMED, level.getGameTime(),
                    "IE_ORDER_MATERIAL_CONSUMED");
            data.put(current);
            save(level);
        }
    }

    private static void removeInstalledFromStaging(ServerLevel level, BlockPos staging,
            Entry entry, Set<ResourceId> ids) {
        for (ResourceId id : ids.stream().sorted(Comparator.comparing(ResourceId::toString)).toList()) {
            removeFromStaging(level, staging, entry, id, Math.toIntExact(
                    entry.requirements().getOrDefault(id, 0L)));
        }
    }

    private static ItemStack removeFromStaging(ServerLevel level, BlockPos staging,
            Entry entry, ResourceId id, int quantity) {
        if (quantity < 1 || !(level.getBlockEntity(staging) instanceof Container container)) {
            throw new OrderFailure("STAGING_MATERIAL_UNAVAILABLE:" + id);
        }
        int remaining = quantity;
        ItemStack result = stack(id, quantity);
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack current = container.getItem(slot);
            if (!current.isEmpty() && identity(current).itemId().equals(id)
                    && identity(current).payloadSha256().equals(MaterialIdentity.EMPTY_PAYLOAD_SHA256)) {
                int take = Math.min(remaining, current.getCount());
                container.removeItem(slot, take);
                remaining -= take;
            }
        }
        container.setChanged();
        if (remaining != 0) throw new OrderFailure("STAGING_MATERIAL_MISSING:" + id);
        return result;
    }

    private static void restoreBaseline(ServerLevel level, StoredOrder stored) {
        HolderLookup<net.minecraft.world.level.block.Block> lookup = level.holderLookup(Registries.BLOCK);
        for (BaselineBlock block : stored.baseline()) {
            level.setBlockAndUpdate(block(block.position()),
                    NbtUtils.readBlockState(lookup, block.serializedState()));
        }
    }

    private static boolean baselineMatches(ServerLevel level, StoredOrder stored) {
        HolderLookup<net.minecraft.world.level.block.Block> lookup = level.holderLookup(Registries.BLOCK);
        return stored.baseline().stream().allMatch(value -> level.getBlockState(block(value.position()))
                .equals(NbtUtils.readBlockState(lookup, value.serializedState())));
    }

    private static void deliverOutput(ServerLevel level, Active active, ItemStack output) {
        BlockPos source = MetalPressOrderSavedData.forLevel(level).order(active.orderId)
                .map(value -> block(value.order().materialSource())).orElseThrow();
        ItemEntity entity = new ItemEntity(level, source.getX() + 0.5D, source.getY() + 1.25D,
                source.getZ() + 0.5D, output);
        entity.setPickUpDelay(20);
        entity.addTag(ORDER_TAG + active.orderId.toString().replace("-", ""));
        if (!level.addFreshEntity(entity)) throw new OrderFailure("OUTPUT_DELIVERY_FAILED");
    }

    private static long deliveredOutputCount(ServerLevel level, UUID orderId) {
        String tag = ORDER_TAG + orderId.toString().replace("-", "");
        long count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity itemEntity && entity.isAlive()
                    && entity.getTags().contains(tag)
                    && identity(itemEntity.getItem()).itemId().equals(OUTPUT)) {
                count += itemEntity.getItem().getCount();
            }
        }
        return count;
    }

    private static boolean allTransactions(Entry entry, MaterialTransactionState state) {
        return !entry.transactions().isEmpty()
                && entry.transactions().stream().allMatch(value -> value.state() == state);
    }

    private static ConstructionBotEntity ensureBuilder(
            ServerLevel level, Active active, ServerPlayer player) {
        if (active.builderId != null && level.getEntity(active.builderId)
                instanceof ConstructionBotEntity bot && bot.isAlive()) return bot;
        String tag = ORDER_TAG + active.orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ConstructionBotEntity bot && bot.getTags().contains(BUILDER_TAG)
                    && bot.getTags().contains(tag)) {
                active.builderId = bot.getUUID();
                return bot;
            }
        }
        ConstructionBotEntity bot = Objects.requireNonNull(
                ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level));
        bot.setRole(ConstructionBotEntity.Role.BUILDER_INSPECTOR);
        bot.moveTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        bot.setCustomName(Component.literal("Steve · IE Metal Press Builder"));
        bot.setCustomNameVisible(true);
        bot.addTag(BUILDER_TAG);
        bot.addTag(tag);
        if (!level.addFreshEntity(bot)) throw new OrderFailure("BUILDER_BOT_SPAWN_FAILED");
        active.builderId = bot.getUUID();
        return bot;
    }

    private static void discardOrderBots(ServerLevel level, UUID orderId) {
        String tag = ORDER_TAG + orderId.toString().replace("-", "");
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(tag)) entity.discard();
        }
    }

    private static UUID persistedCourierId(ServerLevel level, UUID projectId) {
        String tag = "material_project_" + projectId.toString().replace("-", "");
        UUID found = null;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ConstructionBotEntity bot) || !bot.isAlive()
                    || !entity.getTags().contains(tag)) continue;
            if (found != null) throw new OrderFailure("MULTIPLE_PERSISTED_MATERIAL_COURIERS");
            found = entity.getUUID();
        }
        return found;
    }

    private static BlockPos workCell(ServerLevel level, BlockPos origin) {
        for (int radius = 2; radius <= 5; radius++) {
            for (BlockPos candidate : List.of(origin.offset(radius, 0, 0),
                    origin.offset(-radius, 0, 0), origin.offset(0, 0, radius),
                    origin.offset(0, 0, -radius))) {
                if (level.getBlockState(candidate).isAir()
                        && level.getBlockState(candidate.above()).isAir()) return candidate;
            }
        }
        throw new OrderFailure("BUILDER_WORK_CELL_UNAVAILABLE");
    }

    private static boolean at(ConstructionBotEntity bot, BlockPos target) {
        double dx = bot.getX() - (target.getX() + 0.5D);
        double dy = bot.getY() - target.getY();
        double dz = bot.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz < 1.0E-6D;
    }

    private static boolean placeStaging(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).isAir()
                && level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
                && level.getBlockEntity(position) instanceof ChestBlockEntity;
    }

    private static MetalPressProductionOrder checkpoint(ServerLevel level,
            MetalPressProductionOrder order, MetalPressOrderStage stage, String code) {
        MetalPressProductionOrder next = MetalPressOrderStateMachine.checkpoint(
                order, stage, code, now(order));
        put(level, next);
        save(level);
        return next;
    }

    private static MetalPressProductionOrder effect(ServerLevel level,
            MetalPressProductionOrder order, MetalPressDurableEffect effect,
            long observed, String code) {
        MetalPressProductionOrder next = MetalPressOrderStateMachine.recordEffect(
                order, effect, observed, code, now(order));
        put(level, next);
        save(level);
        return next;
    }

    private static void pause(ServerLevel level, UUID orderId, String code) {
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        StoredOrder stored = data.order(orderId).orElse(null);
        if (stored == null) return;
        MetalPressProductionOrder paused = MetalPressOrderStateMachine.checkpoint(
                stored.order(), MetalPressOrderStage.PAUSED, code, now(stored.order()));
        data.put(stored.withOrder(paused));
        save(level);
    }

    private static void put(ServerLevel level, MetalPressProductionOrder order) {
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        data.put(data.order(order.orderId()).orElseThrow().withOrder(order));
    }

    private static long now(MetalPressProductionOrder order) {
        return Math.max(Instant.now().toEpochMilli(), order.updatedAt());
    }

    private static void save(ServerLevel level) {
        level.getServer().overworld().getDataStorage().save();
    }

    private static long duplicates(Entry entry, MaterialTransactionState state) {
        return entry.journal().stream().filter(value -> value.state() == state)
                .collect(java.util.stream.Collectors.groupingBy(
                        PlayerMaterialSavedData.JournalEvent::transactionId,
                        java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(value -> Math.max(0, value - 1)).sum();
    }

    private static DeploymentBoundingBox bounds(
            List<BlockPos> positions, BlockPos source, BlockPos player) {
        ArrayList<BlockPos3i> values = new ArrayList<>();
        positions.forEach(value -> values.add(pos(value)));
        values.add(pos(source));
        values.add(pos(player));
        return DeploymentBoundingBox.enclosing(values);
    }

    private static String planHash(String runtime) {
        StringBuilder value = new StringBuilder(RECIPE.toString()).append('\n').append(runtime);
        REQUIREMENTS.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(row -> value.append('\n')
                .append(row.getKey()).append('=').append(row.getValue()));
        return sha256(value.toString());
    }

    private static String baselineHash(ServerLevel level, List<BaselineBlock> baseline) {
        StringBuilder value = new StringBuilder();
        baseline.forEach(row -> value.append(row.position()).append('=')
                .append(row.serializedState()).append('\n'));
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ItemStack stack(ResourceId id, int quantity) {
        return new ItemStack(item(id), quantity);
    }

    private static Item item(ResourceId id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id.toString()));
        if (item == null) throw new OrderFailure("ITEM_UNAVAILABLE:" + id);
        return item;
    }

    private static MaterialIdentity identity(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) throw new OrderFailure("UNREGISTERED_ITEM");
        String payload = PlayerMaterialService.canonicalPayload(stack);
        return new MaterialIdentity(ResourceId.parse(id.toString()), payload);
    }

    private static Comparator<BlockPos> positionOrder() {
        return Comparator.comparingInt((BlockPos value) -> value.getX())
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
    private static BlockPos3i pos(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }
    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    public record StartResult(boolean success, String code, UUID orderId,
            MetalPressProductionOrder order, Entry material) {
        static StartResult failure(String code) { return new StartResult(false, code, null, null, null); }
    }

    public record StatusResult(boolean success, String code,
            MetalPressProductionOrder order, Entry material) {}

    private static final class Active {
        private final UUID orderId;
        private final UUID playerId;
        private final UUID projectId;
        private final BlockPos origin;
        private final BlockPos staging;
        private final DeploymentBoundingBox bounds;
        private PlayerConstructionService.MaterialCourier courier;
        private final UUID courierId;
        private UUID builderId;
        private int lastEnergy;
        private int stableTicks;
        private final ServerPlayer livePlayer;
        private final boolean recoveredProcessing;

        private Active(UUID orderId, UUID playerId, UUID projectId, BlockPos origin,
                BlockPos staging, DeploymentBoundingBox bounds,
                PlayerConstructionService.MaterialCourier courier, UUID courierId, UUID builderId,
                int lastEnergy, int stableTicks, ServerPlayer livePlayer,
                boolean recoveredProcessing) {
            this.orderId = orderId;
            this.playerId = playerId;
            this.projectId = projectId;
            this.origin = origin;
            this.staging = staging;
            this.bounds = bounds;
            this.courier = courier;
            this.courierId = courierId;
            this.builderId = builderId;
            this.lastEnergy = lastEnergy;
            this.stableTicks = stableTicks;
            this.livePlayer = livePlayer;
            this.recoveredProcessing = recoveredProcessing;
        }
    }

    private static final class OrderFailure extends RuntimeException {
        private OrderFailure(String code) { super(code); }
    }

}
