package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.construction.CapabilityExecutionDescriptor;
import dev.stevecreate.agent.core.execution.construction.CleanupPolicy;
import dev.stevecreate.agent.core.execution.construction.CleanupScope;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskClass;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.RecoveryPolicy;
import dev.stevecreate.agent.core.execution.construction.TaskConditionKind;
import dev.stevecreate.agent.core.execution.construction.TaskDependency;
import dev.stevecreate.agent.core.execution.construction.TaskDependencyKind;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.execution.construction.TaskPrecondition;
import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanTaskSource;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.PhysicalRoute;
import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.layout.ResolvedPhysicalPort;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates only plan-derived Executor Contract v1 descriptors/tasks.
 * It performs no placement, interaction, navigation or reservation mutation.
 */
public final class CreateCapabilityConstructionTaskGraphFactory {
    private static final Set<ExecutionMode> ALLOWED_MODES =
            EnumSet.of(ExecutionMode.DIRECT, ExecutionMode.HYBRID);

    public ConstructionTaskGraph create(CreateCapabilityTaskGraphRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.semantics().support() != CapabilityRecipeSupport.SUPPORTED_PHASE_I) {
            throw new IllegalArgumentException("Unsupported or semantics-only recipes cannot become executable tasks");
        }
        CapabilityExecutionDescriptor descriptor = CreateCapabilityExecutionDescriptors.forRecipe(
                request.semantics(), request.metadata());
        PhysicalMachinePlacement placement = request.verifiedPhysicalPlan().placements().stream()
                .filter(value -> value.logicalNodeId().equals(request.logicalNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified physical plan does not contain the requested logical node"));
        if (!placement.implementationId().equals(request.metadata().metadataId())) {
            throw new IllegalArgumentException(
                    "Verified placement implementation does not match capability metadata");
        }

        List<ResolvedGeometryComponent> components = placement.components().stream()
                .sorted(Comparator.comparing(value -> value.roleId().toString())).toList();
        if (components.isEmpty()) {
            throw new IllegalArgumentException("Verified capability placement contains no components");
        }
        Set<ResourceId> componentRoles = components.stream()
                .map(ResolvedGeometryComponent::roleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        requireExactKeys(
                request.componentPlacementReservationIds(), componentRoles,
                "component placement reservations");
        requireExactKeys(
                request.componentMaterialReservationIds(), componentRoles,
                "component material reservations");

        ResolvedPhysicalPort inputPort = itemPort(placement, true);
        ResolvedPhysicalPort outputPort = itemPort(placement, false);
        Set<ResourceId> placementPortIds = placement.ports().values().stream()
                .map(ResolvedPhysicalPort::logicalPortId)
                .collect(java.util.stream.Collectors.toSet());
        List<PhysicalRoute> routes = request.verifiedPhysicalPlan().routes().stream()
                .filter(value -> placementPortIds.contains(value.sourceLogicalPortId())
                        || placementPortIds.contains(value.targetLogicalPortId()))
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
        Set<ResourceId> routeIds = routes.stream().map(PhysicalRoute::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        requireExactKeys(request.routePlacementReservationIds(), routeIds,
                "route placement reservations");

        List<ConstructionTask> tasks = new ArrayList<>();
        List<TaskDependency> dependencies = new ArrayList<>();
        List<ConstructionTask> placed = new ArrayList<>();
        int ordinal = 0;
        for (ResolvedGeometryComponent component : components) {
            ConstructionTask fetch = componentFetch(request, descriptor, component, ordinal++);
            ConstructionTask place = componentPlace(request, descriptor, component, ordinal++);
            tasks.add(fetch);
            tasks.add(place);
            placed.add(place);
            dependencies.add(dependency(
                    fetch, place, TaskDependencyKind.MATERIAL_DELIVERY));
        }

        List<ConstructionTask> connected = new ArrayList<>();
        for (PhysicalRoute route : routes) {
            ConstructionTask connect = routeConnect(request, descriptor, route, ordinal++);
            tasks.add(connect);
            connected.add(connect);
            for (ConstructionTask place : placed) {
                dependencies.add(dependency(
                        place, connect, TaskDependencyKind.FINISH_TO_START));
            }
        }

        ConstructionTask verify = verifyMachine(request, descriptor, placement, ordinal++);
        tasks.add(verify);
        for (ConstructionTask predecessor : connected.isEmpty() ? placed : connected) {
            dependencies.add(dependency(
                    predecessor, verify, TaskDependencyKind.VERIFICATION_GATE));
        }

        ConstructionTask deliver = deliverProcessInput(
                request, descriptor, inputPort, ordinal++);
        ConstructionTask interact = interact(
                request, descriptor, inputPort, ordinal++);
        ConstructionTask output = verifyOutput(
                request, descriptor, outputPort, ordinal);
        tasks.add(deliver);
        tasks.add(interact);
        tasks.add(output);
        dependencies.add(dependency(
                verify, deliver, TaskDependencyKind.VERIFICATION_GATE));
        dependencies.add(dependency(
                deliver, interact, TaskDependencyKind.MATERIAL_DELIVERY));
        dependencies.add(dependency(
                interact, output, TaskDependencyKind.VERIFICATION_GATE));

        return new ConstructionTaskGraph(
                request.graphId(),
                request.verifiedPhysicalPlan().id(),
                request.semantics().runtimeFingerprint(),
                request.verifiedPhysicalPlan().candidate().snapshotFingerprint(),
                List.of(descriptor),
                tasks,
                dependencies);
    }

    private ConstructionTask componentFetch(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResolvedGeometryComponent component,
            int ordinal) {
        ResourceId taskId = taskId(request, "fetch_component", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PLACEMENT, component.roleId(), ordinal),
                TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                List.of(pre(taskId, "material_reserved", TaskConditionKind.MATERIAL_RESERVED,
                        component.blockId(), ExecutionEvidenceKind.MATERIAL_RESERVED,
                        "reservation", "active")),
                post(taskId, "material_delivered", TaskConditionKind.MATERIAL_DELIVERED,
                        component.blockId(), ExecutionEvidenceKind.MATERIAL_DELIVERED,
                        "block", component.blockId().toString()),
                Set.of(),
                Set.of(request.componentMaterialReservationIds().get(component.roleId())),
                1_200,
                Map.of(
                        key("physical_element_id"), component.roleId().toString(),
                        key("expected_block_id"), component.blockId().toString(),
                        key("quantity"), "1"),
                CleanupPolicy.NONE);
    }

    private ConstructionTask componentPlace(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResolvedGeometryComponent component,
            int ordinal) {
        ResourceId taskId = taskId(request, "place_component", ordinal);
        String state = component.blockState().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PLACEMENT, component.roleId(), ordinal),
                TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORIENTATION_SENSITIVE_COMPONENT,
                List.of(
                        pre(taskId, "placement_reserved", TaskConditionKind.PLACEMENT_RESERVED,
                                component.roleId(), ExecutionEvidenceKind.PRECONDITION,
                                "reservation", "active"),
                        pre(taskId, "current_state", TaskConditionKind.CURRENT_STATE_MATCHES,
                                component.roleId(), ExecutionEvidenceKind.PRECONDITION,
                                "source", "verified_physical_plan")),
                post(taskId, "component_present", TaskConditionKind.COMPONENT_PRESENT,
                        component.roleId(), ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "block", component.blockId() + (state.isBlank() ? "" : "[" + state + "]")),
                Set.of(request.componentPlacementReservationIds().get(component.roleId())),
                Set.of(request.componentMaterialReservationIds().get(component.roleId())),
                1_200,
                Map.of(
                        key("physical_element_id"), component.roleId().toString(),
                        key("expected_block_id"), component.blockId().toString(),
                        key("expected_block_state"), state.isBlank() ? "default" : state),
                reversibleCleanup(true));
    }

