package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Verifies fluid identity, pumps, direction, pipe collision, valves and capacity. */
public final class FluidNetworkVerifier {
    public VerificationReport verify(FluidNetworkGraph graph) {
        Objects.requireNonNull(graph, "graph");
        EnumSet<FluidNetworkFailure> failures = EnumSet.noneOf(FluidNetworkFailure.class);
        for (FluidRoute route : graph.routes().values()) {
            FluidNetworkNode from = graph.nodes().get(route.fromNodeId());
            FluidNetworkNode to = graph.nodes().get(route.toNodeId());
            if (from.contents().isPresent() && !from.contents().equals(
                    java.util.Optional.of(route.fluid()))) {
                failures.add(FluidNetworkFailure.CONTAMINATION);
            }
            if (to.contents().isPresent() && !to.contents().equals(
                    java.util.Optional.of(route.fluid()))) {
                failures.add(FluidNetworkFailure.CONTAMINATION);
            }
            if (route.requiredMb() > route.capacityMb()
                    || route.requiredMb() > to.capacityMb() - to.amountMb()) {
                failures.add(FluidNetworkFailure.CAPACITY_INSUFFICIENT);
            }
            if (route.directional() && graph.nodes().values().stream()
                    .noneMatch(value -> value.kind() == FluidNodeKind.PUMP
                            && value.pumpRateMbPerTick() >= Math.min(route.requiredMb(),
                                    route.capacityMb()))) {
                failures.add(FluidNetworkFailure.PUMP_REQUIRED);
            }
            if (from.outputFaces().isEmpty() || to.inputFaces().isEmpty()) {
                failures.add(FluidNetworkFailure.INTERFACE_DIRECTION_MISSING);
            }
            if (!route.collisionFree()) failures.add(FluidNetworkFailure.PIPE_COLLISION);
            if (!route.valveOpen()) failures.add(FluidNetworkFailure.VALVE_CLOSED);
            if (!route.endpointsConnected()) failures.add(FluidNetworkFailure.DISCONNECTED);
        }
        return new VerificationReport(graph.graphId(), List.copyOf(failures), failures.isEmpty());
    }

    public record VerificationReport(
            ResourceId graphId,
            List<FluidNetworkFailure> failures,
            boolean accepted) {
        public VerificationReport {
            Objects.requireNonNull(graphId, "graphId");
            failures = List.copyOf(failures);
            if (accepted == !failures.isEmpty()) {
                throw new IllegalArgumentException("fluid verification result is inconsistent");
            }
        }
    }
}
