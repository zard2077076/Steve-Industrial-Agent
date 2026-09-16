package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.ForgeEnergyProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.LogisticsProgressProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.OutputCapacityProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.RotationalPowerProbe;
import dev.stevecreate.agent.core.diagnostic.FactoryLiveProbeEvaluator.StructureProbe;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FactoryLiveProbeEvaluatorTest {
    private static final Set<ResourceId> ITEMS = Set.of(ResourceId.parse("test:product"));
    private final FactoryLiveProbeEvaluator evaluator = new FactoryLiveProbeEvaluator();

    @Test
    void exactOutputCapacityDistinguishesAvailableFullAndUnknown() {
        assertThat(evaluator.outputCapacity(
                new OutputCapacityProbe(true, true, 4, 4), ITEMS).state())
                .isEqualTo(FactoryObservationState.HEALTHY);
        assertThat(evaluator.outputCapacity(
                new OutputCapacityProbe(true, true, 4, 3), ITEMS).evidenceCode())
                .isEqualTo("PLAN_OUTPUT_CAPACITY_EXHAUSTED");
        FactoryHealthObservation unknown = evaluator.outputCapacity(
                new OutputCapacityProbe(false, false, 4, 0), ITEMS);
        assertThat(unknown.state()).isEqualTo(FactoryObservationState.UNKNOWN);
        assertThat(unknown.source()).isEqualTo(FactoryEvidenceSource.LIVE_WORLD_UNAVAILABLE);
    }

    @Test
    void slowLogisticsIsProgressWhileStationaryOrBlockedRouteFaults() {
        FactoryHealthObservation slow = evaluator.logistics(new LogisticsProgressProbe(
                true, false, 4, 1, 1_000, 1_199, 200), ITEMS);
        assertThat(slow.state()).isEqualTo(FactoryObservationState.HEALTHY);
        assertThat(slow.evidenceCode()).isEqualTo("PLAN_LOGISTICS_PROGRESS");

        FactoryHealthObservation stalled = evaluator.logistics(new LogisticsProgressProbe(
                true, false, 4, 1, 1_000, 1_201, 200), ITEMS);
        assertThat(stalled.state()).isEqualTo(FactoryObservationState.FAULT);
        assertThat(stalled.evidenceCode()).isEqualTo("PLAN_LOGISTICS_STALLED");

        assertThat(evaluator.logistics(new LogisticsProgressProbe(
                true, true, 4, 1, 1_200, 1_200, 200), ITEMS).state())
                .isEqualTo(FactoryObservationState.FAULT);
    }

    @Test
    void rotationalPowerReportsOverstressMissingEndpointAndUnknownExactly() {
        assertThat(evaluator.rotationalPower(new RotationalPowerProbe(
                true, 2, 2, 2, 0, 16_000, 32_000, 8_000), ITEMS).state())
                .isEqualTo(FactoryObservationState.HEALTHY);
        assertThat(evaluator.rotationalPower(new RotationalPowerProbe(
                true, 2, 2, 2, 0, 16_000, 32_000, 8_000), ITEMS).metrics())
                .containsEntry("minimumRpmMilli", 16_000L);
        assertThat(evaluator.rotationalPower(new RotationalPowerProbe(
                true, 2, 2, 0, 2, 0, 8_000, 16_000), ITEMS).evidenceCode())
                .isEqualTo("PLAN_ROTATIONAL_POWER_OVERSTRESSED");
        assertThat(evaluator.rotationalPower(new RotationalPowerProbe(
                true, 2, 1, 1, 0, 16_000, 32_000, 8_000), ITEMS).state())
                .isEqualTo(FactoryObservationState.FAULT);
        assertThat(evaluator.rotationalPower(new RotationalPowerProbe(
                false, 2, 0, 0, 0, 0, 0, 0), ITEMS).state())
                .isEqualTo(FactoryObservationState.UNKNOWN);
    }

    @Test
    void forgeEnergyAllowsObservedChargingButNotBrokenTopology() {
        assertThat(evaluator.forgeEnergy(new ForgeEnergyProbe(
                true, 2_400, 600, 1, 1, true, false, false), ITEMS).evidenceCode())
                .isEqualTo("PLAN_FE_CHARGING_PROGRESS");
        assertThat(evaluator.forgeEnergy(new ForgeEnergyProbe(
                true, 2_400, 2_400, 1, 0, false, false, false), ITEMS).evidenceCode())
                .isEqualTo("PLAN_FE_CONNECTION_MISMATCH");
        assertThat(evaluator.forgeEnergy(new ForgeEnergyProbe(
                false, 2_400, 0, 1, 0, false, false, false), ITEMS).state())
                .isEqualTo(FactoryObservationState.UNKNOWN);
    }

    @Test
    void structureSeparatesIdentityOrientationAndUnavailableEvidence() {
        assertThat(evaluator.structure(new StructureProbe(
                true, 7, 7, 0, 0, 0), ITEMS).state())
                .isEqualTo(FactoryObservationState.HEALTHY);
        assertThat(evaluator.structure(new StructureProbe(
                true, 7, 6, 1, 0, 0), ITEMS).evidenceCode())
                .isEqualTo("PLAN_STRUCTURE_MISMATCH");
        assertThat(evaluator.structure(new StructureProbe(
                true, 7, 6, 0, 1, 0), ITEMS).evidenceCode())
                .isEqualTo("PLAN_ORIENTATION_MISMATCH");
        assertThat(evaluator.structure(new StructureProbe(
                false, 7, 0, 0, 0, 7), ITEMS).state())
                .isEqualTo(FactoryObservationState.UNKNOWN);
    }
}
