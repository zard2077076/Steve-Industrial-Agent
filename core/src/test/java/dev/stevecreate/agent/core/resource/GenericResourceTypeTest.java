package dev.stevecreate.agent.core.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GenericResourceTypeTest {
    @Test
    void definesTheSevenLoaderNeutralResourceCategories() {
        assertThat(GenericResourceType.values()).containsExactly(
                GenericResourceType.ITEM,
                GenericResourceType.FLUID,
                GenericResourceType.ROTATIONAL_POWER,
                GenericResourceType.ELECTRICAL_ENERGY,
                GenericResourceType.CHEMICAL,
                GenericResourceType.HEAT,
                GenericResourceType.AIRFLOW);
    }

    @Test
    void stableSerializedNamesAreUniqueAndRoundTrip() {
        assertThat(Arrays.stream(GenericResourceType.values())
                .map(GenericResourceType::serializedName))
                .containsExactly(
                        "item",
                        "fluid",
                        "rotational_power",
                        "electrical_energy",
                        "chemical",
                        "heat",
                        "airflow")
                .doesNotHaveDuplicates();

        for (GenericResourceType type : GenericResourceType.values()) {
            assertThat(GenericResourceType.fromSerializedName(type.serializedName())).isSameAs(type);
        }
    }

    @Test
    void rejectsMissingOrUnknownSerializedNames() {
        assertThatNullPointerException()
                .isThrownBy(() -> GenericResourceType.fromSerializedName(null))
                .withMessage("serializedName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GenericResourceType.fromSerializedName(""))
                .withMessage("Unknown generic resource type: ");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GenericResourceType.fromSerializedName("forge_energy"))
                .withMessage("Unknown generic resource type: forge_energy");
    }
}
