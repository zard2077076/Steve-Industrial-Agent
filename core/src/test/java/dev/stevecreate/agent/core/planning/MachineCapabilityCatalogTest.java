package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MachineCapabilityCatalogTest {
    @Test
    void ordersAndQueriesCapabilitiesDeterministically() {
        MachineCapability pressing = capability(
                "industrial:pressing", "test:create_adapter", "create:pressing",
                GenericResourceType.ITEM);
        MachineCapability alternatePressing = capability(
                "other:pressing", "other:adapter", "create:pressing",
                GenericResourceType.ITEM);
        MachineCapability milling = capability(
                "industrial:milling", "test:create_adapter", "create:milling",
                GenericResourceType.ITEM);

        MachineCapabilityCatalog catalog = new ImmutableMachineCapabilityCatalog(
                List.of(pressing, alternatePressing, milling));

        assertThat(catalog.capabilities()).extracting(value -> value.capabilityId().toString())
                .containsExactly("industrial:milling", "industrial:pressing", "other:pressing");
        assertThat(catalog.capabilitiesForRecipeType(MachineCapabilityTest.id("create:pressing")))
                .extracting(value -> value.capabilityId().toString())
                .containsExactly("industrial:pressing", "other:pressing");
        assertThat(catalog.capabilitiesForAdapter(MachineCapabilityTest.id("test:create_adapter")))
                .extracting(value -> value.capabilityId().toString())
                .containsExactly("industrial:milling", "industrial:pressing");
        assertThat(catalog.compatibleCapabilities(MachineCapabilityTest.pressingRecipe()))
                .containsExactly(pressing);
        assertThat(catalog.find(MachineCapabilityTest.id("industrial:milling"))).contains(milling);
    }

    @Test
    void defensivelyCopiesAndRejectsDuplicateCapabilityIds() {
        MachineCapability capability = capability(
                "industrial:pressing", "test:adapter", "create:pressing",
                GenericResourceType.ITEM);
        List<MachineCapability> source = new ArrayList<>(List.of(capability));
        MachineCapabilityCatalog catalog = new ImmutableMachineCapabilityCatalog(source);
        source.clear();

        assertThat(catalog.capabilities()).containsExactly(capability);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> catalog.capabilities().clear());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImmutableMachineCapabilityCatalog(List.of(capability, capability)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ImmutableMachineCapabilityCatalog(List.of()));
    }

    private static MachineCapability capability(
            String capabilityId,
            String adapterId,
            String recipeType,
            GenericResourceType outputType) {
        return new MachineCapability(
                MachineCapabilityTest.id(capabilityId),
                MachineCapabilityTest.id(adapterId),
                Set.of(MachineCapabilityTest.id(recipeType)),
                Set.of(GenericResourceType.ITEM),
                Set.of(outputType),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(MachineCapabilityTest.id("test:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(MachineCapabilityTest.id("test:failure")),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }
}
