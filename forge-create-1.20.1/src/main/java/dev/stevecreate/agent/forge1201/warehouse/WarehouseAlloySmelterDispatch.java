package dev.stevecreate.agent.forge1201.warehouse;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.UnattendedProductionOrderScheduler;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020Adapter;
import dev.stevecreate.agent.forge1201.command.AlloySmelterProductionService;
import dev.stevecreate.agent.forge1201.command.WarehouseDiscovery;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.AlloySmelterOrderSavedData.StoredOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Long-running warehouse admission for the reviewed physical IE Alloy Smelter order.
 *
 * <p>It does not withdraw or move material. It chooses one exact warehouse container,
 * then delegates the whole reservation/courier/build/process/return/report lifecycle to
 * {@link AlloySmelterProductionService}. The batch identity is persisted on that order,
 * so a fresh process can reconstruct the same actor, resume it and settle exactly once.</p>
 */
public final class WarehouseAlloySmelterDispatch implements WarehouseOrderService.WarehouseRuntime {
    private static final double SITE_REACH_SQUARED = 32.0 * 32.0;
    private final ServerLevel level;
    private final BlockPos warehouseCentre;
    private final int radius;
    private final List<BlockPos> siteOrigins;
    private final ResourceId warehouseId;
    private final List<Attempt> attempts = new ArrayList<>();
    private BlockPos lastOutputDestination;

    public WarehouseAlloySmelterDispatch(
            ServerLevel level,
            ResourceId warehouseId,
            BlockPos warehouseCentre,
            int radius,
            List<BlockPos> siteOrigins) {
        this.level = Objects.requireNonNull(level, "level");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.warehouseCentre = Objects.requireNonNull(warehouseCentre, "warehouseCentre").immutable();
        this.radius = radius;
        this.siteOrigins = siteOrigins.stream().map(BlockPos::immutable).toList();
        if (this.siteOrigins.isEmpty()) throw new IllegalArgumentException("no Alloy site given");
    }

    @Override
    public long observedStock(WarehouseResourceKey target) {
        if (!WarehouseResourceKey.EMPTY_COMPONENT_SHA256.equals(target.componentSha256())) return 0;
        return WarehouseDiscovery.candidates(level, warehouseCentre, radius, List.of()).stream()
                .mapToLong(candidate -> candidate.contents().getOrDefault(target.resourceId(), 0L))
                .sum();
    }

    @Override
    public WarehouseOrderService.DispatchResult dispatch(
            UnattendedProductionOrderScheduler.BatchAuthorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        String authorityFailure = validateAuthority(authorization);
        if (authorityFailure != null) return refused(authorization, authorityFailure);

        StoredOrder bound = boundOrder(authorization.orderId(), authorization.batchId()).orElse(null);
        if (bound != null) {
            lastOutputDestination = block(bound.warehouseBinding().orElseThrow().outputDestination());
            attempts.add(new Attempt(authorization.batchId(), true,
                    "WAREHOUSE_ALLOY_BATCH_ALREADY_BOUND", bound.orderId()));
            return new WarehouseOrderService.DispatchResult(
                    true, "WAREHOUSE_ALLOY_BATCH_ALREADY_BOUND");
        }
        boolean anotherActive = AlloySmelterOrderSavedData.forLevel(level).orders().values().stream()
                .filter(value -> value.warehouseBinding().isPresent())
                .filter(value -> value.warehouseBinding().orElseThrow().warehouseId()
                        .equals(warehouseId))
                .anyMatch(value -> AlloySmelterProductionService.findOrder(level, value.orderId())
                        .map(order -> !terminal(order.phase())).orElse(true));
        if (anotherActive) return refused(authorization, "WAREHOUSE_ALLOY_ORDER_ALREADY_ACTIVE");

        var reviewed = AlloySmelterProductionService.requirements(level);
        if (!reviewed.success()) {
            return refused(authorization, "WAREHOUSE_ALLOY_REVIEW_REFUSED:" + reviewed.code());
        }
        List<BlockPos> candidates = WarehouseDiscovery.candidates(
                level, warehouseCentre, radius, List.of()).stream()
                .map(value -> new BlockPos(value.position().x(), value.position().y(),
                        value.position().z()))
                .filter(position -> level.getBlockEntity(position) instanceof Container container
                        && canSupply(container, reviewed.requirements())
                        && hasOutputSlot(container))
                .sorted(Comparator.comparingInt((BlockPos value) -> value.getX())
                        .thenComparingInt(value -> value.getY())
                        .thenComparingInt(value -> value.getZ()))
                .toList();
        if (candidates.isEmpty()) {
            return refused(authorization, "NO_SINGLE_WAREHOUSE_SOURCE_WITH_FULL_ALLOY_BILL");
        }

        String lastCode = "NO_REACHABLE_ALLOY_SITE";
        for (BlockPos source : candidates) {
            FakePlayer actor = actor();
            actor.getAbilities().mayBuild = true;
            BlockPos actorCell = standableBeside(source);
            actor.moveTo(actorCell.getX() + 0.5D, actorCell.getY(),
                    actorCell.getZ() + 0.5D, 0, 0);
            for (BlockPos site : siteOrigins) {
                if (actor.distanceToSqr(site.getX() + 0.5D, site.getY() + 0.5D,
                        site.getZ() + 0.5D) > SITE_REACH_SQUARED) continue;
                var started = AlloySmelterProductionService.createForWarehouse(
                        actor, source, site, warehouseId, authorization.orderId(),
                        authorization.batchId(), source);
                if (started.success()) {
                    lastOutputDestination = source;
                    attempts.add(new Attempt(authorization.batchId(), true,
                            started.code(), started.orderId()));
                    return new WarehouseOrderService.DispatchResult(true, started.code());
                }
                lastCode = started.code();
            }
        }
        return refused(authorization, lastCode);
    }

