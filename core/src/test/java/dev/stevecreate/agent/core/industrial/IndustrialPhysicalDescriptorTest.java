package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.PortTemporalSemantics;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IndustrialPhysicalDescriptorTest {
    @Test
    void modelsFixedElectricalMultiblockAndExactEngineerToolLifecycle() {
        IndustrialActionContract form = new IndustrialActionContract(
                id("ie:form_metal_press"), IndustrialActionType.FORM_MULTIBLOCK,
                Optional.of(id("immersiveengineering:hammer")), Set.of(), true, true,
                false, 1, "formed multiblock identity changes exactly once");
        IndustrialLifecycleContract lifecycle = new IndustrialLifecycleContract(
                Set.of(id("condition:structure_complete"), id("condition:energy_available")),
                Set.of(id("status:formed"), id("status:active"), id("status:output")),
                List.of(form, new IndustrialActionContract(
                        id("ie:observe_metal_press"), IndustrialActionType.OBSERVE_STATUS,
                        Optional.empty(), Set.of(GenericResourceType.ITEM,
                                GenericResourceType.ELECTRICAL_ENERGY), true, true, true, 3,
                        "input output active and energy deltas are observed")),
                false, true, "reload requires exact structure and network fingerprint reconciliation");
        MultiblockStructureContract structure = new MultiblockStructureContract(
                id("immersiveengineering:metal_press"),
                List.of(new MultiblockComponentContract(id("role:steel_scaffolding"),
                        id("immersiveengineering:steel_scaffolding_standard"),
                        new BlockPos3i(0, 0, 0), java.util.Map.of(), false)),
                Set.of(QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90), false,
                form.actionId(), id("status:formed"), "a".repeat(64));
        IndustrialPhysicalDescriptor descriptor = new IndustrialPhysicalDescriptor(
                id("ie1020:metal_press"), id("ie1020:adapter"), Optional.empty(),
                List.of(port(ImplementationPortRole.ITEM_INPUT),
                        port(ImplementationPortRole.ITEM_OUTPUT),
                        port(ImplementationPortRole.ELECTRICAL_ENERGY_INPUT)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ELECTRICAL_ENERGY),
                Optional.of(structure), lifecycle, "ie-10.2.0-183", 1);

        assertThat(descriptor.multiblock()).contains(structure);
        assertThat(descriptor.lifecycle().actions()).extracting(IndustrialActionContract::actionId)
                .containsExactly(id("ie:form_metal_press"), id("ie:observe_metal_press"));
    }

    @Test
    void toolActionsAndPhysicalGeometryFailClosedWhenIncomplete() {
        assertThatThrownBy(() -> new IndustrialActionContract(
                id("ie:form"), IndustrialActionType.FORM_MULTIBLOCK, Optional.empty(),
                Set.of(), true, true, false, 1, "formed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retained tool");
    }

    private static ImplementationPortContract port(ImplementationPortRole role) {
        return new ImplementationPortContract(
                id("port:" + role.serializedName()), role, role.expectedResourceType(),
                role.expectedMode(), 1, OptionalLong.empty(), true, false,
                PortTemporalSemantics.CONTINUOUS, Set.of(),
                Set.of(VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
