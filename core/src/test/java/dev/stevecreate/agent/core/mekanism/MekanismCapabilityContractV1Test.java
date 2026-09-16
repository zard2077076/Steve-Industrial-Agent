package dev.stevecreate.agent.core.mekanism;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MekanismCapabilityContractV1Test {
    @Test
    void everyDeclaredCapabilityAcceptsNoRuntimeUntilOneIsObserved() {
        // The point of the whole contract: it must be unusable as evidence. An empty
        // fingerprint set is how CapabilityVersionLimits already says that, so a later
        // change that quietly populates it would fail here.
        assertThat(MekanismCapabilityContractV1.declaredCapabilities())
                .isNotEmpty()
                .allSatisfy(capability -> assertThat(
                        capability.versionLimits().acceptedRuntimeFingerprints()).isEmpty());
        // The lower bound exists only because CapabilityVersionLimits refuses a declaration
        // that bounds nothing; it names a candidate pin, not a verified one.
        assertThat(MekanismCapabilityContractV1.unverified().minimumInclusive())
                .contains(MekanismCapabilityContractV1.CANDIDATE_PIN);
        assertThat(MekanismCapabilityContractV1.unverified().maximumExclusive()).isEmpty();
    }

    @Test
    void theEnrichmentChamberIsDrivenByElectricalEnergyNotRotationalPower() {
        MachineCapability chamber = MekanismCapabilityContractV1.enrichmentChamber();

        assertThat(chamber.requiredResources())
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.resourceType())
                            .isEqualTo(GenericResourceType.ELECTRICAL_ENERGY);
                    assertThat(requirement.continuous()).isTrue();
                });
        assertThat(chamber.inputPortTypes()).containsExactly(GenericResourceType.ITEM);
        assertThat(chamber.outputPortTypes()).containsExactly(GenericResourceType.ITEM);
        assertThat(chamber.supportedRecipeTypes())
                .containsExactly(MekanismCapabilityContractV1.ENRICHING);
    }

    @Test
    void chemicalFormsAllMapToTheOneGenericCategoryAndRoundTrip() {
        assertThat(MekanismChemicalForm.values()).hasSize(4);
        for (MekanismChemicalForm form : MekanismChemicalForm.values()) {
            assertThat(form.genericResourceType()).isEqualTo(GenericResourceType.CHEMICAL);
            assertThat(MekanismChemicalForm.fromSerializedName(form.serializedName()))
                    .isEqualTo(form);
        }
        assertThat(Arrays.stream(MekanismChemicalForm.values())
                .map(MekanismChemicalForm::serializedName))
                .containsExactly("gas", "infusion", "pigment", "slurry");
    }

    @Test
    void anUnknownChemicalFormIsRefusedRatherThanDefaulted() {
        assertThatThrownBy(() -> MekanismChemicalForm.fromSerializedName("plasma"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plasma");
    }

    @Test
    void noNuclearOrRadiationCapabilityIsDeclared() {
        // AGENTS.md keeps that surface disabled until its own safety gate exists and is
        // approved. Declaring it here would be the first step toward planning it.
        assertThat(MekanismCapabilityContractV1.declaredCapabilities())
                .allSatisfy(capability -> assertThat(capability.capabilityId().toString())
                        .doesNotContain("fission")
                        .doesNotContain("fusion")
                        .doesNotContain("reactor")
                        .doesNotContain("radiation")
                        .doesNotContain("waste"));
    }
}
