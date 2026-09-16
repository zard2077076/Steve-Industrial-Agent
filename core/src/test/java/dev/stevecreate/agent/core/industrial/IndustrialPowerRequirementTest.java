package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndustrialPowerRequirementTest {
    @Test
    void electricalAndItemFuelAuthoritiesAreMutuallyExclusive() {
        IndustrialPowerRequirement electrical = IndustrialPowerRequirement.electrical(
                2_400, id("network:lv"), "a".repeat(64));
        IndustrialPowerRequirement fuel = IndustrialPowerRequirement.itemFuel(
                Map.of(id("minecraft:coal"), 1L), 200);

        assertThat(electrical.mode()).isEqualTo(IndustrialPowerMode.ELECTRICAL_NETWORK);
        assertThat(electrical.requiredEnergyFe()).isEqualTo(2_400);
        assertThat(electrical.fuelItems()).isEmpty();
        assertThat(fuel.mode()).isEqualTo(IndustrialPowerMode.ITEM_FUEL);
        assertThat(fuel.requiredEnergyFe()).isZero();
        assertThat(fuel.fuelItems()).containsExactly(Map.entry(id("minecraft:coal"), 1L));
        assertThat(fuel.electricalNetworkId()).isEmpty();

        assertThatThrownBy(() -> new IndustrialPowerRequirement(
                IndustrialPowerMode.ITEM_FUEL, 2_400,
                Map.of(id("minecraft:coal"), 1L), 200,
                java.util.Optional.empty(), java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndustrialPowerRequirement(
                IndustrialPowerMode.ELECTRICAL_NETWORK, 2_400,
                Map.of(id("minecraft:coal"), 1L), 0,
                java.util.Optional.of(id("network:lv")),
                java.util.Optional.of("a".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fuelRowsAreBoundedCanonicalAndDefensive() {
        java.util.LinkedHashMap<ResourceId, Long> source = new java.util.LinkedHashMap<>();
        source.put(id("minecraft:charcoal"), 2L);
        source.put(id("minecraft:coal"), 1L);
        IndustrialPowerRequirement requirement = IndustrialPowerRequirement.itemFuel(source, 400);
        source.clear();

        assertThat(requirement.fuelItems().keySet()).extracting(ResourceId::toString)
                .containsExactly("minecraft:charcoal", "minecraft:coal");
        assertThatThrownBy(() -> IndustrialPowerRequirement.itemFuel(Map.of(), 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndustrialPowerRequirement.itemFuel(
                Map.of(id("minecraft:coal"), 0L), 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
