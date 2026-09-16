package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Minimal immutable material projection that can only originate from a verified physical plan. */
public final class VerifiedPlanMaterialSnapshot {
    private final ResourceId physicalPlanId;
    private final List<ResolvedGeometryComponent> components;
    private final List<BlockPos3i> itemRoutePositions;
    private final List<BlockPos3i> powerRoutePositions;

    private VerifiedPlanMaterialSnapshot(
            ResourceId physicalPlanId,
            List<ResolvedGeometryComponent> components,
            List<BlockPos3i> itemRoutePositions,
            List<BlockPos3i> powerRoutePositions) {
        this.physicalPlanId = Objects.requireNonNull(physicalPlanId, "physicalPlanId");
        this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        this.itemRoutePositions = positions(itemRoutePositions);
        this.powerRoutePositions = positions(powerRoutePositions);
        if (this.components.isEmpty()) {
            throw new IllegalArgumentException("verified material snapshot has no components");
        }
    }

    public static VerifiedPlanMaterialSnapshot from(VerifiedPhysicalPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<ResolvedGeometryComponent> components = plan.placements().stream()
                .flatMap(value -> value.components().stream())
                .sorted(Comparator.comparing(ResolvedGeometryComponent::position,
                        Comparator.comparingInt(BlockPos3i::x)
                                .thenComparingInt(BlockPos3i::y)
                                .thenComparingInt(BlockPos3i::z)))
                .toList();
        java.util.Set<BlockPos3i> componentPositions = components.stream()
                .map(ResolvedGeometryComponent::position)
                .collect(java.util.stream.Collectors.toSet());
        List<BlockPos3i> itemRoutes = plan.routes().stream()
                // Route endpoints are ports, not blocks built by the route executor.
                // Adjacent ports have no interior and must not demand phantom material.
                .flatMap(value -> value.interiorPositions().stream())
                .filter(position -> !componentPositions.contains(position)).toList();
        List<BlockPos3i> powerRoutes = plan.placements().stream()
                .flatMap(value -> value.rotationalPowerRoute().stream())
                .filter(position -> !componentPositions.contains(position)).toList();
        return new VerifiedPlanMaterialSnapshot(plan.id(), components, itemRoutes, powerRoutes);
    }

    /**
     * Projects the already verified stage snapshots of one admitted composite graph
     * into a single reservation authority.  Each stage keeps its own verified
     * physical plan; this merge exists only so one player order can reserve every
     * stage and route material before any handler runs.  Two stages may never claim
     * the same cell, so a composite cannot silently reuse or double-count another
     * stage's machine component, and the explicit route positions must stay clear of
     * every component cell.
     */
    public static VerifiedPlanMaterialSnapshot merge(
            ResourceId compositePlanId,
            List<VerifiedPlanMaterialSnapshot> stages,
            List<BlockPos3i> routePositions) {
        Objects.requireNonNull(compositePlanId, "compositePlanId");
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(routePositions, "routePositions");
        // One is enough to merge. The bound read "at least two" because a merge of one
        // looked pointless, but the merge does two things and only the first needs a
        // second stage: it checks that no two stages claim the same cell, and it re-ids
        // the result under the composite plan so one order reserves the whole site. A
        // single-stage composite still needs the second, and refusing it here was the
        // last thing keeping eighty-seven single-machine products off the production
        // path — every overlap check below still runs unchanged.
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("composite material snapshot needs a stage");
        }
        LinkedHashSet<BlockPos3i> claimed = new LinkedHashSet<>();
        List<ResolvedGeometryComponent> components = new ArrayList<>();
        List<BlockPos3i> itemRoutes = new ArrayList<>();
        List<BlockPos3i> powerRoutes = new ArrayList<>();
        for (VerifiedPlanMaterialSnapshot stage : stages) {
            Objects.requireNonNull(stage, "composite stage snapshot");
            for (ResolvedGeometryComponent component : stage.components()) {
                if (!claimed.add(component.position())) {
                    throw new IllegalArgumentException(
                            "composite stages claim the same verified cell " + component.position());
                }
                components.add(component);
            }
            itemRoutes.addAll(stage.itemRoutePositions());
            powerRoutes.addAll(stage.powerRoutePositions());
        }
        for (BlockPos3i position : routePositions) {
            Objects.requireNonNull(position, "composite route position");
            if (claimed.contains(position)) {
                throw new IllegalArgumentException(
                        "composite route position overlaps a verified component cell " + position);
            }
            itemRoutes.add(position);
        }
        components.sort(Comparator.comparing(ResolvedGeometryComponent::position,
                Comparator.comparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z)));
        return new VerifiedPlanMaterialSnapshot(
                compositePlanId, components, itemRoutes, powerRoutes);
    }

    static VerifiedPlanMaterialSnapshot fixture(
            ResourceId physicalPlanId,
            List<ResolvedGeometryComponent> components,
            List<BlockPos3i> itemRoutePositions,
            List<BlockPos3i> powerRoutePositions) {
        return new VerifiedPlanMaterialSnapshot(
                physicalPlanId, components, itemRoutePositions, powerRoutePositions);
    }

    public ResourceId physicalPlanId() { return physicalPlanId; }
    public List<ResolvedGeometryComponent> components() { return components; }
    public List<BlockPos3i> itemRoutePositions() { return itemRoutePositions; }
    public List<BlockPos3i> powerRoutePositions() { return powerRoutePositions; }

    private static List<BlockPos3i> positions(List<BlockPos3i> values) {
        Objects.requireNonNull(values, "positions");
        if (values.size() > ProjectMaterialLine.MAX_BOUND_POSITIONS) {
            throw new IllegalArgumentException("verified material route exceeds its bound");
        }
        return List.copyOf(new ArrayList<>(new LinkedHashSet<>(values)));
    }
}
