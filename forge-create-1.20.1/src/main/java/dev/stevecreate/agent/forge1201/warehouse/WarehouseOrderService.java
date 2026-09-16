package dev.stevecreate.agent.forge1201.warehouse;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.ProductionOrder;
import dev.stevecreate.agent.core.warehouse.ProductionOrderStatus;
import dev.stevecreate.agent.core.warehouse.UnattendedProductionOrderScheduler;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-tick durable order dispatcher. Runtime registrations are deliberately ephemeral: after a
 * restart no order can dispatch until the exact warehouse implementation registers again.
 */
public final class WarehouseOrderService {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(WarehouseOrderService.class);
    private static final Map<ResourceId, WarehouseRuntime> RUNTIMES = new LinkedHashMap<>();
    private static final UnattendedProductionOrderScheduler SCHEDULER =
            new UnattendedProductionOrderScheduler();

    private WarehouseOrderService() {}

    public static synchronized void registerRuntime(
            ResourceId warehouseId, WarehouseRuntime runtime) {
        if (RUNTIMES.putIfAbsent(Objects.requireNonNull(warehouseId, "warehouseId"),
                Objects.requireNonNull(runtime, "runtime")) != null) {
            throw new IllegalStateException("warehouse runtime already registered " + warehouseId);
        }
    }

    public static synchronized void unregisterRuntime(ResourceId warehouseId) {
        RUNTIMES.remove(warehouseId);
    }

    /** Read-only exact runtime-owned endpoints; absence remains UNKNOWN to diagnostics. */
    public static synchronized Optional<RuntimeDiagnostic> diagnostic(ResourceId warehouseId) {
        WarehouseRuntime runtime = RUNTIMES.get(Objects.requireNonNull(warehouseId, "warehouseId"));
        return runtime == null ? Optional.empty() : runtime.diagnostic();
    }

    public static synchronized void clearServerState() {
        TRANSFERS.clear();
        RUNTIMES.clear();
    }

    /**
     * Rebuilds every persisted warehouse runtime, so production survives a restart.
     *
     * <p>Orders were always durable and the runtime acting on them was not: it lived in a
     * static map cleared on shutdown, so a restarted server read its orders, found no
     * runtime and skipped all of them forever. The orders sitting in storage made it look
     * like the factory was still working.
     *
     * <p>A registration that cannot be rebuilt is dropped with a log line rather than
     * retried silently. Losing one warehouse's instruction is visible; a restart loop
     * would not be.</p>
     */
    public static synchronized int restoreRegisteredRuntimes(MinecraftServer server) {
        ServerLevel level = server.overworld();
        int restored = 0;
        for (WarehouseRuntimeSavedData.Registration registration
                : WarehouseRuntimeSavedData.forLevel(level).registrations().values()) {
            try {
                RUNTIMES.put(registration.warehouseId(), runtime(level, registration));
                restored++;
            } catch (RuntimeException failure) {
                LOGGER.warn("WAREHOUSE_RUNTIME_NOT_RESTORED warehouse={} reason={}",
                        registration.warehouseId(), failure.getMessage());
            }
        }
        if (restored > 0) {
            LOGGER.info("WAREHOUSE_RUNTIMES_RESTORED count={}", restored);
        }
        return restored;
    }

    /**
     * Registers a runtime and records how to rebuild it after a restart.
     *
     * <p>The plain {@link #registerRuntime} stays for callers whose runtime is not
     * reconstructible from a description — an acceptance fixture's observer, for
     * instance. Anything a player asked for should use this one, because an instruction
     * that evaporates on restart is not an instruction.</p>
     */
    public static synchronized void registerDurableRuntime(
            ServerLevel level, WarehouseRuntimeSavedData.Registration registration) {
        WarehouseRuntimeSavedData.forLevel(level).put(registration);
        RUNTIMES.put(registration.warehouseId(), runtime(level, registration));
    }

