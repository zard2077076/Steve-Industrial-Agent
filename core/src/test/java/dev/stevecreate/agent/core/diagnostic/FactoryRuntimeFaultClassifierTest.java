package dev.stevecreate.agent.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class FactoryRuntimeFaultClassifierTest {
    @Test
    void typedFaultCodesMapToStableCategoriesIncludingDetailSuffixes() {
        assertThat(FactoryRuntimeFaultClassifier.classify("MATERIALS_INSUFFICIENT"))
                .containsExactly(FactoryHealthCategory.MATERIAL_SUPPLY);
        assertThat(FactoryRuntimeFaultClassifier.classify(
                "WAREHOUSE_SOURCE_OUT_OF_REACH:minecraft:iron_ingot"))
                .containsExactly(FactoryHealthCategory.LOGISTICS_ROUTE);
        assertThat(FactoryRuntimeFaultClassifier.classify("MOLD_INSTALLED_NOT_POWERED"))
                .containsExactly(FactoryHealthCategory.ENERGY_SUPPLY);
        assertThat(FactoryRuntimeFaultClassifier.classify("MATERIAL_SOURCE_CHANGED"))
                .containsExactly(FactoryHealthCategory.RESERVATION_INTEGRITY);
    }

    @Test
    void oneTypedCodeMayCarryTwoIndependentFaults() {
        assertThat(FactoryRuntimeFaultClassifier.classify("OUTPUT_DELIVERY_FAILED"))
                .containsExactlyInAnyOrder(FactoryHealthCategory.OUTPUT_CAPACITY,
                        FactoryHealthCategory.LOGISTICS_ROUTE);
    }

    @Test
    void successAndUnknownCodesAreNeverGuessedIntoFaults() {
        assertThat(FactoryRuntimeFaultClassifier.classify("MATERIALS_RESERVED")).isEmpty();
        assertThat(FactoryRuntimeFaultClassifier.classify("POWER_VERIFIED")).isEmpty();
        assertThat(FactoryRuntimeFaultClassifier.classify("MATERIALS_MYSTERIOUS_NEW_STATE"))
                .isEmpty();
        assertThat(FactoryRuntimeFaultClassifier.classify("ROUTE_SLOW_PROGRESS")).isEmpty();
    }
}
