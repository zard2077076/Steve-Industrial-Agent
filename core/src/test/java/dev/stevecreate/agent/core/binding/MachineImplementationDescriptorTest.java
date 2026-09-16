package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MachineImplementationDescriptorTest {
    @Test
    void describesARealStableMillstoneImplementationWithoutPhysicalAuthority() {
        MachineImplementationDescriptor descriptor = millstone();

        assertThat(descriptor.implementationId()).isEqualTo(id("create:mechanical_millstone"));
        assertThat(descriptor.adapterId())
                .isEqualTo(id("steve_industrial:create_runtime_1_20_1_6_0_6"));
        assertThat(descriptor.capabilityIds()).containsExactly(id("create:milling"));
        assertThat(descriptor.supportedRecipeTypes()).containsExactly(id("create:milling"));
        assertThat(descriptor.inputResourceTypes()).containsExactly(GenericResourceType.ITEM);
        assertThat(descriptor.outputResourceTypes()).containsExactly(GenericResourceType.ITEM);
        assertThat(descriptor.ports()).extracting(ImplementationPortContract::role)
                .containsExactly(
                        ImplementationPortRole.ITEM_INPUT,
                        ImplementationPortRole.ITEM_OUTPUT,
                        ImplementationPortRole.ROTATIONAL_POWER_INPUT);
        assertThat(descriptor.powerInputContracts()).containsExactly(
                new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true));
        assertThat(descriptor.requiresContinuousPower()).isTrue();
        assertThat(descriptor.executionSupport())
                .isEqualTo(ImplementationExecutionSupport.PHYSICALLY_VERIFIED);
        assertThat(descriptor.physicallyVerifiedRecipeIds())
                .containsExactly(id("create:milling/cobblestone"));
        assertThat(descriptor.runtimeFingerprint()).isEqualTo("sha256:runtime");
        assertThat(descriptor.limitations())
                .containsExactly("orientation-dependent physical ports require layout");

        Set<String> forbidden = Set.of(
                "position", "coordinate", "orientation", "direction", "side", "layout",
                "world", "blockentity", "unifiedmachinegraph", "session", "executor");
        assertThat(Arrays.stream(MachineImplementationDescriptor.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .map(String::toLowerCase)
                        .noneMatch(name -> forbidden.stream().anyMatch(name::contains)))
                .isTrue();
        assertThat(Arrays.stream(ImplementationPortContract.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .map(String::toLowerCase)
                        .noneMatch(name -> Set.of(
                                        "position", "coordinate", "orientation", "direction",
                                        "side", "adjacent", "distance", "layout")
                                .stream().anyMatch(name::contains)))
                .isTrue();
    }

    @Test
    void definesEveryRequiredLogicalPortRoleAndStableSerializedName() {
        assertThat(ImplementationPortRole.values()).containsExactly(
                ImplementationPortRole.ITEM_INPUT,
                ImplementationPortRole.ITEM_OUTPUT,
                ImplementationPortRole.FLUID_INPUT,
                ImplementationPortRole.FLUID_OUTPUT,
                ImplementationPortRole.ROTATIONAL_POWER_INPUT,
                ImplementationPortRole.ROTATIONAL_POWER_OUTPUT,
                ImplementationPortRole.ELECTRICAL_ENERGY_INPUT,
                ImplementationPortRole.ELECTRICAL_ENERGY_OUTPUT,
                ImplementationPortRole.HEAT_INPUT,
                ImplementationPortRole.HEAT_OUTPUT,
                ImplementationPortRole.AIRFLOW_INPUT,
                ImplementationPortRole.AIRFLOW_OUTPUT,
                ImplementationPortRole.REDSTONE_CONTROL,
                ImplementationPortRole.SIGNAL_INPUT,
                ImplementationPortRole.SIGNAL_OUTPUT,
                ImplementationPortRole.UNSUPPORTED_SPECIAL_PORT);
        assertThat(Arrays.stream(ImplementationPortRole.values())
                        .map(ImplementationPortRole::serializedName))
                .doesNotHaveDuplicates();
        for (ImplementationPortRole role : ImplementationPortRole.values()) {
            assertThat(ImplementationPortRole.fromSerializedName(role.serializedName()))
                    .contains(role);
        }
        assertThat(ImplementationPortRole.fromSerializedName("north_item_input")).isEmpty();
    }

    @Test
    void rejectsPortRoleResourceModeAndThroughputMismatch() {
        assertThatThrownBy(() -> new ImplementationPortContract(
                id("create:millstone_item_input"),
                ImplementationPortRole.ITEM_INPUT,
                Optional.of(GenericResourceType.FLUID),
                PortMode.INPUT,
                1,
                OptionalLong.empty(),
                true,
                false,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(id("steve_industrial:direct_item")),
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource type");

        assertThatThrownBy(() -> itemPort(
                "create:millstone_item_input", ImplementationPortRole.ITEM_INPUT, PortMode.OUTPUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");

        assertThatThrownBy(() -> new ImplementationPortContract(
                id("create:millstone_item_input"),
                ImplementationPortRole.ITEM_INPUT,
                Optional.of(GenericResourceType.ITEM),
                PortMode.INPUT,
                10,
                OptionalLong.of(9),
                true,
                false,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(id("steve_industrial:direct_item")),
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("throughput");
    }

    @Test
    void requiresDescriptorResourcesAndPowerToBeBackedByLogicalPorts() {
        MachineImplementationDescriptor valid = millstone();
        assertThat(valid.ports()).hasSize(3);

        assertThatThrownBy(() -> descriptor(
                List.of(
                        itemPort("create:millstone_item_input", ImplementationPortRole.ITEM_INPUT,
                                PortMode.INPUT),
                        itemPort("create:millstone_item_output", ImplementationPortRole.ITEM_OUTPUT,
                                PortMode.OUTPUT)),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power");

        assertThatThrownBy(() -> descriptor(
                millstonePorts(),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiresContinuousPower");
    }

    @Test
    void executionProofAndBindingPermissionCannotBeFabricated() {
        assertThatThrownBy(() -> new MachineImplementationDescriptor(
                id("create:mechanical_millstone"),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                Set.of(id("create:milling")),
                id("create:millstone"),
                Set.of(id("create:milling")),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                millstonePorts(),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                true,
                id("create:inventory_batch_milling"),
                Set.of(VerificationEvidenceKind.INPUT_CONSUMED),
                Set.of(id("steve_industrial:no_rotational_power")),
                ImplementationExecutionSupport.DESCRIBED_ONLY,
                Set.of(id("create:milling/cobblestone")),
                true,
                "1.20.1",
                "create",
                "6.0.6",
                "sha256:runtime",
                ImplementationDescriptorSource.VERSIONED_ADAPTER,
                List.of(),
                10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Physical execution proof");
    }

    static MachineImplementationDescriptor millstone() {
        return descriptor(
                millstonePorts(),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                true);
    }

    private static MachineImplementationDescriptor descriptor(
            List<ImplementationPortContract> ports,
            Set<CapabilityResourceRequirement> power,
            boolean continuous) {
        return new MachineImplementationDescriptor(
                id("create:mechanical_millstone"),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                Set.of(id("create:milling")),
                id("create:millstone"),
                Set.of(id("create:milling")),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                ports,
                power,
                continuous,
                id("create:inventory_batch_milling"),
                Set.of(
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(
                        id("steve_industrial:no_rotational_power"),
                        id("steve_industrial:overstressed")),
                ImplementationExecutionSupport.PHYSICALLY_VERIFIED,
                Set.of(id("create:milling/cobblestone")),
                true,
                "1.20.1",
                "create",
                "6.0.6",
                "sha256:runtime",
                ImplementationDescriptorSource.VERSIONED_ADAPTER,
                List.of("orientation-dependent physical ports require layout"),
                10);
    }

    private static List<ImplementationPortContract> millstonePorts() {
        return List.of(
                itemPort("create:millstone_item_input", ImplementationPortRole.ITEM_INPUT,
                        PortMode.INPUT),
                itemPort("create:millstone_item_output", ImplementationPortRole.ITEM_OUTPUT,
                        PortMode.OUTPUT),
                new ImplementationPortContract(
                        id("create:millstone_rotational_power_input"),
                        ImplementationPortRole.ROTATIONAL_POWER_INPUT,
                        Optional.of(GenericResourceType.ROTATIONAL_POWER),
                        PortMode.INPUT,
                        1,
                        OptionalLong.empty(),
                        true,
                        false,
                        PortTemporalSemantics.CONTINUOUS,
                        Set.of(id("create:kinetic_network")),
                        Set.of(
                                VerificationEvidenceKind.NETWORK_CONNECTED,
                                VerificationEvidenceKind.POWER_PRESENT)));
    }

    private static ImplementationPortContract itemPort(
            String portId,
            ImplementationPortRole role,
            PortMode mode) {
        return new ImplementationPortContract(
                id(portId),
                role,
                Optional.of(GenericResourceType.ITEM),
                mode,
                1,
                OptionalLong.empty(),
                true,
                false,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(id("steve_industrial:direct_item")),
                Set.of(role == ImplementationPortRole.ITEM_INPUT
                        ? VerificationEvidenceKind.INPUT_CONSUMED
                        : VerificationEvidenceKind.OUTPUT_PRODUCED));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