    @Override
    public void tick(MinecraftServer server) {
        for (ProductionOrder production : WarehouseOrderSavedData.forLevel(level)
                .orders().values().stream().filter(value -> value.warehouseId().equals(warehouseId))
                .filter(value -> value.status() == ProductionOrderStatus.BATCH_IN_FLIGHT).toList()) {
            ResourceId batchId = production.inFlightBatchId().orElseThrow();
            StoredOrder stored = boundOrder(production.orderId(), batchId).orElse(null);
            if (stored == null) {
                // Crash-safe edge: scheduler authority was saved before physical order binding.
                var replay = new UnattendedProductionOrderScheduler.BatchAuthorization(
                        batchId, production.orderId(), production.warehouseId(),
                        production.approvedProductionGraphId(), production.approvedRecipeIds(),
                        production.approvedAdapterIds(), production.approvedSiteIds(), 1,
                        production.verifiedBatchOutput(), production.nextEligibleTick());
                WarehouseOrderService.DispatchResult result = dispatch(replay);
                if (!result.accepted()) {
                    WarehouseOrderService.batchFailed(server, production.orderId(), batchId,
                            result.statusCode());
                }
                continue;
            }
            var binding = stored.warehouseBinding().orElseThrow();
            lastOutputDestination = block(binding.outputDestination());
            FakePlayer actor = actor();
            actor.getAbilities().mayBuild = true;
            BlockPos source = block(stored.materialSource());
            BlockPos actorCell = standableBeside(source);
            actor.moveTo(actorCell.getX() + 0.5D, actorCell.getY(),
                    actorCell.getZ() + 0.5D, 0, 0);
            var beforeRecovery = AlloySmelterProductionService.findOrder(
                    level, stored.orderId()).orElse(null);
            if (beforeRecovery != null
                    && beforeRecovery.phase() != IndustrialLifecyclePhase.COMPLETED
                    && beforeRecovery.phase() != IndustrialLifecyclePhase.RECOVERED
                    && beforeRecovery.phase() != IndustrialLifecyclePhase.CANCELLED
                    && beforeRecovery.phase() != IndustrialLifecyclePhase.FAILED
                    && (beforeRecovery.phase() != IndustrialLifecyclePhase.PAUSED
                            || beforeRecovery.stage().equals(
                                    "RELOAD_RECONCILIATION_REQUIRED"))) {
                AlloySmelterProductionService.recoverForOrder(actor, stored.orderId());
            }
            var common = AlloySmelterProductionService.findOrder(
                    level, stored.orderId()).orElse(null);
            if (common == null) {
                WarehouseOrderService.batchFailed(server, production.orderId(), batchId,
                        "WAREHOUSE_ALLOY_COMMON_ORDER_MISSING");
                continue;
            }
            if (common.phase() == IndustrialLifecyclePhase.COMPLETED) {
                boolean accepted = common.report().map(report -> report.accepted(
                        Map.of(), Map.of(stored.output(), (long) stored.outputCount())))
                        .orElse(false);
                if (!accepted) {
                    WarehouseOrderService.batchFailed(server, production.orderId(), batchId,
                            "WAREHOUSE_ALLOY_REPORT_NOT_ACCEPTED");
                    continue;
                }
                WarehouseOrderService.batchSucceeded(server, production.orderId(), batchId);
                continue;
            }
            if (common.phase() == IndustrialLifecyclePhase.PAUSED
                    || common.phase() == IndustrialLifecyclePhase.FAILED
                    || common.phase() == IndustrialLifecyclePhase.CANCELLED) {
                WarehouseOrderService.batchFailed(server, production.orderId(), batchId,
                        "WAREHOUSE_ALLOY_" + common.phase() + ":" + common.stage());
            }
        }
    }