    private static WarehouseRuntime runtime(
            ServerLevel level, WarehouseRuntimeSavedData.Registration registration) {
        if (registration.runtimeKind()
                == WarehouseRuntimeSavedData.RuntimeKind.ALLOY_SMELTER) {
            if (!registration.orderType().equals(
                    dev.stevecreate.agent.forge1201.command.AlloySmelterProductionService
                            .ORDER_TYPE)) {
                throw new IllegalArgumentException("Alloy runtime has the wrong order type");
            }
            if (registration.mode()
                    != dev.stevecreate.agent.core.execution.construction.ExecutionMode.BOTS) {
                throw new IllegalArgumentException("Alloy runtime requires BOTS mode");
            }
            if (registration.siteChest().isPresent()) {
                throw new IllegalArgumentException("Alloy runtime does not use a carry chest");
            }
            return new WarehouseAlloySmelterDispatch(level, registration.warehouseId(),
                    block(registration.warehouseCentre()), registration.radius(),
                    registration.siteOrigins().stream().map(WarehouseOrderService::block).toList());
        }
        return new WarehouseProductionDispatch(
                level,
                block(registration.warehouseCentre()),
                registration.radius(),
                registration.siteOrigins().stream().map(WarehouseOrderService::block).toList(),
                registration.orderType(),
                registration.mode(),
                registration.siteChest().map(WarehouseOrderService::block).orElse(null));
    }

    private static net.minecraft.core.BlockPos block(
            dev.stevecreate.agent.core.model.BlockPos3i cell) {
        return new net.minecraft.core.BlockPos(cell.x(), cell.y(), cell.z());
    }

    /** A courier moving stock from one warehouse to another, and what it was asked to move. */
    public record Transfer(
            dev.stevecreate.agent.forge1201.command.WarehouseMaterialCarry carry,
            ResourceId resource,
            long amount,
            net.minecraft.core.BlockPos destination) {}

    private static final List<Transfer> TRANSFERS = new java.util.ArrayList<>();

    /**
     * Starts a courier carrying stock between two warehouses.
     *
     * <p>Inter-factory routing turns out to be the same operation C5-A already built: a
     * bot taking material from a set of containers to a chest. A warehouse is just a set
     * of containers, so moving between two of them needed a caller rather than a
     * mechanism.
     *
     * <p>Like every other carry it takes its own material entry and its own reservations,
     * so the two warehouses' ledgers stay separate and complete.</p>
     */
    public static synchronized String beginTransfer(
            net.minecraft.server.level.ServerPlayer actor,
            List<net.minecraft.core.BlockPos> sources,
            net.minecraft.core.BlockPos destination,
            net.minecraft.core.BlockPos botStart,
            ResourceId resource,
            long amount,
            String worldIdentity) {
        var started = dev.stevecreate.agent.forge1201.command.WarehouseMaterialCarry.begin(
                actor, java.util.Map.of(resource, amount), sources, destination, botStart,
                "steve_industrial:warehouse_transfer/v1", worldIdentity);
        if (!started.success()) return started.code();
        TRANSFERS.add(new Transfer(started.carry(), resource, amount, destination));
        return "TRANSFER_STARTED";
    }

    /** Transfers still walking, for a caller that needs to know when to look. */
    public static synchronized int transfersInFlight() {
        return TRANSFERS.size();
    }

