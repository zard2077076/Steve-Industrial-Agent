package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Independent fail-closed verifier for a non-executable physical candidate. */
public final class PhysicalizationVerifier {
    public PhysicalizationResult verify(
            PhysicalLayoutCandidate candidate,
            ImmutableMachineGeometryCatalog geometries,
            LayoutConstraints constraints) {
        if (candidate == null || geometries == null || constraints == null) {
            return failure(LayoutFailureCode.LAYOUT_CONSTRAINTS_MISSING,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Physicalization verifier input is missing");
        }
        List<PhysicalizationVerificationEvidence> evidence = new ArrayList<>();
        Map<?, BoundMachineNode> bound = candidate.boundPlan().graph().boundProcessNodes();
        if (candidate.placements().size() != bound.size()
                || candidate.placements().stream().anyMatch(value -> {
                    BoundMachineNode node = candidate.boundPlan().graph().boundProcessNodes().get(value.logicalNodeId());
                    return node == null || !node.implementationId().equals(value.implementationId());
                })) {
            return failure(LayoutFailureCode.PHYSICALIZATION_FAILED,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Binding identity was not preserved");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.BINDING_IDENTITY_PRESERVED,
                "bound_nodes=" + bound.size()));

        String runtime = candidate.boundPlan().graph().runtimeFingerprint();
        if (!runtime.equals(candidate.snapshotFingerprint())
                || !runtime.equals(constraints.snapshot().runtimeFingerprint())
                || !runtime.equals(geometries.runtimeFingerprint())) {
            return failure(LayoutFailureCode.WORLD_SNAPSHOT_STALE,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Runtime fingerprints do not match");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.RUNTIME_SNAPSHOT_MATCHES, runtime));

        for (PhysicalMachinePlacement placement : candidate.placements()) {
            MachineGeometryDescriptor geometry = geometries.find(placement.implementationId()).orElse(null);
            if (geometry == null || !geometry.supportedOrientations().contains(placement.orientation())) {
                return failure(LayoutFailureCode.ORIENTATION_UNAVAILABLE,
                        LayoutStage.PHYSICALIZATION_VERIFICATION, "Placement orientation is unsupported");
            }
        }
        evidence.add(ok(PhysicalizationVerificationCheck.ORIENTATIONS_SUPPORTED,
                "placements=" + candidate.placements().size()));

        Set<BlockPos3i> occupied = new HashSet<>();
        for (PhysicalMachinePlacement placement : candidate.placements()) {
            for (ResolvedGeometryComponent component : placement.components()) {
                if (!occupied.add(component.position())) {
                    return failure(LayoutFailureCode.PLACEMENT_COLLISION,
                            LayoutStage.PHYSICALIZATION_VERIFICATION, "Resolved footprints overlap");
                }
                if (constraints.snapshot().state(component.position()).orElse(LayoutCellState.UNLOADED)
                        != LayoutCellState.REPLACEABLE) {
                    return failure(LayoutFailureCode.PLACEMENT_COLLISION,
                            LayoutStage.PHYSICALIZATION_VERIFICATION, "Footprint is not replaceable");
                }
            }
        }
        evidence.add(ok(PhysicalizationVerificationCheck.FOOTPRINTS_COLLISION_FREE,
                "cells=" + occupied.size()));

        for (PhysicalMachinePlacement placement : candidate.placements()) {
            Set<BlockPos3i> ownFootprint = placement.components().stream()
                    .map(ResolvedGeometryComponent::position)
                    .collect(java.util.stream.Collectors.toSet());
            for (BlockPos3i position : placement.clearance()) {
                if ((occupied.contains(position) && !ownFootprint.contains(position))
                        || (!occupied.contains(position)
                                && constraints.snapshot().state(position).orElse(LayoutCellState.UNLOADED)
                                != LayoutCellState.REPLACEABLE)) {
                    return failure(LayoutFailureCode.CLEARANCE_BLOCKED,
                            LayoutStage.PHYSICALIZATION_VERIFICATION, "Clearance cell is blocked or absent");
                }
            }
        }
        evidence.add(ok(PhysicalizationVerificationCheck.CLEARANCES_FREE, "clearance=observed"));

        for (PhysicalMachinePlacement placement : candidate.placements()) {
            BoundMachineNode node = candidate.boundPlan().graph().boundProcessNodes().get(placement.logicalNodeId());
            if (!placement.ports().keySet().equals(node.logicalToImplementationPorts().keySet())) {
                return failure(LayoutFailureCode.PHYSICAL_PORT_RESOLUTION_FAILED,
                        LayoutStage.PHYSICALIZATION_VERIFICATION, "Physical logical ports are incomplete");
            }
        }
        evidence.add(ok(PhysicalizationVerificationCheck.PHYSICAL_PORTS_COMPLETE, "ports=complete"));

        long logicalItemEdges = candidate.boundPlan().graph().logicalPlan().logicalGraph().edges().values()
                .stream().filter(value -> value.resourceType() == GenericResourceType.ITEM).count();
        long itemRoutes = candidate.routes().stream()
                .filter(value -> value.resourceType() == GenericResourceType.ITEM).count();
        if (itemRoutes != logicalItemEdges) {
            return failure(LayoutFailureCode.ITEM_ROUTE_NOT_FOUND,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Not every logical item edge has a route");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.ITEM_ROUTES_CONNECTED,
                "routes=" + itemRoutes));

        if (candidate.placements().stream().anyMatch(value -> value.rotationalPowerRoute().size() < 2)) {
            return failure(LayoutFailureCode.ROTATIONAL_POWER_ROUTE_NOT_FOUND,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Power route is incomplete");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.POWER_ROUTES_CONNECTED,
                "routes=" + candidate.placements().size()));

        if (candidate.routes().stream().anyMatch(value -> value.requiredAmount() > value.capacity())) {
            return failure(LayoutFailureCode.ROUTE_CAPACITY_INSUFFICIENT,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Item route capacity is insufficient");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.ROUTE_CAPACITY_SUFFICIENT,
                "item_capacity=" + constraints.itemRouteCapacity()));

        long stress = candidate.placements().stream().mapToLong(PhysicalMachinePlacement::stressImpact).sum();
        if (stress > constraints.stressCapacity()) {
            return failure(LayoutFailureCode.STRESS_CAPACITY_INSUFFICIENT,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Stress capacity is insufficient");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.STRESS_CAPACITY_SUFFICIENT,
                "stress=" + stress + "/" + constraints.stressCapacity()));

        if (candidate.unifiedGraph().nodes().isEmpty()
                || candidate.unifiedGraph().ports().isEmpty()
                || candidate.unifiedGraph().edges().isEmpty()) {
            return failure(LayoutFailureCode.UNIFIED_GRAPH_INVALID,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Unified graph is incomplete");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.UNIFIED_GRAPH_STRICT,
                "nodes=" + candidate.unifiedGraph().nodes().size()
                        + ",edges=" + candidate.unifiedGraph().edges().size()));

        if (candidate.candidatesExamined() > constraints.maximumCandidates()
                || candidate.searchOperations() > constraints.searchBudget()) {
            return failure(LayoutFailureCode.SEARCH_BUDGET_EXHAUSTED,
                    LayoutStage.PHYSICALIZATION_VERIFICATION, "Search exceeded declared bounds");
        }
        evidence.add(ok(PhysicalizationVerificationCheck.SEARCH_BOUNDS_RESPECTED,
                "candidates=" + candidate.candidatesExamined() + ",operations=" + candidate.searchOperations()));
        evidence.add(ok(PhysicalizationVerificationCheck.NO_EXECUTION_AUTHORITY,
                "read_only=true,mutation=false,session=false"));
        return new PhysicalizationSuccess(new VerifiedPhysicalPlan(candidate, evidence));
    }

    private static PhysicalizationVerificationEvidence ok(
            PhysicalizationVerificationCheck check, String detail) {
        return new PhysicalizationVerificationEvidence(check, detail);
    }

    private static PhysicalizationFailure failure(
            LayoutFailureCode code, LayoutStage stage, String message) {
        return new PhysicalizationFailure(new LayoutFailure(
                code, stage, Optional.empty(), Optional.empty(), message,
                Map.of("verifier", "fail_closed"), List.of("physicalization_verifier:" + code.name().toLowerCase()),
                "Regenerate the physical candidate from a current bounded snapshot"));
    }
}
