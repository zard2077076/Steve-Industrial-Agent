package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.graph.MachineAxis;
import dev.stevecreate.agent.core.graph.MachineEdge;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.graph.MachineOrientation;
import dev.stevecreate.agent.core.graph.MachinePort;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.QuarterTurn;
import java.util.List;
import java.util.Objects;

/** Loader-neutral, immutable whole-plan Y rotation around a typed anchor. */
public record PlanTransform(PlanAnchor anchor) {
    public PlanTransform {
        Objects.requireNonNull(anchor, "anchor");
    }

    /** Rotates a relative position and resolves it to the anchor's absolute position. */
    public BlockPos3i resolve(BlockPos3i relativePosition) {
        BlockPos3i rotated = rotateRelative(relativePosition);
        return anchor.position().translate(rotated.x(), rotated.y(), rotated.z());
    }

    /** Rotates a relative vector without applying the anchor translation. */
    public BlockPos3i rotateRelative(BlockPos3i relativePosition) {
        return Objects.requireNonNull(relativePosition, "relativePosition")
                .rotateY(anchor.rotation());
    }

    public Direction6 rotate(Direction6 direction) {
        return Objects.requireNonNull(direction, "direction").rotateY(anchor.rotation());
    }

    public MachineAxis rotate(MachineAxis axis) {
        Objects.requireNonNull(axis, "axis");
        if (axis == MachineAxis.Y
                || anchor.rotation() == QuarterTurn.ZERO
                || anchor.rotation() == QuarterTurn.CLOCKWISE_180) {
            return axis;
        }
        return axis == MachineAxis.X ? MachineAxis.Z : MachineAxis.X;
    }

    public PlanBlockAxis rotate(PlanBlockAxis axis) {
        Objects.requireNonNull(axis, "axis");
        if (axis == PlanBlockAxis.NONE || axis == PlanBlockAxis.Y
                || anchor.rotation() == QuarterTurn.ZERO
                || anchor.rotation() == QuarterTurn.CLOCKWISE_180) {
            return axis;
        }
        return axis == PlanBlockAxis.X ? PlanBlockAxis.Z : PlanBlockAxis.X;
    }

    public PlanBlockFacing rotate(PlanBlockFacing facing) {
        Objects.requireNonNull(facing, "facing");
        if (facing == PlanBlockFacing.NONE) {
            return facing;
        }
        return PlanBlockFacing.valueOf(rotate(Direction6.valueOf(facing.name())).name());
    }

    public MachineOrientation rotate(MachineOrientation orientation) {
        Objects.requireNonNull(orientation, "orientation");
        return new MachineOrientation(
                orientation.facing().map(this::rotate),
                orientation.axis().map(this::rotate));
    }

    /** Rotates graph-relative geometry and state while preserving every identity and edge. */
    public UnifiedMachineGraph rotate(UnifiedMachineGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<MachineNode> nodes = graph.nodes().values().stream()
                .map(node -> new MachineNode(
                        node.id(),
                        node.roleId(),
                        node.implementationId(),
                        rotateRelative(node.relativePosition()),
                        rotate(node.orientation()),
                        node.requiredCapabilities(),
                        node.configuration()))
                .toList();
        List<MachinePort> ports = graph.ports().values().stream()
                .map(port -> new MachinePort(
                        port.id(),
                        port.nodeId(),
                        port.resourceType(),
                        port.mode(),
                        port.side().map(this::rotate),
                        port.capacity(),
                        port.constraints()))
                .toList();
        List<MachineEdge> edges = List.copyOf(graph.edges().values());
        return new UnifiedMachineGraph(graph.id(), nodes, ports, edges);
    }
}
