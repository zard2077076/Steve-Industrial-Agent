package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CapabilityVersionLimits;
import dev.stevecreate.agent.core.planning.ImmutableMachineCapabilityCatalog;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeMachineCapabilityCatalogContractTest {
    @Test
    void carriesTwoCanonicalRuntimeAttributedCapabilitiesWithoutPhysicalLayoutData() {
        RuntimeFingerprint runtime = runtime();
        RuntimeMachineCapabilityDeclaration pressing = declaration(
                "create:pressing", "create:pressing/iron_ingot", runtime);
        RuntimeMachineCapabilityDeclaration milling = declaration(
                "create:milling", "create:milling/cobblestone", runtime);

        RuntimeMachineCapabilityCatalogSnapshot snapshot =
                new RuntimeMachineCapabilityCatalogSnapshot(
                        new ImmutableMachineCapabilityCatalog(List.of(pressing.capability(), milling.capability())),
                        List.of(pressing, milling),
                        runtime,
                        "sha256:runtime");

        assertThat(snapshot.declarations())
                .extracting(value -> value.capability().capabilityId())
                .containsExactly(id("create:milling"), id("create:pressing"));
        assertThat(snapshot.catalog().capabilities()).extracting(MachineCapability::capabilityId)
                .containsExactly(id("create:milling"), id("create:pressing"));
        assertThat(snapshot.declarations()).allSatisfy(value -> {
            assertThat(value.physicalExecutionAvailable()).isTrue();
            assertThat(value.capability().requiredResources()).containsExactly(
                    new CapabilityResourceRequirement(
                            GenericResourceType.ROTATIONAL_POWER, 1, true));
            assertThat(value.capability().inputPortTypes()).containsExactly(GenericResourceType.ITEM);
            assertThat(value.capability().outputPortTypes()).containsExactly(GenericResourceType.ITEM);
            assertThat(value.source()).isEqualTo(RuntimeMachineCapabilitySource.VERSIONED_ADAPTER);
            assertThat(value.runtimeFingerprint()).isEqualTo("sha256:runtime");
        });
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snapshot.declarations().clear());
    }

    @Test
    void rejectsFalsePhysicalClaimsAndFingerprintMismatch() {
        RuntimeFingerprint runtime = runtime();
        MachineCapability capability = capability("create:milling", "sha256:runtime");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeMachineCapabilityDeclaration(
                        capability,
                        false,
                        Set.of(id("create:milling/cobblestone")),
                        runtime,
                        "sha256:runtime",
                        RuntimeMachineCapabilitySource.VERSIONED_ADAPTER));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeMachineCapabilityDeclaration(
                        capability,
                        true,
                        Set.of(id("create:milling/cobblestone")),
                        runtime,
                        "sha256:other",
                        RuntimeMachineCapabilitySource.VERSIONED_ADAPTER));
    }

    private static RuntimeMachineCapabilityDeclaration declaration(
            String capabilityId,
            String provenRecipe,
            RuntimeFingerprint runtime) {
        return new RuntimeMachineCapabilityDeclaration(
                capability(capabilityId, "sha256:runtime"),
                true,
                Set.of(id(provenRecipe)),
                runtime,
                "sha256:runtime",
                RuntimeMachineCapabilitySource.VERSIONED_ADAPTER);
    }

    private static MachineCapability capability(String capabilityId, String fingerprint) {
        return new MachineCapability(
                id(capabilityId),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                Set.of(id(capabilityId)),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(id("create:v606/process")),
                Set.of(
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("steve_industrial:no_power"), id("steve_industrial:overstressed")),
                new CapabilityVersionLimits(
                        Optional.of("6.0.6"), Optional.of("6.0.7"), Set.of(fingerprint)));
    }

    private static RuntimeFingerprint runtime() {
        return new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                Map.of("create", "6.0.6-150"),
                "steve_industrial:create_runtime_1_20_1_6_0_6",
                1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
