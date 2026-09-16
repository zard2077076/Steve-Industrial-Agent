package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.PortTemporalSemantics;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.electrical.ElectricalWireEdge;
import dev.stevecreate.agent.core.electrical.VoltageTier;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialDisposition;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialPurpose;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IndustrialProductionPlannerTest {
    @Test
    void plansMetalPressFromRecipeMultiblockSiteMaterialsToolsAndFe() {
        BlockPos3i anchor = new BlockPos3i(20, 64, 20);
        IndustrialProductionPlanner.PlanningResult result = new IndustrialProductionPlanner().plan(
                request(anchor, 2, 2_400, network(100, 0), true));

        assertThat(result).isInstanceOf(IndustrialProductionPlanner.Ready.class);
        IndustrialProductionPlan plan = ((IndustrialProductionPlanner.Ready) result).plan();
        assertThat(plan.batches()).isEqualTo(2);
        assertThat(plan.requiredEnergyFe()).isEqualTo(4_800);
        assertThat(plan.botWorkers()).isEqualTo(3);
        assertThat(plan.components()).extracting(IndustrialComponentPlacement::position)
                .containsExactly(new BlockPos3i(20, 64, 20), new BlockPos3i(20, 64, 21));
        assertThat(plan.itemMaterialLines()).filteredOn(value ->
                        value.purpose() == ProjectMaterialPurpose.PROCESS_INPUT)
                .singleElement().satisfies(value -> {
                    assertThat(value.resourceId()).isEqualTo(id("forge:ingots/iron"));
                    assertThat(value.quantity()).isEqualTo(2);
                    assertThat(value.disposition()).isEqualTo(ProjectMaterialDisposition.CONSUME);
                });
        assertThat(plan.itemMaterialLines()).filteredOn(value ->
                        value.disposition() == ProjectMaterialDisposition.LEASE)
                .extracting(value -> value.resourceId().toString())
                .containsExactly("immersiveengineering:hammer", "immersiveengineering:mold_plate");
        assertThat(plan.itemMaterialLines()).filteredOn(value ->
                        value.purpose() == ProjectMaterialPurpose.POWER_COMPONENT)
                .singleElement().satisfies(value ->
                        assertThat(value.resourceId()).isEqualTo(
                                id("immersiveengineering:wirecoil_copper")));
        assertThat(plan.executionSteps()).extracting(IndustrialExecutionStep::actionType)
                .containsExactly(IndustrialActionType.PLACE_BLOCK,
                        IndustrialActionType.FORM_MULTIBLOCK,
                        IndustrialActionType.CONNECT_RESOURCE,
                        IndustrialActionType.INSERT_RESOURCE,
                        IndustrialActionType.OBSERVE_STATUS);
        assertThat(plan.planSha256()).hasSize(64);
    }

    @Test
    void refusesMissingEnergyObstructedSiteAndBotUnsupportedActionIndependently() {
        BlockPos3i anchor = new BlockPos3i(20, 64, 20);
        assertThat(new IndustrialProductionPlanner().plan(
                request(anchor, 2, 20_000, network(1, 0), true)))
                .isEqualTo(new IndustrialProductionPlanner.Refused(
                        "ELECTRICAL_ENERGY_INSUFFICIENT",
                        "electrical network cannot authorize the selected production batch"));

        var obstructed = request(anchor, 1, 2_400, network(100, 0), true);
        obstructed = new IndustrialProductionPlanner.Request(obstructed.projectId(),
                obstructed.target(), obstructed.targetQuantity(), obstructed.process(),
                obstructed.descriptor(), obstructed.anchor(), obstructed.orientation(),
                Set.of(anchor), obstructed.retainedTools(), obstructed.energyPerBatchFe(),
                obstructed.electricalNetwork(), obstructed.fluidNetwork(), true, 3);
        assertThat(new IndustrialProductionPlanner().plan(obstructed))
                .isEqualTo(new IndustrialProductionPlanner.Refused(
                        "SITE_OBSTRUCTED_OR_UNAUTHORIZED",
                        "not every multiblock component is inside the verified buildable site"));

        var directOnlyDescriptor = descriptor(false);
        var request = request(anchor, 1, 2_400, network(100, 0), true);
        request = new IndustrialProductionPlanner.Request(request.projectId(), request.target(),
                request.targetQuantity(), request.process(), directOnlyDescriptor, request.anchor(),
                request.orientation(), request.buildablePositions(), request.retainedTools(),
                request.energyPerBatchFe(), request.electricalNetwork(), request.fluidNetwork(),
                true, 3);
        assertThat(new IndustrialProductionPlanner().plan(request))
                .isEqualTo(new IndustrialProductionPlanner.Refused(
                        "BOT_ACTION_UNSUPPORTED",
                        "one or more required lifecycle actions lack Bot support"));
    }

    @Test
    void capabilityProfileKeepsDiscoveryAndExecutionClaimsDistinct() {
        IndustrialCapabilityProfile readOnly = new IndustrialCapabilityProfile(
                id("adapter:ie"), Set.of(IndustrialCapability.ITEM_PROCESSING,
                        IndustrialCapability.ELECTRICAL_POWER,
                        IndustrialCapability.MULTIBLOCK), "ie-runtime", true, false,
                "recipe template and FE scans only");
        assertThat(readOnly.supports(IndustrialCapability.MULTIBLOCK)).isTrue();
        assertThat(readOnly.physicalExecutionImplemented()).isFalse();
    }

    @Test
    void plansFuelPoweredAlloySmelterWithoutInventingFeOrAnElectricalNetwork() {
        BlockPos3i anchor = new BlockPos3i(40, 64, 40);
        IndustrialProductionPlanner.PlanningResult result = new IndustrialProductionPlanner().plan(
                fuelRequest(anchor, fuelDescriptor(true)));

        assertThat(result).isInstanceOf(IndustrialProductionPlanner.Ready.class);
        IndustrialProductionPlan plan = ((IndustrialProductionPlanner.Ready) result).plan();
        assertThat(plan.batches()).isEqualTo(2);
        assertThat(plan.requiredEnergyFe()).isZero();
        assertThat(plan.powerRequirement().mode()).isEqualTo(IndustrialPowerMode.ITEM_FUEL);
        assertThat(plan.powerRequirement().fuelItems())
                .containsExactly(Map.entry(id("minecraft:coal"), 2L));
        assertThat(plan.powerRequirement().minimumBurnTicks()).isEqualTo(400);
        assertThat(plan.powerRequirement().electricalNetworkId()).isEmpty();
        assertThat(plan.itemMaterialLines()).filteredOn(value ->
                        value.purpose() == ProjectMaterialPurpose.FUEL)
                .singleElement().satisfies(value -> {
                    assertThat(value.resourceId()).isEqualTo(id("minecraft:coal"));
                    assertThat(value.quantity()).isEqualTo(2);
                    assertThat(value.disposition()).isEqualTo(ProjectMaterialDisposition.CONSUME);
                });
        assertThat(plan.executionSteps()).extracting(IndustrialExecutionStep::actionType)
                .containsExactly(IndustrialActionType.PLACE_BLOCK,
                        IndustrialActionType.FORM_MULTIBLOCK,
                        IndustrialActionType.INSERT_RESOURCE,
                        IndustrialActionType.OBSERVE_STATUS);
    }

    @Test
    void fuelPoweredPlanStillRequiresAnExactItemInputPort() {
        BlockPos3i anchor = new BlockPos3i(40, 64, 40);
        IndustrialProductionPlanner.PlanningResult result = new IndustrialProductionPlanner().plan(
                fuelRequest(anchor, fuelDescriptor(false)));

        assertThat(result).isEqualTo(new IndustrialProductionPlanner.Refused(
                "ITEM_FUEL_INPUT_PORT_MISSING",
                "power source cannot authorize the selected production batch"));

        IndustrialPhysicalDescriptor withoutHeat = fuelDescriptor(true, false);
        assertThat(new IndustrialProductionPlanner().plan(
                fuelRequest(anchor, withoutHeat))).isEqualTo(
                        new IndustrialProductionPlanner.Refused(
                                "ITEM_FUEL_HEAT_CAPABILITY_MISSING",
                                "power source cannot authorize the selected production batch"));
    }

    private static IndustrialProductionPlanner.Request request(
            BlockPos3i anchor, long target, long energy, ElectricalNetworkGraph network,
            boolean botSupported) {
        return new IndustrialProductionPlanner.Request(id("project:metal_press"),
                id("immersiveengineering:plate_iron"), target, process(),
                descriptor(botSupported), anchor, QuarterTurn.CLOCKWISE_90,
                Set.of(anchor, new BlockPos3i(anchor.x(), anchor.y(), anchor.z() + 1)),
                Set.of(id("immersiveengineering:mold_plate")), energy, network,
                Optional.empty(), true, 3);
    }

    private static GenericProcessSpec process() {
        return new GenericProcessSpec(id("immersiveengineering:metalpress/plate_iron"),
                id("immersiveengineering:metal_press"),
                List.of(new ProcessResource(id("forge:ingots/iron"), GenericResourceType.ITEM, 1)),
                List.of(new ProcessResource(id("immersiveengineering:plate_iron"),
                        GenericResourceType.ITEM, 1)), List.of(), Set.of(id("ie1020:metal_press")),
                Set.of(id("evidence:formed"), id("evidence:output")), 100,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.EXACT_DECLARED, Map.of());
    }

    private static IndustrialProductionPlanner.Request fuelRequest(
            BlockPos3i anchor, IndustrialPhysicalDescriptor descriptor) {
        return new IndustrialProductionPlanner.Request(id("project:alloy_smelter"),
                id("create:brass_ingot"), 4, fuelProcess(), descriptor, anchor,
                QuarterTurn.ZERO,
                Set.of(anchor, new BlockPos3i(anchor.x() + 1, anchor.y(), anchor.z())),
                Set.of(), IndustrialPowerRequest.itemFuel(
                        Map.of(id("minecraft:coal"), 1L), 200),
                Optional.empty(), true, 3);
    }

    private static GenericProcessSpec fuelProcess() {
        return new GenericProcessSpec(id("immersiveengineering:alloysmelter/brass"),
                id("immersiveengineering:alloy_smelter"),
                List.of(new ProcessResource(id("minecraft:copper_ingot"),
                                GenericResourceType.ITEM, 1),
                        new ProcessResource(id("create:zinc_ingot"),
                                GenericResourceType.ITEM, 1)),
                List.of(new ProcessResource(id("create:brass_ingot"),
                        GenericResourceType.ITEM, 2)), List.of(),
                Set.of(id("ie1020:alloy_smelter")),
                Set.of(id("evidence:formed"), id("evidence:fuel_burned"),
                        id("evidence:output")), 2_400,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.EXACT_DECLARED, Map.of());
    }

    private static IndustrialPhysicalDescriptor fuelDescriptor(boolean itemInput) {
        return fuelDescriptor(itemInput, true);
    }

    private static IndustrialPhysicalDescriptor fuelDescriptor(
            boolean itemInput, boolean heatCapability) {
        BlockPos3i second = new BlockPos3i(1, 0, 0);
        MultiblockStructureContract structure = new MultiblockStructureContract(
                id("immersiveengineering:alloy_smelter"), List.of(
                        new MultiblockComponentContract(id("role:brick_a"),
                                id("immersiveengineering:alloybrick"),
                                new BlockPos3i(0, 0, 0), Map.of(), false),
                        new MultiblockComponentContract(id("role:brick_b"),
                                id("immersiveengineering:alloybrick"), second,
                                Map.of(), false)), Set.of(QuarterTurn.ZERO), false,
                id("ie1020:form_alloy_smelter"), id("ie1020:alloy_smelter_formed"),
                "e".repeat(64));
        IndustrialLifecycleContract lifecycle = new IndustrialLifecycleContract(
                Set.of(id("condition:structure"), id("condition:item_fuel")),
                Set.of(id("status:formed"), id("status:fuel_burned"), id("status:output")),
                List.of(action("fuel-place", IndustrialActionType.PLACE_BLOCK,
                                Optional.empty(), true),
                        action("fuel-form", IndustrialActionType.FORM_MULTIBLOCK,
                                Optional.of(id("immersiveengineering:hammer")), true),
                        action("fuel-insert", IndustrialActionType.INSERT_RESOURCE,
                                Optional.empty(), true),
                        action("fuel-observe", IndustrialActionType.OBSERVE_STATUS,
                                Optional.empty(), true)), false, true,
                "exact alloy inputs item fuel burn output and ledger reconciliation");
        List<ImplementationPortContract> ports = itemInput
                ? List.of(port("alloy_input", ImplementationPortRole.ITEM_INPUT),
                        port("alloy_output", ImplementationPortRole.ITEM_OUTPUT))
                : List.of(port("alloy_output", ImplementationPortRole.ITEM_OUTPUT));
        return new IndustrialPhysicalDescriptor(id("ie1020:alloy_smelter"),
                id("adapter:ie1020"), Optional.empty(), ports,
                heatCapability
                        ? Set.of(GenericResourceType.ITEM, GenericResourceType.HEAT)
                        : Set.of(GenericResourceType.ITEM),
                Optional.of(structure), lifecycle,
                "ie-runtime", 1);
    }

    private static IndustrialPhysicalDescriptor descriptor(boolean botSupported) {
        MultiblockStructureContract structure = new MultiblockStructureContract(
                id("immersiveengineering:metal_press"), List.of(
                        new MultiblockComponentContract(id("role:scaffold"),
                                id("immersiveengineering:steel_scaffolding_standard"),
                                new BlockPos3i(0, 0, 0), Map.of(), false),
                        new MultiblockComponentContract(id("role:piston"),
                                id("immersiveengineering:heavy_engineering"),
                                new BlockPos3i(1, 0, 0), Map.of(), false)),
                Set.of(QuarterTurn.ZERO, QuarterTurn.CLOCKWISE_90), false,
                id("ie1020:form"), id("ie1020:formed"), "a".repeat(64));
        IndustrialLifecycleContract lifecycle = new IndustrialLifecycleContract(
                Set.of(id("condition:structure"), id("condition:energy")),
                Set.of(id("status:formed"), id("status:output")), List.of(
                        action("place", IndustrialActionType.PLACE_BLOCK, Optional.empty(), true),
                        action("form", IndustrialActionType.FORM_MULTIBLOCK,
                                Optional.of(id("immersiveengineering:hammer")), botSupported),
                        action("connect", IndustrialActionType.CONNECT_RESOURCE,
                                Optional.of(id("immersiveengineering:wirecoil_copper")), true),
                        action("mold", IndustrialActionType.INSERT_RESOURCE,
                                Optional.of(id("immersiveengineering:mold_plate")), true),
                        action("observe", IndustrialActionType.OBSERVE_STATUS, Optional.empty(), true)),
                false, true, "exact template network mold and ledger reconciliation");
        return new IndustrialPhysicalDescriptor(id("ie1020:metal_press"), id("adapter:ie1020"),
                Optional.empty(), List.of(port("input", ImplementationPortRole.ITEM_INPUT),
                        port("output", ImplementationPortRole.ITEM_OUTPUT),
                        port("energy", ImplementationPortRole.ELECTRICAL_ENERGY_INPUT)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ELECTRICAL_ENERGY),
                Optional.of(structure), lifecycle, "ie-runtime", 1);
    }

    private static IndustrialActionContract action(
            String name, IndustrialActionType type, Optional<ResourceId> tool, boolean bots) {
        return new IndustrialActionContract(id("action:" + name), type, tool,
                type == IndustrialActionType.CONNECT_RESOURCE
                        ? Set.of(GenericResourceType.ELECTRICAL_ENERGY)
                        : Set.of(GenericResourceType.ITEM), true, bots,
                type == IndustrialActionType.OBSERVE_STATUS, 2, "exact live verification");
    }

    private static ImplementationPortContract port(String name, ImplementationPortRole role) {
        return new ImplementationPortContract(id("port:" + name), role,
                role.expectedResourceType(), role.expectedMode(), 1, OptionalLong.empty(),
                true, false, PortTemporalSemantics.CONTINUOUS, Set.of(),
                Set.of(VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE));
    }

    private static ElectricalNetworkGraph network(long generation, long stored) {
        ElectricalNetworkNode source = new ElectricalNetworkNode(id("node:generator"),
                ElectricalNodeKind.GENERATOR, new BlockPos3i(0, 0, 0), VoltageTier.LV,
                Optional.empty(), Set.of(), Set.of(Direction6.EAST), generation, 0,
                stored, stored, "b".repeat(64), true);
        ElectricalNetworkNode consumer = new ElectricalNetworkNode(id("node:consumer"),
                ElectricalNodeKind.CONSUMER, new BlockPos3i(1, 0, 0), VoltageTier.LV,
                Optional.empty(), Set.of(Direction6.WEST), Set.of(), 0, 1,
                0, 0, "c".repeat(64), true);
        ElectricalWireEdge edge = new ElectricalWireEdge(id("edge:power"), source.nodeId(),
                consumer.nodeId(), VoltageTier.LV, 1, 16, Math.max(1, generation),
                List.of(source.position(), consumer.position()), "d".repeat(64),
                true, true, true);
        return new ElectricalNetworkGraph(id("network:metal_press"), "world",
                id("minecraft:overworld"), List.of(source, consumer), List.of(edge), 0);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
