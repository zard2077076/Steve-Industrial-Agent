package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.industrial.MetalPressDurableEffect;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStateMachine;
import dev.stevecreate.agent.core.industrial.MetalPressProductionOrder;
import dev.stevecreate.agent.core.industrial.MetalPressRecoveryEvidence;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.BaselineBlock;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData.StoredOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Two-JVM proof for the five production interruption windows and durable effect replay. */
public final class MetalPressRecoveryReloadAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.metalPressRecoveryReloadAcceptancePhase";
    private static final List<String> WINDOWS = List.of(
            "WITHDRAWN_NOT_DELIVERED",
            "MOLD_INSTALLED_NOT_POWERED",
            "INPUT_ADMITTED_NOT_OUTPUT",
            "ENERGY_SETTLED_NOT_ACCOUNTED",
            "OUTPUT_CLAIMED_NOT_REPORTED");

    private MetalPressRecoveryReloadAcceptanceFixture() {}

    public static void run(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("MetalPressRecoveryReloadAcceptanceFixture");
        try {
            if ("write".equals(phase)) write(server, logger);
            else if ("read".equals(phase)) read(server, logger);
            else throw new IllegalArgumentException("Unknown Metal Press reload phase: " + phase);
        } catch (RuntimeException failure) {
            logger.error("IE_METAL_PRESS_RECOVERY_RELOAD FAIL phase={} detail={}",
                    phase, failure.getMessage());
            server.halt(false);
            throw failure;
        }
    }

    private static void write(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        check(data.orders().isEmpty(), "write phase found existing Metal Press reload fixtures");
        Map<String, MetalPressProductionOrder> orders = interruptedOrders();
        int index = 0;
        for (Map.Entry<String, MetalPressProductionOrder> row : orders.entrySet()) {
            CompoundTag air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            data.put(new StoredOrder(row.getValue(), List.of(new BaselineBlock(
                    new BlockPos3i(1_000 + index++, 240, 1_000), air))));
        }
        level.getDataStorage().save();
        logger.info("IE_METAL_PRESS_RECOVERY_RELOAD_WRITE PASS windows={} orders={} "
                        + "durableSavedData=true processWillExit=true",
                WINDOWS, data.orders().size());
        server.halt(false);
    }

    private static void read(MinecraftServer server, Logger logger) {
        ServerLevel level = server.overworld();
        MetalPressOrderSavedData data = MetalPressOrderSavedData.forLevel(level);
        Map<String, MetalPressProductionOrder> expected = interruptedOrders();
        check(data.orders().size() == expected.size(), "read phase did not reload all five orders");
        Map<String, MetalPressProductionOrder> restored = new LinkedHashMap<>();
        for (Map.Entry<String, MetalPressProductionOrder> row : expected.entrySet()) {
            MetalPressProductionOrder order = data.order(row.getValue().orderId())
                    .orElseThrow(() -> new IllegalStateException("missing window " + row.getKey()))
                    .order();
            check(order.equals(row.getValue()), "reloaded order changed for " + row.getKey());
            restored.put(row.getKey(), order);
        }

        check(recover(restored.get(WINDOWS.get(0)), evidence(true, false, false, false, false,
                false, false, 0, 0)) == MetalPressOrderStage.MATERIALS_WITHDRAWN,
                "withdrawn recovery window changed");
        check(recover(restored.get(WINDOWS.get(1)), evidence(true, true, true, true, true,
                false, false, 0, 0)) == MetalPressOrderStage.MOLD_INSTALLED,
                "mold recovery window changed");
        check(recover(restored.get(WINDOWS.get(2)), evidence(true, true, true, true, true,
                true, true, 0, 0)) == MetalPressOrderStage.INPUT_QUEUED,
                "input recovery window changed");
        check(recover(restored.get(WINDOWS.get(3)), evidence(true, true, true, true, true,
                true, true, 2_400, 0)) == MetalPressOrderStage.ENERGY_SETTLED,
                "energy recovery window changed");
        check(recover(restored.get(WINDOWS.get(4)), evidence(true, true, true, true, true,
                true, true, 2_400, 1)) == MetalPressOrderStage.OUTPUT_OBSERVED,
                "output recovery window changed");

        check(replay(restored.get(WINDOWS.get(0)), MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1),
                "material withdrawal replay changed authority");
        check(replay(restored.get(WINDOWS.get(2)), MetalPressDurableEffect.INPUT_ADMISSION, 2_424),
                "input replay changed authority");
        check(replay(restored.get(WINDOWS.get(3)), MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400),
                "energy replay changed authority");
        check(replay(restored.get(WINDOWS.get(4)), MetalPressDurableEffect.OUTPUT_CLAIM, 1),
                "output replay changed authority");

        logger.info("IE_METAL_PRESS_RECOVERY_RELOAD_ACCEPTANCE PASS windows={} "
                        + "oldRuntimeDestroyed=true savedDataReloaded=true "
                        + "duplicateWithdrawals=0 duplicateInputAdmissions=0 "
                        + "duplicateEnergySettlements=0 duplicateOutputs=0 blindReplay=false",
                WINDOWS);
        server.halt(false);
    }

    private static Map<String, MetalPressProductionOrder> interruptedOrders() {
        LinkedHashMap<String, MetalPressProductionOrder> result = new LinkedHashMap<>();
        MetalPressProductionOrder withdrawn = reserved(WINDOWS.get(0));
        withdrawn = MetalPressOrderStateMachine.recordEffect(withdrawn,
                MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1, "WITHDRAWN", 2);
        result.put(WINDOWS.get(0), withdrawn);

        MetalPressProductionOrder mold = throughMold(reserved(WINDOWS.get(1)));
        result.put(WINDOWS.get(1), mold);

        MetalPressProductionOrder input = throughMold(reserved(WINDOWS.get(2)));
        input = MetalPressOrderStateMachine.checkpoint(input,
                MetalPressOrderStage.POWER_NETWORK_BUILT, "POWER_BUILT", 6);
        input = MetalPressOrderStateMachine.checkpoint(input,
                MetalPressOrderStage.POWER_VERIFIED, "POWER_VERIFIED", 7);
        input = MetalPressOrderStateMachine.recordEffect(input,
                MetalPressDurableEffect.INPUT_ADMISSION, 2_424, "INPUT_ADMITTED", 8);
        result.put(WINDOWS.get(2), input);

        MetalPressProductionOrder energy = throughInput(reserved(WINDOWS.get(3)));
        energy = MetalPressOrderStateMachine.checkpoint(energy,
                MetalPressOrderStage.PROCESSING, "PROCESSING", 9);
        energy = MetalPressOrderStateMachine.recordEffect(energy,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400, "ENERGY_SETTLED", 10);
        result.put(WINDOWS.get(3), energy);

        MetalPressProductionOrder output = throughInput(reserved(WINDOWS.get(4)));
        output = MetalPressOrderStateMachine.checkpoint(output,
                MetalPressOrderStage.PROCESSING, "PROCESSING", 9);
        output = MetalPressOrderStateMachine.recordEffect(output,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400, "ENERGY_SETTLED", 10);
        output = MetalPressOrderStateMachine.recordEffect(output,
                MetalPressDurableEffect.OUTPUT_CLAIM, 1, "OUTPUT_CLAIMED", 11);
        result.put(WINDOWS.get(4), output);
        return Map.copyOf(result);
    }

    private static MetalPressProductionOrder reserved(String window) {
        MetalPressProductionOrder order = MetalPressProductionOrder.create(
                uuid("order:" + window), uuid("project:" + window), uuid("player:" + window),
                id("minecraft:overworld"), new BlockPos3i(1_000, 240, 1_000),
                new BlockPos3i(990, 240, 1_000),
                id("immersiveengineering:metalpress/plate_iron"), "a".repeat(64),
                "ie-1.20.1-10.2.0-183", "b".repeat(64), 0);
        return MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MATERIALS_RESERVED, "RESERVED", 1);
    }

    private static MetalPressProductionOrder throughMold(MetalPressProductionOrder order) {
        order = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1, "WITHDRAWN", 2);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MATERIALS_DELIVERED, "DELIVERED", 3);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.STRUCTURE_BUILT, "STRUCTURE", 4);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MULTIBLOCK_FORMED, "FORMED", 5);
        return MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MOLD_INSTALLED, "MOLD", 6);
    }

    private static MetalPressProductionOrder throughInput(MetalPressProductionOrder order) {
        order = throughMold(order);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.POWER_NETWORK_BUILT, "POWER_BUILT", 7);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.POWER_VERIFIED, "POWER_VERIFIED", 8);
        return MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.INPUT_ADMISSION, 2_424, "INPUT_ADMITTED", 9);
    }

    private static MetalPressRecoveryEvidence evidence(boolean withdrawn, boolean delivered,
            boolean structure, boolean formed, boolean mold, boolean powerNetwork,
            boolean powered, long energy, long output) {
        return new MetalPressRecoveryEvidence(true, withdrawn, delivered, structure, formed, mold,
                powerNetwork, powered, powered, energy, output, false, false, false);
    }

    private static MetalPressOrderStage recover(
            MetalPressProductionOrder order, MetalPressRecoveryEvidence evidence) {
        return MetalPressOrderStateMachine.recoverStage(order, evidence);
    }

    private static boolean replay(
            MetalPressProductionOrder order, MetalPressDurableEffect effect, long value) {
        return MetalPressOrderStateMachine.recordEffect(
                order, effect, value, "REPLAY", order.updatedAt() + 1) == order;
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
