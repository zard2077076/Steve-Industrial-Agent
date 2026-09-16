package dev.stevecreate.agent.core.industrial;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Idempotent state/effect reducer. Physical actions remain adapter-owned. */
public final class MetalPressOrderStateMachine {
    private MetalPressOrderStateMachine() {}

    public static MetalPressProductionOrder checkpoint(
            MetalPressProductionOrder order, MetalPressOrderStage next, String statusCode, long now) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(next, "next");
        if (next == order.stage()) return order;
        if (!allowed(order.stage(), next)) {
            throw new IllegalStateException("illegal Metal Press transition "
                    + order.stage() + " -> " + next);
        }
        return copy(order, next, order.durableEffects(), order.energyAtInputFe(),
                order.measuredEnergyConsumedFe(), order.exactOutputCount(), statusCode, now,
                order.report());
    }

    public static MetalPressProductionOrder recordEffect(
            MetalPressProductionOrder order, MetalPressDurableEffect effect,
            long observedValue, String statusCode, long now) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(effect, "effect");
        if (order.durableEffects().contains(effect)) {
            if ((effect == MetalPressDurableEffect.ENERGY_SETTLEMENT
                    && observedValue != order.measuredEnergyConsumedFe())
                    || (effect == MetalPressDurableEffect.OUTPUT_CLAIM
                    && observedValue != order.exactOutputCount())
                    || (effect == MetalPressDurableEffect.INPUT_ADMISSION
                    && observedValue != order.energyAtInputFe())) {
                throw new IllegalStateException("conflicting replay for durable effect " + effect);
            }
            return order;
        }
        if (!effectAllowed(order.stage(), effect)) {
            throw new IllegalStateException("durable effect " + effect
                    + " is not allowed at " + order.stage());
        }
        if (effect == MetalPressDurableEffect.ENERGY_SETTLEMENT
                && observedValue != order.expectedEnergyFe()) {
            throw new IllegalArgumentException("Metal Press energy must settle at exactly 2400 FE");
        }
        if (effect == MetalPressDurableEffect.OUTPUT_CLAIM && observedValue != 1) {
            throw new IllegalArgumentException("Metal Press output must be exactly one item");
        }
        if (effect == MetalPressDurableEffect.INPUT_ADMISSION
                && observedValue < order.expectedEnergyFe()) {
            throw new IllegalArgumentException("input cannot be admitted without 2400 available FE");
        }
        EnumSet<MetalPressDurableEffect> effects = order.durableEffects().isEmpty()
                ? EnumSet.noneOf(MetalPressDurableEffect.class)
                : EnumSet.copyOf(order.durableEffects());
        effects.add(effect);
        long energy = effect == MetalPressDurableEffect.ENERGY_SETTLEMENT
                ? observedValue : order.measuredEnergyConsumedFe();
        long inputEnergy = effect == MetalPressDurableEffect.INPUT_ADMISSION
                ? observedValue : order.energyAtInputFe();
        long output = effect == MetalPressDurableEffect.OUTPUT_CLAIM
                ? observedValue : order.exactOutputCount();
        MetalPressOrderStage stage = switch (effect) {
            case MATERIAL_WITHDRAWAL -> MetalPressOrderStage.MATERIALS_WITHDRAWN;
            case INPUT_ADMISSION -> MetalPressOrderStage.INPUT_QUEUED;
            case ENERGY_SETTLEMENT -> MetalPressOrderStage.ENERGY_SETTLED;
            case OUTPUT_CLAIM -> MetalPressOrderStage.OUTPUT_OBSERVED;
            case MATERIAL_RETURN -> MetalPressOrderStage.MATERIALS_RETURNED;
            case COMPLETION_REPORT -> MetalPressOrderStage.REPORT_GENERATED;
            case BASELINE_RESTORE -> MetalPressOrderStage.BASELINE_RESTORED;
        };
        return copy(order, stage, effects, inputEnergy, energy, output, statusCode, now, order.report());
    }

    public static MetalPressProductionOrder recordReport(
            MetalPressProductionOrder order, MetalPressCompletionReport report,
            long now) {
        Objects.requireNonNull(report, "report");
        if (order.durableEffects().contains(MetalPressDurableEffect.COMPLETION_REPORT)) {
            if (!order.report().orElseThrow().equals(report)) {
                throw new IllegalStateException("conflicting completion report replay");
            }
            return order;
        }
        if (!effectAllowed(order.stage(), MetalPressDurableEffect.COMPLETION_REPORT)) {
            throw new IllegalStateException("report cannot be generated at " + order.stage());
        }
        EnumSet<MetalPressDurableEffect> effects = EnumSet.copyOf(order.durableEffects());
        effects.add(MetalPressDurableEffect.COMPLETION_REPORT);
        return copy(order, MetalPressOrderStage.REPORT_GENERATED, effects,
                order.energyAtInputFe(), order.measuredEnergyConsumedFe(), order.exactOutputCount(),
                "COMPLETION_REPORT_GENERATED", now, Optional.of(report));
    }

    public static MetalPressOrderStage recoverStage(
            MetalPressProductionOrder order, MetalPressRecoveryEvidence evidence) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(evidence, "evidence");
        if (order.stage() == MetalPressOrderStage.COMPLETED && evidence.baselineRestored()
                && evidence.reportPersisted()) {
            return MetalPressOrderStage.COMPLETED;
        }
        if (evidence.reportPersisted()
                && order.durableEffects().contains(MetalPressDurableEffect.COMPLETION_REPORT)) {
            return MetalPressOrderStage.REPORT_GENERATED;
        }
        if (evidence.returnAccountedFor()
                && order.durableEffects().contains(MetalPressDurableEffect.MATERIAL_RETURN)) {
            return MetalPressOrderStage.MATERIALS_RETURNED;
        }
        if (evidence.baselineRestored()
                && order.durableEffects().contains(MetalPressDurableEffect.BASELINE_RESTORE)) {
            return MetalPressOrderStage.BASELINE_RESTORED;
        }
        if (evidence.exactOutputCount() == 1
                && order.durableEffects().contains(MetalPressDurableEffect.OUTPUT_CLAIM)) {
            return MetalPressOrderStage.OUTPUT_OBSERVED;
        }
        if (evidence.measuredEnergyConsumedFe() == order.expectedEnergyFe()
                && order.durableEffects().contains(MetalPressDurableEffect.ENERGY_SETTLEMENT)) {
            return MetalPressOrderStage.ENERGY_SETTLED;
        }
        if (evidence.inputAdmitted()
                && order.durableEffects().contains(MetalPressDurableEffect.INPUT_ADMISSION)) {
            return MetalPressOrderStage.INPUT_QUEUED;
        }
        if (evidence.machineReceivedPower()) return MetalPressOrderStage.POWER_VERIFIED;
        if (evidence.powerNetworkPresent()) return MetalPressOrderStage.POWER_NETWORK_BUILT;
        if (evidence.moldInstalled()) return MetalPressOrderStage.MOLD_INSTALLED;
        if (evidence.multiblockFormed()) return MetalPressOrderStage.MULTIBLOCK_FORMED;
        if (evidence.structurePresent()) return MetalPressOrderStage.STRUCTURE_BUILT;
        if (evidence.deliveryAccountedFor()) return MetalPressOrderStage.MATERIALS_DELIVERED;
        if (evidence.withdrawnMaterialsAccountedFor()
                && order.durableEffects().contains(MetalPressDurableEffect.MATERIAL_WITHDRAWAL)) {
            return MetalPressOrderStage.MATERIALS_WITHDRAWN;
        }
        return evidence.materialsStillReserved()
                ? MetalPressOrderStage.MATERIALS_RESERVED
                : MetalPressOrderStage.PAUSED;
    }

    private static boolean allowed(MetalPressOrderStage from, MetalPressOrderStage to) {
        if (to == MetalPressOrderStage.PAUSED || to == MetalPressOrderStage.CANCELLED) return true;
        return switch (from) {
            case MATERIAL_SOURCE_SELECTION -> to == MetalPressOrderStage.MATERIALS_RESERVED;
            case MATERIALS_RESERVED -> to == MetalPressOrderStage.MATERIALS_WITHDRAWN;
            case MATERIALS_WITHDRAWN -> to == MetalPressOrderStage.MATERIALS_DELIVERED;
            case MATERIALS_DELIVERED -> to == MetalPressOrderStage.STRUCTURE_BUILT;
            case STRUCTURE_BUILT -> to == MetalPressOrderStage.MULTIBLOCK_FORMED;
            case MULTIBLOCK_FORMED -> to == MetalPressOrderStage.MOLD_INSTALLED;
            case MOLD_INSTALLED -> to == MetalPressOrderStage.POWER_NETWORK_BUILT;
            case POWER_NETWORK_BUILT -> to == MetalPressOrderStage.POWER_VERIFIED;
            case POWER_VERIFIED -> to == MetalPressOrderStage.INPUT_QUEUED;
            case INPUT_QUEUED -> to == MetalPressOrderStage.PROCESSING;
            case PROCESSING -> to == MetalPressOrderStage.ENERGY_SETTLED;
            case ENERGY_SETTLED -> to == MetalPressOrderStage.OUTPUT_OBSERVED;
            case OUTPUT_OBSERVED -> to == MetalPressOrderStage.TEARDOWN;
            case TEARDOWN -> to == MetalPressOrderStage.BASELINE_RESTORED;
            case BASELINE_RESTORED -> to == MetalPressOrderStage.MATERIALS_RETURNED;
            case MATERIALS_RETURNED -> to == MetalPressOrderStage.REPORT_GENERATED;
            case REPORT_GENERATED -> to == MetalPressOrderStage.COMPLETED;
            case PAUSED -> to != MetalPressOrderStage.COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    private static boolean effectAllowed(
            MetalPressOrderStage stage, MetalPressDurableEffect effect) {
        return switch (effect) {
            case MATERIAL_WITHDRAWAL -> stage == MetalPressOrderStage.MATERIALS_RESERVED;
            case INPUT_ADMISSION -> stage == MetalPressOrderStage.POWER_VERIFIED;
            case ENERGY_SETTLEMENT -> stage == MetalPressOrderStage.PROCESSING
                    || stage == MetalPressOrderStage.INPUT_QUEUED;
            case OUTPUT_CLAIM -> stage == MetalPressOrderStage.ENERGY_SETTLED;
            case MATERIAL_RETURN -> stage == MetalPressOrderStage.BASELINE_RESTORED;
            case COMPLETION_REPORT -> stage == MetalPressOrderStage.MATERIALS_RETURNED;
            case BASELINE_RESTORE -> stage == MetalPressOrderStage.TEARDOWN;
        };
    }

    private static MetalPressProductionOrder copy(
            MetalPressProductionOrder order, MetalPressOrderStage stage,
            Set<MetalPressDurableEffect> effects, long inputEnergy, long energy, long output,
            String statusCode, long now, Optional<MetalPressCompletionReport> report) {
        if (now < order.updatedAt()) throw new IllegalArgumentException("order time moved backwards");
        return new MetalPressProductionOrder(order.orderId(), order.projectId(), order.playerId(),
                order.dimension(), order.machineOrigin(), order.materialSource(), order.recipeId(),
                order.planHash(), order.runtimeFingerprint(), order.baselineHash(),
                order.expectedEnergyFe(), stage, effects, inputEnergy, energy, output, statusCode,
                order.createdAt(), now, Math.addExact(order.generation(), 1), report);
    }
}
