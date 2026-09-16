package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.ProjectMaterialDisposition;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLine;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanMaterialSnapshot;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlanFactory;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.CreateSurvivalPowerMappingV1;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606PhysicalItemRouteBuilder;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Rebuilds the complete material authority from the current verified physical plan. */
final class PlayerVerifiedMaterialPlanResolver {
    /**
     * Keep the Forge material gate derived from the loader-neutral reviewed matrix.
     * Wiring this directly to the reviewed matrix prevents a future plan from bypassing the
     * power gate by forgetting to update an adapter-local set. The current C-03--C-10 matrix is
     * fully survival-reviewed, but this remains fail-closed for any future unresolved entry.
     */
    private static final Set<ResourceId> CREATIVE_ONLY =
            CreateSurvivalPowerMappingV1.unresolvedPowerResources();
    // These five used to live here — belt, water, lava, fire, soul fire — as blocks with
    // no survival item form. They are now bought by name through PlacementItemBinding: a
    // full bucket, a flint and steel, a belt connector. The set stays because the policy
    // is a real one and the next block without an item will need it again.
    private static final Set<ResourceId> ACTION_MAPPED_REQUIRED = Set.of();

    private PlayerVerifiedMaterialPlanResolver() {}

    static Resolution resolve(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            SitePreparationCommand.PreparedExecutionContext prepared) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(prepared, "prepared");
        PilotDeploymentCommand.TargetSpec target = PilotDeploymentCommand.TargetSpec.supported(
                player.serverLevel(), project.target(), project.quantity());
        if (target == null) return Refused.of("TARGET_NOT_SUPPORTED");
        ResourceId sessionId = ResourceId.parse("steve_industrial:player_material_plan/"
                + project.projectId().toString().replace("-", ""));
        CreateV606GoalDrivenPlanner.PlanningResult planning = CreateV606GoalDrivenPlanner.plan(
                player.serverLevel(), project.target(), project.quantity(), target.inputs(),
                project.anchor(), project.orientation(), sessionId, target.inputs(),
                ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                target.materialConstraints());
        if (!(planning instanceof CreateV606GoalDrivenPlanner.Ready ready)) {
            var failure = (CreateV606GoalDrivenPlanner.Failure) planning;
            return new Refused("MATERIAL_PHYSICAL_PLAN_REFUSED:" + failure.code(),
                    failure.detail(), List.of());
        }
        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.from(
                ready.executionReadyPlan().physicalPlan());
        // Item routes are priced now instead of refused.
        //
        // This turned away every plan containing a route, which is most machines worth
        // building — create:cogwheel among them, and three attempts in a real session
        // died here. The refusal read as a missing capability and was not one: the
        // executor has always known what a route is made of, and only the layer that pays
        // for it did not. One definition, in PhysicalRoute and the route builder, read by
        // both.
        Map<ResourceId, Long> routeMaterials = Map.of();
        int routeCells = Create606PhysicalItemRouteBuilder.interiorCellCount(
                ready.executionReadyPlan().physicalPlan().routes());
        if (routeCells > 0) {
            routeMaterials = Map.of(
                    Create606PhysicalItemRouteBuilder.routeBlock(), (long) routeCells);
        }
        if (!snapshot.powerRoutePositions().isEmpty()) {
            return Refused.of("POWER_ROUTE_MATERIAL_MAPPING_REQUIRED");
        }
        LinkedHashMap<ResourceId, Long> fuels = new LinkedHashMap<>();
        ready.executionMetadata().fuelReservations().forEach(value -> fuels.merge(
                value.fuel().resourceId(), value.fuel().amount(), Math::addExact));
        VerifiedProjectMaterialPlan materialPlan;
        try {
            materialPlan = new VerifiedProjectMaterialPlanFactory().create(
                    new VerifiedProjectMaterialPlanFactory.Request(
                            ResourceId.parse("player_project:"
                                    + project.projectId().toString().replace("-", "")),
                            project.target(), project.quantity(), ready.runtimeRecipeFingerprint(),
                            snapshot, target.inputs(), routeMaterials, Map.of(), Map.of(), fuels,
                            Map.of(), Set.of()));
        } catch (IllegalArgumentException failure) {
            return new Refused("COMPLETE_MATERIAL_PLAN_REFUSED", failure.getMessage(), List.of());
        }
        List<Blocker> blockers = materialPlan.lines().stream()
                .filter(value -> value.disposition() == ProjectMaterialDisposition.INSTALL)
                .map(PlayerVerifiedMaterialPlanResolver::blocker)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(value -> value.resourceId().toString()))
                .toList();
        if (!blockers.isEmpty()) {
            return new Refused("SURVIVAL_MATERIAL_BINDING_REQUIRED",
                    blockers.stream().map(value -> value.resourceId() + "=" + value.reason())
                            .collect(java.util.stream.Collectors.joining(",")), blockers);
        }
        for (ProjectMaterialLine line : materialPlan.lines()) {
            if (line.disposition() != ProjectMaterialDisposition.INSTALL
                    && exactItem(line.resourceId()) == null) {
                return new Refused("EXACT_ITEM_IDENTITY_REQUIRED", line.resourceId().toString(),
                        List.of(new Blocker(line.resourceId(), "tag_or_non_item_identity")));
            }
        }
        return new Ready(ready, materialPlan);
    }

    private static Blocker blocker(ProjectMaterialLine line) {
        ResourceId resource = line.resourceId();
        String knownReason = knownUnsupportedReason(resource);
        if (knownReason != null) return new Blocker(resource, knownReason);
        if (exactItem(resource) == null) {
            return new Blocker(resource, "no_exact_survival_item_form");
        }
        return null;
    }

    /** Pure, registry-independent part of the survival construction policy. */
    static String knownUnsupportedReason(ResourceId resource) {
        Objects.requireNonNull(resource, "resource");
        if (CREATIVE_ONLY.contains(resource)) return "creative_only_power_source";
        if (ACTION_MAPPED_REQUIRED.contains(resource)) {
            return "adapter_action_material_mapping_required";
        }
        return null;
    }

    static Set<ResourceId> creativeOnlyResources() {
        return CREATIVE_ONLY;
    }

    private static net.minecraft.world.item.Item exactItem(ResourceId resource) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                resource.namespace(), resource.path());
        net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == Items.AIR ? null : item;
    }

    sealed interface Resolution permits Ready, Refused {}

    record Ready(
            CreateV606GoalDrivenPlanner.Ready execution,
            VerifiedProjectMaterialPlan materialPlan) implements Resolution {
        Ready {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(materialPlan, "materialPlan");
        }
    }

    record Refused(String code, String detail, List<Blocker> blockers) implements Resolution {
        Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
            if (code.isBlank()) throw new IllegalArgumentException("material refusal code is blank");
        }
        static Refused of(String code) { return new Refused(code, code, List.of()); }
    }

    record Blocker(ResourceId resourceId, String reason) {
        Blocker {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
