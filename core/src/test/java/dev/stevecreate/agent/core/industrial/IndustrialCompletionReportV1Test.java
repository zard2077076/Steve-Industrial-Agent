package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndustrialCompletionReportV1Test {
    private static final ResourceId IRON = id("minecraft:iron_ingot");
    private static final ResourceId FE = id("immersiveengineering:fe");
    private static final ResourceId PLATE = id("immersiveengineering:plate_iron");

    @Test
    void balancedEvidenceRequiresThePlannedIdentityAndQuantityToo() {
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                Map.of(IRON, 4L), Map.of(IRON, 4L), Map.of(IRON, 3L), Map.of(IRON, 1L),
                Map.of(FE, 2_400L), Map.of(), Map.of(PLATE, 1L),
                0, 0, 0, 0, 0, 0, 0, true, "a".repeat(64));

        assertThat(report.materialLedgerBalanced()).isTrue();
        assertThat(report.accepted(FE, 2_400, PLATE, 1)).isTrue();
        assertThat(report.evidenceHash()).hasSize(64);
    }

    @Test
    void plannedAndWithdrawnDriftCannotBeClaimedAsBalanced() {
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                Map.of(IRON, 4L), Map.of(IRON, 3L), Map.of(IRON, 2L), Map.of(IRON, 1L),
                Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0,
                true, "a".repeat(64));

        assertThat(report.materialLedgerBalanced()).isFalse();
        assertThat(report.accepted(FE, 0, PLATE, 0)).isFalse();
    }

    @Test
    void extraEnergyOrOutputIdentityCannotBeAccepted() {
        IndustrialCompletionReportV1 extraEnergy = IndustrialCompletionReportV1.create(
                Map.of(IRON, 1L), Map.of(IRON, 1L), Map.of(IRON, 1L), Map.of(),
                Map.of(FE, 2_400L,
                        ResourceId.parse("immersiveengineering:other_fe"), 1L),
                Map.of(), Map.of(PLATE, 1L),
                0, 0, 0, 0, 0, 0, 0, true, "a".repeat(64));
        assertThat(extraEnergy.accepted(FE, 2_400, PLATE, 1)).isFalse();

        IndustrialCompletionReportV1 extraOutput = IndustrialCompletionReportV1.create(
                Map.of(IRON, 1L), Map.of(IRON, 1L), Map.of(IRON, 1L), Map.of(),
                Map.of(FE, 2_400L), Map.of(),
                Map.of(PLATE, 1L,
                        ResourceId.parse("immersiveengineering:scrap"), 1L),
                0, 0, 0, 0, 0, 0, 0, true, "a".repeat(64));
        assertThat(extraOutput.accepted(FE, 2_400, PLATE, 1)).isFalse();
    }

    @Test
    void itemFuelIsSettledAsMaterialWithoutInventingAnEnergyRow() {
        ResourceId coal = id("minecraft:coal");
        ResourceId brass = id("create:brass_ingot");
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                Map.of(IRON, 1L, coal, 1L), Map.of(IRON, 1L, coal, 1L),
                Map.of(IRON, 1L, coal, 1L), Map.of(), Map.of(), Map.of(),
                Map.of(brass, 2L), 0, 0, 0, 0, 0, 0, 0,
                true, "a".repeat(64));

        assertThat(report.accepted(Map.of(), Map.of(brass, 2L))).isTrue();
        assertThat(report.accepted(Map.of(FE, 1L), Map.of(brass, 2L))).isFalse();
        assertThat(report.energyConsumed()).isEmpty();
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
