package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetalPressOrderStateMachineTest {
    @Test
    void restartCheckpointsResolveAllRequestedInterruptionWindows() {
        MetalPressProductionOrder withdrawn = reserved();
        withdrawn = MetalPressOrderStateMachine.recordEffect(withdrawn,
                MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1, "WITHDRAWN", 2);
        assertThat(recover(withdrawn, true, false, false, false, false, false, 0, 0, false, false, false))
                .isEqualTo(MetalPressOrderStage.MATERIALS_WITHDRAWN);

        MetalPressProductionOrder mold = throughMold(withdrawn);
        assertThat(recover(mold, true, true, true, true, true, false, 0, 0, false, false, false))
                .isEqualTo(MetalPressOrderStage.MOLD_INSTALLED);

        MetalPressProductionOrder fed = MetalPressOrderStateMachine.checkpoint(mold,
                MetalPressOrderStage.POWER_NETWORK_BUILT, "POWER_BUILT", 7);
        fed = MetalPressOrderStateMachine.checkpoint(fed,
                MetalPressOrderStage.POWER_VERIFIED, "POWER_VERIFIED", 8);
        fed = MetalPressOrderStateMachine.recordEffect(fed,
                MetalPressDurableEffect.INPUT_ADMISSION, 2_422, "INPUT_QUEUED", 9);
        assertThat(recover(fed, true, true, true, true, true, true, 0, 0, false, false, false))
                .isEqualTo(MetalPressOrderStage.INPUT_QUEUED);

        MetalPressProductionOrder energy = MetalPressOrderStateMachine.checkpoint(fed,
                MetalPressOrderStage.PROCESSING, "PROCESSING", 10);
        energy = MetalPressOrderStateMachine.recordEffect(energy,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400, "ENERGY_SETTLED", 11);
        assertThat(recover(energy, true, true, true, true, true, true,
                2_400, 0, false, false, false)).isEqualTo(MetalPressOrderStage.ENERGY_SETTLED);

        MetalPressProductionOrder output = MetalPressOrderStateMachine.recordEffect(energy,
                MetalPressDurableEffect.OUTPUT_CLAIM, 1, "OUTPUT_OBSERVED", 12);
        assertThat(recover(output, true, true, true, true, true, true,
                2_400, 1, false, false, false)).isEqualTo(MetalPressOrderStage.OUTPUT_OBSERVED);
    }

    @Test
    void replaysCannotDuplicateWithdrawalEnergyOutputOrReport() {
        MetalPressProductionOrder order = reserved();
        order = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1, "WITHDRAWN", 2);
        MetalPressProductionOrder replay = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.MATERIAL_WITHDRAWAL, 1, "WITHDRAWN", 3);
        assertThat(replay).isSameAs(order);

        order = throughMold(order);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.POWER_NETWORK_BUILT, "POWER_BUILT", 7);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.POWER_VERIFIED, "POWER_VERIFIED", 8);
        order = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.INPUT_ADMISSION, 2_422, "INPUT", 9);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.PROCESSING, "PROCESSING", 10);
        order = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400, "ENERGY", 11);
        MetalPressProductionOrder energyReplay = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_400, "ENERGY", 12);
        assertThat(energyReplay).isSameAs(order);
        assertThatThrownBy(() -> MetalPressOrderStateMachine.recordEffect(energyReplay,
                MetalPressDurableEffect.ENERGY_SETTLEMENT, 2_399, "BAD", 13))
                .isInstanceOf(IllegalStateException.class);

        order = MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.OUTPUT_CLAIM, 1, "OUTPUT", 12);
        assertThat(MetalPressOrderStateMachine.recordEffect(order,
                MetalPressDurableEffect.OUTPUT_CLAIM, 1, "OUTPUT", 13)).isSameAs(order);
    }

    @Test
    void acceptedReportRequiresExactEnergyOutputBalanceAndBaseline() {
        MetalPressCompletionReport report = new MetalPressCompletionReport(
                20, 20, 5, 15, 2_400, 1, 0, 0, 0, 0,
                0, 0, true, true, "b".repeat(64));
        assertThat(report.accepted(2_400)).isTrue();
        IndustrialCompletionReportV1 generic = report.toIndustrialReport();
        assertThat(generic.accepted(id("immersiveengineering:fe"), 2_400,
                id("immersiveengineering:plate_iron"), 1)).isTrue();
        assertThat(generic.evidenceHash()).hasSize(64);
        assertThatThrownBy(() -> new MetalPressCompletionReport(
                20, 20, 5, 14, 2_400, 1, 0, 0, 0, 0,
                0, 0, true, true, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metalPressProjectsIntoCommonPlayerOrderWithoutChangingFrozenStage() {
        MetalPressProductionOrder order = reserved();
        IndustrialPlayerOrderV1 generic = order.toIndustrialPlayerOrderV1();
        assertThat(generic.orderType()).isEqualTo(id("steve_industrial:metal_press"));
        assertThat(generic.recipeId()).isEqualTo(id("immersiveengineering:metalpress/plate_iron"));
        assertThat(generic.phase()).isEqualTo(IndustrialLifecyclePhase.PLANNED);
        assertThat(generic.stage()).isEqualTo("MATERIALS_RESERVED");
        assertThat(generic.report()).isEmpty();
        assertThat(order.stage()).isEqualTo(MetalPressOrderStage.MATERIALS_RESERVED);
    }

    private static MetalPressProductionOrder reserved() {
        MetalPressProductionOrder order = MetalPressProductionOrder.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), id("minecraft:overworld"),
                new BlockPos3i(10, 64, 10), new BlockPos3i(0, 64, 0),
                id("immersiveengineering:metalpress/plate_iron"), "a".repeat(64),
                "ie-1.20.1-10.2.0-183", "b".repeat(64), 0);
        return MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MATERIALS_RESERVED, "RESERVED", 1);
    }

    private static MetalPressProductionOrder throughMold(MetalPressProductionOrder order) {
        long now = Math.max(3, order.updatedAt() + 1);
        if (order.stage() == MetalPressOrderStage.MATERIALS_WITHDRAWN) {
            order = MetalPressOrderStateMachine.checkpoint(order,
                    MetalPressOrderStage.MATERIALS_DELIVERED, "DELIVERED", now++);
        }
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.STRUCTURE_BUILT, "BUILT", now++);
        order = MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MULTIBLOCK_FORMED, "FORMED", now++);
        return MetalPressOrderStateMachine.checkpoint(order,
                MetalPressOrderStage.MOLD_INSTALLED, "MOLD", now);
    }

    private static MetalPressOrderStage recover(MetalPressProductionOrder order,
            boolean withdrawn, boolean delivered, boolean structure, boolean formed,
            boolean mold, boolean power, long energy, long output, boolean returned,
            boolean report, boolean baseline) {
        return MetalPressOrderStateMachine.recoverStage(order, new MetalPressRecoveryEvidence(
                true, withdrawn, delivered, structure, formed, mold, power, power,
                order.durableEffects().contains(MetalPressDurableEffect.INPUT_ADMISSION),
                energy, output, returned, report, baseline));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