    @Override
    public Optional<WarehouseOrderService.RuntimeDiagnostic> diagnostic() {
        return Optional.of(new WarehouseOrderService.RuntimeDiagnostic(
                Optional.ofNullable(lastOutputDestination).map(WarehouseAlloySmelterDispatch::cell),
                Optional.empty()));
    }

    public Optional<Attempt> latest() {
        return attempts.isEmpty() ? Optional.empty()
                : Optional.of(attempts.get(attempts.size() - 1));
    }

    private String validateAuthority(
            UnattendedProductionOrderScheduler.BatchAuthorization authorization) {
        ResourceId recipe = ResourceId.parse(
                dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020
                        .ImmersiveEngineeringV1020AlloySmelterProduction.REVIEWED_RECIPE.toString());
        if (!authorization.warehouseId().equals(warehouseId)) return "WAREHOUSE_ID_MISMATCH";
        if (!authorization.productionGraphId().equals(AlloySmelterProductionService.ORDER_TYPE)) {
            return "WAREHOUSE_ALLOY_ORDER_TYPE_MISMATCH";
        }
        if (authorization.batches() != 1 || authorization.expectedOutput() != 2) {
            return "WAREHOUSE_ALLOY_BATCH_SHAPE_UNSUPPORTED";
        }
        if (!authorization.recipeIds().equals(java.util.Set.of(recipe))) {
            return "WAREHOUSE_ALLOY_RECIPE_AUTHORITY_MISMATCH";
        }
        if (!authorization.adapterIds().equals(java.util.Set.of(
                ImmersiveEngineeringV1020Adapter.ADAPTER_ID))) {
            return "WAREHOUSE_ALLOY_ADAPTER_AUTHORITY_MISMATCH";
        }
        return null;
    }

    private WarehouseOrderService.DispatchResult refused(
            UnattendedProductionOrderScheduler.BatchAuthorization authorization, String code) {
        attempts.add(new Attempt(authorization.batchId(), false, code, null));
        return new WarehouseOrderService.DispatchResult(false, code);
    }

    private Optional<StoredOrder> boundOrder(ResourceId productionOrderId, ResourceId batchId) {
        return AlloySmelterOrderSavedData.forLevel(level).orders().values().stream()
                .filter(value -> value.warehouseBinding().isPresent())
                .filter(value -> {
                    var binding = value.warehouseBinding().orElseThrow();
                    return binding.warehouseId().equals(warehouseId)
                            && binding.productionOrderId().equals(productionOrderId)
                            && binding.batchId().equals(batchId);
                }).findFirst();
    }

    private FakePlayer actor() {
        return FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("steve-unattended-alloy-" + warehouseId)
                        .getBytes(StandardCharsets.UTF_8)), "SteveAlloyWarehouse"));
    }

    private BlockPos standableBeside(BlockPos container) {
        for (net.minecraft.core.Direction face
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos candidate = container.relative(face);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()
                    && !level.getBlockState(candidate.below()).isAir()) {
                return candidate;
            }
        }
        throw new IllegalStateException("NO_STANDABLE_CELL_BESIDE_WAREHOUSE_SOURCE");
    }

    private static boolean terminal(IndustrialLifecyclePhase phase) {
        return phase == IndustrialLifecyclePhase.COMPLETED
                || phase == IndustrialLifecyclePhase.RECOVERED
                || phase == IndustrialLifecyclePhase.CANCELLED
                || phase == IndustrialLifecyclePhase.FAILED;
    }

    private static boolean hasOutputSlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) return true;
        }
        return false;
    }

    private static boolean canSupply(Container container, Map<ResourceId, Long> requirements) {
        java.util.LinkedHashMap<ResourceId, Long> exact = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            // Item identity is the admission filter. Components/NBT are frozen later by
            // the authoritative PlayerMaterial source snapshot and exact reservations;
            // excluding them here would incorrectly hide damageable tools such as IE's
            // fresh engineer hammer, whose stack carries a zero-damage payload.
            if (stack.isEmpty()) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) exact.merge(ResourceId.parse(key.toString()),
                    (long) stack.getCount(), Math::addExact);
        }
        return requirements.entrySet().stream()
                .allMatch(row -> exact.getOrDefault(row.getKey(), 0L) >= row.getValue());
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static BlockPos3i cell(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    public record Attempt(ResourceId batchId, boolean accepted, String code, UUID projectId) {}
}
