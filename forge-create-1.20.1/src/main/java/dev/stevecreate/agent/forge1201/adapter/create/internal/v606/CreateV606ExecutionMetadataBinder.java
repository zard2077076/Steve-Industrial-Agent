package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.ResolvedRuntimeRecipe;
import dev.stevecreate.agent.adapter.api.RuntimeVerifiedPlanningResult;
import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeHeatRequirement;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verifies the additive recipe-metadata sidecar against binding and physicalization identities. */
final class CreateV606ExecutionMetadataBinder {
    private static final ResourceId MIXING = id("create:mixing");
    private static final ResourceId COMPACTING = id("create:compacting");
    private static final ResourceId BLAZE_BURNER = id("create:blaze_burner");
    private static final ResourceId ORDINARY_FUEL = id("minecraft:coal");

    BindResult bind(
            RuntimeVerifiedPlanningResult planning,
            VerifiedImplementationBoundPlan bound,
            VerifiedPhysicalPlan physical,
            ResourceId sessionId) {
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(sessionId, "sessionId");
        if (!planning.verifiedPlan().equals(bound.graph().logicalPlan())
                || !physical.candidate().boundPlan().equals(bound)) {
            return failure("Heat metadata was not bound to the same verified planning chain");
        }
        Map<ResourceId, ResolvedRuntimeRecipe> resolvedByRecipe = new LinkedHashMap<>();
        for (ResolvedRuntimeRecipe resolved : planning.resolvedRecipes().resolutions()) {
            resolvedByRecipe.put(resolved.resolvedRecipe().recipeId(), resolved);
        }
        Map<ResourceId, PhysicalMachinePlacement> placementByNode = new LinkedHashMap<>();
        for (PhysicalMachinePlacement placement : physical.placements()) {
            placementByNode.put(placement.logicalNodeId(), placement);
        }
        Map<ResourceId, RecipeHeatRequirement> heatByStep = new LinkedHashMap<>();
        Map<ResourceId, List<ProcessResource>> fluidsByStep = new LinkedHashMap<>();
        List<CreateV606VerifiedExecutionMetadata.FuelReservationRequirement> fuel =
                new ArrayList<>();
        List<String> trace = new ArrayList<>();
        for (BoundMachineNode node : bound.graph().boundProcessNodes().values()) {
            ResolvedRuntimeRecipe resolved = resolvedByRecipe.get(node.recipeId());
            if (resolved == null) {
                return failure("Bound node lacks resolved runtime metadata: " + node.recipeId());
            }
            RecipeHeatRequirement requirement = resolved.runtimeEntry().heatRequirement();
            if (!requirement.sourceRecipeId().equals(node.recipeId())
                    || !requirement.sourceCapabilityId().equals(node.recipeType())) {
                return failure("Heat metadata provenance differs from the bound node");
            }
            if (!resolved.runtimeEntry().source().runtimeFingerprint()
                    .equals(planning.runtimeFingerprint())) {
                return failure("Heat metadata belongs to a stale runtime fingerprint");
            }
            List<ProcessResource> fluidInputs = resolved.runtimeEntry().fluidInputs();
            if (!fluidInputs.isEmpty()) {
                boolean exactMixer = node.recipeType().equals(MIXING)
                        && node.implementationId().equals(
                                CreateRuntimeMachineImplementationCatalog
                                        .BASIN_MIXER_IMPLEMENTATION_ID);
                boolean exactPress = node.recipeType().equals(COMPACTING)
                        && node.implementationId().equals(
                                CreateRuntimeMachineImplementationCatalog
                                        .BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID);
                if ((!exactMixer && !exactPress)
                        || node.quantityConversion().executions() != 1
                        || fluidInputs.stream().anyMatch(value ->
                                value.resourceType()
                                        != dev.stevecreate.agent.core.resource.GenericResourceType.FLUID)) {
                    return failure(
                            "Fluid input is limited to one exact verified Basin/Mixer or Basin/Press execution");
                }
                fluidsByStep.put(node.stepId(), List.copyOf(fluidInputs));
            }
            if (requirement.heatTier() == RecipeHeatTier.SUPERHEATED
                    || requirement.heatTier()
                            == RecipeHeatTier.UNSUPPORTED_HEAT_REQUIREMENT) {
                return failure("Unsupported runtime heat tier: " + requirement.heatTier());
            }
            PhysicalMachinePlacement placement = placementByNode.get(node.logicalNodeId());
            if (placement == null) {
                return failure("Heat metadata has no matching verified physical placement");
            }
            if (requirement.heatTier() == RecipeHeatTier.HEATED) {
                if (!node.recipeType().equals(MIXING)
                        || !node.implementationId().equals(
                                CreateRuntimeMachineImplementationCatalog
                                        .BASIN_MIXER_IMPLEMENTATION_ID)
                        || node.quantityConversion().executions() != 1
                        || placement.components().stream().noneMatch(component ->
                                component.blockId().equals(BLAZE_BURNER))) {
                    return failure(
                            "HEATED is limited to one exact verified Basin/Mixer execution with an owned Blaze Burner");
                }
                ProcessResource exactFuel = requirement.fuelPerExecution().orElse(null);
                if (exactFuel == null
                        || !exactFuel.resourceId().equals(ORDINARY_FUEL)
                        || exactFuel.amount() != 1) {
                    return failure("HEATED mixing requires exactly one ordinary coal fuel");
                }
                fuel.add(new CreateV606VerifiedExecutionMetadata.FuelReservationRequirement(
                        child(sessionId, "fuel/" + node.stepId().path()),
                        sessionId,
                        node.stepId(),
                        exactFuel,
                        ConstructionTask.MAXIMUM_TASK_TICKS,
                        true,
                        CreateV606VerifiedExecutionMetadata.ReloadBehavior
                                .RELEASE_AND_REVERIFY_BEFORE_RESOURCE_USE));
            } else if (requirement.fuelPerExecution().isPresent()) {
                return failure("NONE heat cannot carry a fuel reservation");
            }
            if (heatByStep.put(node.stepId(), requirement) != null) {
                return failure("Duplicate heat metadata for step " + node.stepId());
            }
            trace.add("step=" + node.stepId()
                    + ":recipe=" + node.recipeId()
                    + ":heat=" + requirement.heatTier()
                    + ":fluids=" + fluidInputs
                    + ":fuel=" + requirement.fuelPerExecution()
                            .map(value -> value.resourceId() + "@" + value.amount())
                            .orElse("none"));
        }
        if (heatByStep.size() != bound.graph().boundProcessNodes().size()) {
            return failure("Heat metadata did not exactly cover every bound process node");
        }
        return new Bound(new CreateV606VerifiedExecutionMetadata(
                sessionId,
                planning.runtimeFingerprint(),
                heatByStep,
                fluidsByStep,
                fuel,
                trace));
    }

    sealed interface BindResult permits Bound, Failure {}

    record Bound(CreateV606VerifiedExecutionMetadata metadata) implements BindResult {
        Bound {
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    record Failure(String detail) implements BindResult {
        Failure {
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail is blank");
        }
    }

    private static Failure failure(String detail) {
        return new Failure(detail);
    }

    private static ResourceId child(ResourceId parent, String suffix) {
        return new ResourceId(parent.namespace(), parent.path() + "/" + suffix);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
