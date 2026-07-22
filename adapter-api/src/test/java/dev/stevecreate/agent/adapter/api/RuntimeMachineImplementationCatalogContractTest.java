package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.binding.ImmutableMachineImplementationCatalog;
import dev.stevecreate.agent.core.binding.ImplementationDescriptorSource;
import dev.stevecreate.agent.core.binding.ImplementationExecutionSupport;
import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.MachineImplementationDescriptor;
import dev.stevecreate.agent.core.binding.PortTemporalSemantics;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeMachineImplementationCatalogContractTest {
    @Test
    void carriesExactlyTwoRuntimeAttributedCreateImplementations() {
        RuntimeFingerprint runtime = runtime();
        MachineImplementationDescriptor millstone = descriptor(
                "create:mechanical_millstone", "create:milling",
                "create:milling/cobblestone", "create:inventory_batch_milling");
        MachineImplementationDescriptor press = descriptor(
                "create:mechanical_press", "create:pressing",
                "create:pressing/iron_ingot", "create:belt_held_pressing");
        RuntimeMachineImplementationCatalogSnapshot snapshot =
                new RuntimeMachineImplementationCatalogSnapshot(
                        new ImmutableMachineImplementationCatalog(
                                List.of(press, millstone), "sha256:runtime", 3),
                        runtime,
                        "sha256:runtime",
                        3);

        assertThat(snapshot.catalog().implementations())
                .extracting(MachineImplementationDescriptor::implementationId)
                .containsExactly(id("create:mechanical_millstone"), id("create:mechanical_press"));
        assertThat(snapshot.catalog().implementations()).allSatisfy(value -> {
            assertThat(value.adapterId()).isEqualTo(id(runtime.adapterId()));
            assertThat(value.minecraftVersion()).isEqualTo("1.20.1");
            assertThat(value.modId()).isEqualTo("create");
            assertThat(value.modVersion()).isEqualTo("6.0.6-150");
            assertThat(value.runtimeFingerprint()).isEqualTo("sha256:runtime");
            assertThat(value.executionSupport())
                    .isEqualTo(ImplementationExecutionSupport.PHYSICALLY_VERIFIED);
            assertThat(value.bindingAllowed()).isTrue();
            assertThat(value.ports()).extracting(ImplementationPortContract::role)
                    .containsExactlyInAnyOrder(
                            ImplementationPortRole.ITEM_INPUT,
                            ImplementationPortRole.ITEM_OUTPUT,
                            ImplementationPortRole.ROTATIONAL_POWER_INPUT);
        });
    }

    @Test
    void rejectsRuntimeOrReloadIdentityDrift() {
        MachineImplementationDescriptor millstone = descriptor(
                "create:mechanical_millstone", "create:milling",
                "create:milling/cobblestone", "create:inventory_batch_milling");
        ImmutableMachineImplementationCatalog catalog =
                new ImmutableMachineImplementationCatalog(
                        List.of(millstone), "sha256:runtime", 3);
        assertThatThrownBy(() -> new RuntimeMachineImplementationCatalogSnapshot(
                catalog, runtime(), "sha256:other", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> new RuntimeMachineImplementationCatalogSnapshot(
                catalog, runtime(), "sha256:runtime", 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation");
    }

    private static MachineImplementationDescriptor descriptor(
            String implementationId,
            String capabilityId,
            String provenRecipe,
            String processingMode) {
        return new MachineImplementationDescriptor(
                id(implementationId), id(runtime().adapterId()), Set.of(id(capabilityId)),
                id(implementationId + "_family"), Set.of(id(capabilityId)),
                Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                List.of(
                        port(implementationId + "_item_input", ImplementationPortRole.ITEM_INPUT,
                                GenericResourceType.ITEM, PortMode.INPUT),
                        port(implementationId + "_item_output", ImplementationPortRole.ITEM_OUTPUT,
                                GenericResourceType.ITEM, PortMode.OUTPUT),
                        port(implementationId + "_power_input",
                                ImplementationPortRole.ROTATIONAL_POWER_INPUT,
                                GenericResourceType.ROTATIONAL_POWER, PortMode.INPUT)),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                true, id(processingMode),
                Set.of(
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("steve_industrial:no_rotational_power")),
                ImplementationExecutionSupport.PHYSICALLY_VERIFIED,
                Set.of(id(provenRecipe)), true, "1.20.1", "create", "6.0.6-150",
                "sha256:runtime", ImplementationDescriptorSource.VERSIONED_ADAPTER,
                List.of("orientation-dependent physical ports require layout"), 0);
    }

    private static ImplementationPortContract port(
            String portId,
            ImplementationPortRole role,
            GenericResourceType type,
            PortMode mode) {
        return new ImplementationPortContract(
                id(portId), role, Optional.of(type), mode, 1, OptionalLong.empty(), true,
                false, PortTemporalSemantics.CONTINUOUS,
                Set.of(id(type == GenericResourceType.ROTATIONAL_POWER
                        ? "create:kinetic_network" : "steve_industrial:direct_item")),
                Set.of(type == GenericResourceType.ROTATIONAL_POWER
                        ? VerificationEvidenceKind.POWER_PRESENT
                        : mode == PortMode.INPUT
                        ? VerificationEvidenceKind.INPUT_CONSUMED
                        : VerificationEvidenceKind.OUTPUT_PRODUCED));
    }

    private static RuntimeFingerprint runtime() {
        return new RuntimeFingerprint(
                "1.20.1", "forge", "47.4.10", Map.of("create", "6.0.6-150"),
                "steve_industrial:create_runtime_1_20_1_6_0_6", 1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
