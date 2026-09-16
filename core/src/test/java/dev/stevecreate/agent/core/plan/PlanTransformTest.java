package dev.stevecreate.agent.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.graph.EdgeMode;
import dev.stevecreate.agent.core.graph.MachineAxis;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanTransformTest {
    private static final BlockPos3i ANCHOR_POSITION = new BlockPos3i(100, 64, -20);

    @Test
    void typedAnchorResolvesAllFourRotationsAndRejectsOverflow() {
        BlockPos3i anchor = new BlockPos3i(10, 64, -5);
        BlockPos3i relative = new BlockPos3i(2, 3, -1);

        assertThat(transform(anchor, QuarterTurn.ZERO).resolve(relative))
                .isEqualTo(new BlockPos3i(12, 67, -6));
        assertThat(transform(anchor, QuarterTurn.CLOCKWISE_90).resolve(relative))
                .isEqualTo(new BlockPos3i(11, 67, -3));
        assertThat(transform(anchor, QuarterTurn.CLOCKWISE_180).resolve(relative))
                .isEqualTo(new BlockPos3i(8, 67, -4));
        assertThat(transform(anchor, QuarterTurn.CLOCKWISE_270).resolve(relative))
                .isEqualTo(new BlockPos3i(9, 67, -7));
        assertThat(PlanAnchor.at(anchor).rotation()).isEqualTo(QuarterTurn.ZERO);
        assertThatThrownBy(() -> transform(
                        new BlockPos3i(Integer.MAX_VALUE, 0, 0), QuarterTurn.ZERO)
                .resolve(new BlockPos3i(1, 0, 0)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rotatesDirectionsAxesFacingsAndMachineOrientationTogether() {
        PlanTransform clockwise = transform(new BlockPos3i(0, 0, 0), QuarterTurn.CLOCKWISE_90);
        PlanTransform halfTurn = transform(new BlockPos3i(0, 0, 0), QuarterTurn.CLOCKWISE_180);

        assertThat(clockwise.rotate(Direction6.NORTH)).isEqualTo(Direction6.EAST);
        assertThat(clockwise.rotate(Direction6.UP)).isEqualTo(Direction6.UP);
        assertThat(clockwise.rotate(MachineAxis.X)).isEqualTo(MachineAxis.Z);
        assertThat(clockwise.rotate(MachineAxis.Z)).isEqualTo(MachineAxis.X);
        assertThat(clockwise.rotate(MachineAxis.Y)).isEqualTo(MachineAxis.Y);
        assertThat(halfTurn.rotate(MachineAxis.X)).isEqualTo(MachineAxis.X);
        assertThat(clockwise.rotate(PlanBlockAxis.X)).isEqualTo(PlanBlockAxis.Z);
        assertThat(clockwise.rotate(PlanBlockAxis.NONE)).isEqualTo(PlanBlockAxis.NONE);
        assertThat(clockwise.rotate(PlanBlockFacing.NORTH)).isEqualTo(PlanBlockFacing.EAST);
        assertThat(clockwise.rotate(PlanBlockFacing.UP)).isEqualTo(PlanBlockFacing.UP);
        assertThat(clockwise.rotate(MachineOrientation.of(Direction6.NORTH, MachineAxis.X)))
                .isEqualTo(MachineOrientation.of(Direction6.EAST, MachineAxis.Z));
    }

    @Test
    void rotatesGraphGeometryOrientationAndPortSidesWhilePreservingEveryEdgeAndIdentity() {
        ResourceId sourceNodeId = id("test:node/source");
        ResourceId targetNodeId = id("test:node/target");
        ResourceId sourcePortId = id("test:port/source");
        ResourceId targetPortId = id("test:port/target");
        MachineNode source = new MachineNode(
                sourceNodeId,
                id("test:role/source"),
                id("test:machine/source"),
                new BlockPos3i(2, 5, -3),
                MachineOrientation.of(Direction6.NORTH, MachineAxis.X),
                Set.of(id("test:capability/source")),
                Map.of("state", "source"));
        MachineNode target = new MachineNode(
                targetNodeId,
                id("test:role/target"),
                id("test:machine/target"),
                new BlockPos3i(-1, 5, 4),
                MachineOrientation.NONE,
                Set.of(id("test:capability/target")),
                Map.of("state", "target"));
        MachinePort sourcePort = new MachinePort(
                sourcePortId,
                sourceNodeId,
                GenericResourceType.ITEM,
                PortMode.OUTPUT,
                Optional.of(Direction6.EAST),
                OptionalLong.of(8),
                Map.of("filter", "test:item"));
        MachinePort targetPort = new MachinePort(
                targetPortId,
                targetNodeId,
                GenericResourceType.ITEM,
                PortMode.INPUT,
                Optional.of(Direction6.NORTH),
                OptionalLong.of(8),
                Map.of("filter", "test:item"));
        MachineEdge edge = new MachineEdge(
                id("test:edge/source_to_target"),
                sourcePortId,
                targetPortId,
                GenericResourceType.ITEM,
                EdgeMode.DIRECTED,
                OptionalLong.of(4),
                Map.of("channel", "main"));
        UnifiedMachineGraph original = new UnifiedMachineGraph(
                id("test:graph/line"),
                List.of(source, target),
                List.of(sourcePort, targetPort),
                List.of(edge));

        UnifiedMachineGraph rotated = transform(
                new BlockPos3i(500, 80, 500), QuarterTurn.CLOCKWISE_90).rotate(original);

        assertThat(rotated.id()).isEqualTo(original.id());
        assertThat(rotated.nodes().keySet()).containsExactlyElementsOf(original.nodes().keySet());
        assertThat(rotated.ports().keySet()).containsExactlyElementsOf(original.ports().keySet());
        assertThat(rotated.edges()).isEqualTo(original.edges());
        assertThat(rotated.node(sourceNodeId).relativePosition())
                .isEqualTo(new BlockPos3i(3, 5, 2));
        assertThat(rotated.node(sourceNodeId).orientation())
                .isEqualTo(MachineOrientation.of(Direction6.EAST, MachineAxis.Z));
        assertThat(rotated.node(targetNodeId).relativePosition())
                .isEqualTo(new BlockPos3i(-4, 5, -1));
        assertThat(rotated.port(sourcePortId).side()).contains(Direction6.SOUTH);
        assertThat(rotated.port(targetPortId).side()).contains(Direction6.EAST);
        assertThat(original.node(sourceNodeId)).isEqualTo(source);
        assertThat(original.port(sourcePortId)).isEqualTo(sourcePort);
    }

    @Test
    void rotatesEveryC03PlanStateAndItsGenericGraphForAllFourTurns() {
        WaterWheelMillstonePlan defaultPlan = WaterWheelMillstonePlan.at(ANCHOR_POSITION);
        UnifiedMachineGraph defaultGraph =
                WaterWheelMillstoneGenericExecutionPlan.from(defaultPlan).machineGraph();

        for (QuarterTurn turn : QuarterTurn.values()) {
            PlanTransform transform = transform(ANCHOR_POSITION, turn);
            WaterWheelMillstonePlan rotated = WaterWheelMillstonePlan.at(transform.anchor());

            assertThat(rotated.anchor()).isEqualTo(transform.anchor());
            assertThat(rotated.placements()).hasSize(defaultPlan.placements().size());
            assertThat(rotated.preflightPositions()).hasSize(175).doesNotHaveDuplicates();
            for (WaterWheelMillstoneRole role : WaterWheelMillstoneRole.values()) {
                ResolvedPlanPlacement before = defaultPlan.placement(role);
                ResolvedPlanPlacement after = rotated.placement(role);
                assertThat(after.position()).isEqualTo(transform.resolve(relative(before.position())));
                assertThat(after.axis()).isEqualTo(transform.rotate(before.axis()));
            }
            assertGraphEquals(
                    transform.rotate(defaultGraph),
                    WaterWheelMillstoneGenericExecutionPlan.from(rotated).machineGraph());
        }
    }

    @Test
    void rotatesEveryC04PositionStateBeltEndpointAndGenericGraphForAllFourTurns() {
        BeltPressPlan defaultPlan = BeltPressPlan.at(ANCHOR_POSITION);
        UnifiedMachineGraph defaultGraph =
                BeltPressGenericExecutionPlan.from(defaultPlan).machineGraph();

        for (QuarterTurn turn : QuarterTurn.values()) {
            PlanTransform transform = transform(ANCHOR_POSITION, turn);
            BeltPressPlan rotated = BeltPressPlan.at(transform.anchor());

            assertThat(rotated.anchor()).isEqualTo(transform.anchor());
            assertThat(rotated.finalPlacements()).hasSize(defaultPlan.finalPlacements().size());
            assertThat(rotated.preflightPositions()).hasSize(336).doesNotHaveDuplicates();
            for (BeltPressRole role : BeltPressRole.values()) {
                BeltPressPlacement before = defaultPlan.placement(role);
                BeltPressPlacement after = rotated.placement(role);
                assertThat(after.position()).isEqualTo(transform.resolve(relative(before.position())));
                assertThat(after.rotationAxis()).isEqualTo(transform.rotate(before.rotationAxis()));
                assertThat(after.facing()).isEqualTo(transform.rotate(before.facing()));
            }
            for (int index = 0; index < defaultPlan.buildSteps().size() - 1; index++) {
                BeltPressBuildStep.PlaceBlock before =
                        (BeltPressBuildStep.PlaceBlock) defaultPlan.buildSteps().get(index);
                BeltPressBuildStep.PlaceBlock after =
                        (BeltPressBuildStep.PlaceBlock) rotated.buildSteps().get(index);
                assertThat(after.position()).isEqualTo(transform.resolve(relative(before.position())));
                assertThat(after.rotationAxis()).isEqualTo(transform.rotate(before.rotationAxis()));
                assertThat(after.facing()).isEqualTo(transform.rotate(before.facing()));
            }
            assertThat(rotated.beltConnection().startPosition())
                    .isEqualTo(transform.resolve(relative(defaultPlan.beltConnection().startPosition())));
            assertThat(rotated.beltConnection().endPosition())
                    .isEqualTo(transform.resolve(relative(defaultPlan.beltConnection().endPosition())));
            assertGraphEquals(
                    transform.rotate(defaultGraph),
                    BeltPressGenericExecutionPlan.from(rotated).machineGraph());
        }
    }

    private static void assertGraphEquals(UnifiedMachineGraph expected, UnifiedMachineGraph actual) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.nodes()).isEqualTo(expected.nodes());
        assertThat(actual.ports()).isEqualTo(expected.ports());
        assertThat(actual.edges()).isEqualTo(expected.edges());
    }

    private static BlockPos3i relative(BlockPos3i absolute) {
        return new BlockPos3i(
                Math.subtractExact(absolute.x(), ANCHOR_POSITION.x()),
                Math.subtractExact(absolute.y(), ANCHOR_POSITION.y()),
                Math.subtractExact(absolute.z(), ANCHOR_POSITION.z()));
    }

    private static PlanTransform transform(BlockPos3i position, QuarterTurn rotation) {
        return new PlanTransform(new PlanAnchor(position, rotation));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