    private ConstructionTask routeConnect(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            PhysicalRoute route,
            int ordinal) {
        ResourceId taskId = taskId(request, "connect_route", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_ROUTE, route.id(), ordinal),
                TaskKind.CONNECT_COMPONENTS,
                ConstructionTaskClass.ORIENTATION_SENSITIVE_COMPONENT,
                List.of(pre(taskId, "placement_reserved", TaskConditionKind.PLACEMENT_RESERVED,
                        route.id(), ExecutionEvidenceKind.PRECONDITION,
                        "reservation", "active")),
                post(taskId, "connection_present", TaskConditionKind.CONNECTION_PRESENT,
                        route.id(), ExecutionEvidenceKind.CONNECTION_VERIFIED,
                        "route", route.id().toString()),
                Set.of(request.routePlacementReservationIds().get(route.id())),
                Set.of(),
                2_400,
                Map.of(
                        key("physical_element_id"), route.id().toString(),
                        key("resource_type"), route.resourceType().serializedName(),
                        key("required_amount"), Long.toString(route.requiredAmount()),
                        key("capacity"), Long.toString(route.capacity())),
                reversibleCleanup(false));
    }

    private ConstructionTask verifyMachine(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            PhysicalMachinePlacement placement,
            int ordinal) {
        ResourceId taskId = taskId(request, "verify_machine", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PLACEMENT,
                        placement.logicalNodeId(), ordinal),
                TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                List.of(),
                post(taskId, "machine_verified", TaskConditionKind.CURRENT_STATE_MATCHES,
                        placement.logicalNodeId(), ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "implementation", placement.implementationId().toString()),
                Set.of(), Set.of(),
                request.semantics().environment().runtimeObservation().maximumObservationTicks(),
                commonParameters(request, placement.logicalNodeId()),
                CleanupPolicy.NONE);
    }

    private ConstructionTask deliverProcessInput(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResolvedPhysicalPort input,
            int ordinal) {
        ResourceId taskId = taskId(request, "deliver_input", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PORT, input.logicalPortId(), ordinal),
                TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                List.of(pre(taskId, "material_reserved", TaskConditionKind.MATERIAL_RESERVED,
                        input.logicalPortId(), ExecutionEvidenceKind.MATERIAL_RESERVED,
                        "reservation", "active")),
                post(taskId, "input_delivered", TaskConditionKind.MATERIAL_DELIVERED,
                        input.logicalPortId(), ExecutionEvidenceKind.MATERIAL_DELIVERED,
                        "port", input.logicalPortId().toString()),
                Set.of(), Set.of(request.processMaterialReservationId()),
                2_400,
                commonParameters(request, input.logicalPortId()),
                CleanupPolicy.NONE);
    }

    private ConstructionTask interact(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResolvedPhysicalPort input,
            int ordinal) {
        ResourceId taskId = taskId(request, "safe_machine_interaction", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PORT, input.logicalPortId(), ordinal),
                TaskKind.SAFE_MACHINE_INTERACTION,
                ConstructionTaskClass.CREATE_MACHINE,
                List.of(
                        pre(taskId, "chunk_loaded", TaskConditionKind.CHUNK_LOADED,
                                input.logicalPortId(), ExecutionEvidenceKind.PRECONDITION,
                                "source", "verified_physical_plan"),
                        pre(taskId, "safe_machine_face", TaskConditionKind.SAFE_MACHINE_FACE,
                                input.logicalPortId(), ExecutionEvidenceKind.PRECONDITION,
                                "item_processing_only", "true")),
                post(taskId, "interaction_verified", TaskConditionKind.CURRENT_STATE_MATCHES,
                        input.logicalPortId(), ExecutionEvidenceKind.MACHINE_INTERACTION_VERIFIED,
                        "recipe", request.semantics().recipeId().toString()),
                Set.of(request.machineInteractionPlacementReservationId()), Set.of(),
                request.semantics().environment().runtimeObservation().maximumObservationTicks(),
                commonParameters(request, input.logicalPortId()),
                CleanupPolicy.NONE);
    }

    private ConstructionTask verifyOutput(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResolvedPhysicalPort output,
            int ordinal) {
        ResourceId taskId = taskId(request, "verify_output", ordinal);
        return task(
                request,
                descriptor,
                taskId,
                source(request, TaskSourceKind.VERIFIED_PORT, output.logicalPortId(), ordinal),
                TaskKind.VERIFY_OUTPUT,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                List.of(),
                post(taskId, "output_verified", TaskConditionKind.OUTPUT_PRESENT,
                        output.logicalPortId(), ExecutionEvidenceKind.OUTPUT_VERIFIED,
                        "recipe", request.semantics().recipeId().toString()),
                Set.of(), Set.of(),
                request.semantics().environment().runtimeObservation().maximumObservationTicks(),
                commonParameters(request, output.logicalPortId()),
                CleanupPolicy.NONE);
    }

    private ConstructionTask task(
            CreateCapabilityTaskGraphRequest request,
            CapabilityExecutionDescriptor descriptor,
            ResourceId taskId,
            VerifiedPlanTaskSource source,
            TaskKind kind,
            ConstructionTaskClass taskClass,
            List<TaskPrecondition> preconditions,
            TaskPostcondition postcondition,
            Set<ResourceId> placementReservations,
            Set<ResourceId> materialReservations,
            long maximumTicks,
            Map<ResourceId, String> parameters,
            CleanupPolicy cleanup) {
        return new ConstructionTask(
                taskId,
                source,
                descriptor.capabilityId(),
                kind,
                taskClass,
                ALLOWED_MODES,
                preconditions,
                List.of(postcondition),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                cleanup,
                placementReservations,
                materialReservations,
                Set.of(),
                maximumTicks,
                parameters);
    }

    private static TaskDependency dependency(
            ConstructionTask predecessor,
            ConstructionTask successor,
            TaskDependencyKind kind) {
        return new TaskDependency(
                predecessor.taskId(), successor.taskId(), kind, predecessor.postconditionIds());
    }

    private static TaskPrecondition pre(
            ResourceId taskId,
            String suffix,
            TaskConditionKind kind,
            ResourceId subject,
            ExecutionEvidenceKind evidence,
            String key,
            String value) {
        return new TaskPrecondition(
                conditionId(taskId, suffix), kind, subject, evidence,
                Map.of(key(key), value));
    }

    private static TaskPostcondition post(
            ResourceId taskId,
            String suffix,
            TaskConditionKind kind,
            ResourceId subject,
            ExecutionEvidenceKind evidence,
            String key,
            String value) {
        return new TaskPostcondition(
                conditionId(taskId, suffix), kind, subject, evidence,
                Map.of(key(key), value));
    }

    private static VerifiedPlanTaskSource source(
            CreateCapabilityTaskGraphRequest request,
            TaskSourceKind kind,
            ResourceId elementId,
            int ordinal) {
        return new VerifiedPlanTaskSource(
                request.verifiedPhysicalPlan().id(), kind, elementId, ordinal);
    }

    private static ResolvedPhysicalPort itemPort(
            PhysicalMachinePlacement placement,
            boolean input) {
        return placement.ports().values().stream()
                .filter(value -> value.resourceType() == GenericResourceType.ITEM)
                .filter(value -> input ? value.mode().acceptsInput() : value.mode().providesOutput())
                .sorted(Comparator.comparing(value -> value.logicalPortId().toString()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified capability placement lacks an item " + (input ? "input" : "output") + " port"));
    }

    private static void requireExactKeys(
            Map<ResourceId, ResourceId> reservations,
            Set<ResourceId> expected,
            String name) {
        if (!reservations.keySet().equals(expected)) {
            throw new IllegalArgumentException(name + " must match exact verified element IDs");
        }
    }

    private static Map<ResourceId, String> commonParameters(
            CreateCapabilityTaskGraphRequest request,
            ResourceId elementId) {
        Map<ResourceId, String> values = new LinkedHashMap<>();
        values.put(key("recipe_id"), request.semantics().recipeId().toString());
        values.put(key("recipe_type"), request.semantics().recipeType().toString());
        values.put(key("physical_element_id"), elementId.toString());
        values.put(key("runtime_observer"), "create-v606-read-only");
        values.put(key("fixed_sleep_forbidden"), "true");
        values.put(key("player_inventory_forbidden"), "true");
        values.put(key("arbitrary_world_interaction_forbidden"), "true");
        return values;
    }

    private static CleanupPolicy reversibleCleanup(boolean returnMaterials) {
        return new CleanupPolicy(
                CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                returnMaterials,
                true,
                1);
    }

    private static ResourceId taskId(
            CreateCapabilityTaskGraphRequest request,
            String operation,
            int ordinal) {
        return new ResourceId(
                request.graphId().namespace(),
                request.graphId().path() + "/" + operation + "_" + String.format("%03d", ordinal));
    }

    private static ResourceId conditionId(ResourceId taskId, String suffix) {
        return new ResourceId(taskId.namespace(), taskId.path() + "/condition_" + suffix);
    }

    private static ResourceId key(String path) {
        return new ResourceId("steve_industrial", path);
    }
}