    public static void tick(MinecraftServer server) {
        // Runtimes tick every tick, orders every twenty. A carrying bot that only moved
        // three times a second would look broken; the scheduler has no such need.
        List<WarehouseRuntime> runtimes;
        synchronized (WarehouseOrderService.class) {
            runtimes = List.copyOf(RUNTIMES.values());
        }
        List<Transfer> transfers;
        synchronized (WarehouseOrderService.class) {
            transfers = List.copyOf(TRANSFERS);
        }
        for (Transfer transfer : transfers) {
            var state = transfer.carry().tick();
            if (state == dev.stevecreate.agent.forge1201.command
                    .WarehouseMaterialCarry.State.CARRYING) {
                continue;
            }
            synchronized (WarehouseOrderService.class) {
                TRANSFERS.remove(transfer);
            }
            LOGGER.info("WAREHOUSE_TRANSFER_{} resource={} amount={} destination={} cause={}",
                    state, transfer.resource(), transfer.amount(),
                    transfer.destination().toShortString(), transfer.carry().failureCode());
        }
        for (WarehouseRuntime runtime : runtimes) {
            try {
                runtime.tick(server);
            } catch (RuntimeException ignored) {
                // A runtime that throws while carrying must not stop the others or the
                // scheduler; its own dispatch records the failure.
            }
        }
        if (server.getTickCount() % 20 != 0) return;
        var data = WarehouseOrderSavedData.forLevel(server.overworld());
        for (ProductionOrder order : data.orders().values()) {
            if (order.status() == ProductionOrderStatus.CANCELLED
                    || order.status() == ProductionOrderStatus.PAUSED
                    || order.status() == ProductionOrderStatus.BATCH_IN_FLIGHT) continue;
            WarehouseRuntime runtime;
            synchronized (WarehouseOrderService.class) {
                runtime = RUNTIMES.get(order.warehouseId());
            }
            if (runtime == null) continue;
            long stock;
            try {
                stock = runtime.observedStock(order.target());
            } catch (RuntimeException failure) {
                continue;
            }
            var decision = SCHEDULER.evaluate(order, stock, server.getTickCount());
            data.put(decision.order());
            decision.authorization().ifPresent(authorization -> {
                DispatchResult dispatched;
                try {
                    dispatched = runtime.dispatch(authorization);
                } catch (RuntimeException failure) {
                    dispatched = new DispatchResult(false, "DISPATCH_EXCEPTION");
                }
                if (!dispatched.accepted()) {
                    data.put(SCHEDULER.batchFailed(decision.order(), authorization.batchId(),
                            dispatched.statusCode(), server.getTickCount()));
                }
            });
        }
    }

    public static void batchSucceeded(
            MinecraftServer server, ResourceId orderId, ResourceId batchId) {
        var data = WarehouseOrderSavedData.forLevel(server.overworld());
        ProductionOrder order = data.order(orderId).orElseThrow();
        data.put(SCHEDULER.batchSucceeded(order, batchId, server.getTickCount()));
    }

    public static void batchFailed(
            MinecraftServer server, ResourceId orderId, ResourceId batchId, String code) {
        var data = WarehouseOrderSavedData.forLevel(server.overworld());
        ProductionOrder order = data.order(orderId).orElseThrow();
        data.put(SCHEDULER.batchFailed(order, batchId, code, server.getTickCount()));
    }

    public interface WarehouseRuntime {
        long observedStock(WarehouseResourceKey target);

        /**
         * Advances anything this runtime started that takes more than one tick.
         *
         * <p>A dispatch that physically carries material cannot finish inside
         * {@code dispatch}: a bot walks over many ticks. Default does nothing, so a
         * runtime with no such work is unaffected.</p>
         */
        default void tick(MinecraftServer server) {}
        default Optional<RuntimeDiagnostic> diagnostic() { return Optional.empty(); }
        DispatchResult dispatch(UnattendedProductionOrderScheduler.BatchAuthorization authorization);
    }

    public record RuntimeDiagnostic(
            Optional<dev.stevecreate.agent.core.model.BlockPos3i> outputDestination,
            Optional<dev.stevecreate.agent.forge1201.command.PlayerConstructionService
                    .LogisticsDiagnosticSnapshot> logistics) {
        public RuntimeDiagnostic {
            outputDestination = Objects.requireNonNull(outputDestination, "outputDestination");
            logistics = Objects.requireNonNull(logistics, "logistics");
        }
    }

    public record DispatchResult(boolean accepted, String statusCode) {
        public DispatchResult {
            Objects.requireNonNull(statusCode, "statusCode");
            if (statusCode.isBlank() || statusCode.length() > 256) {
                throw new IllegalArgumentException("warehouse dispatch status is invalid");
            }
        }
    }
}
