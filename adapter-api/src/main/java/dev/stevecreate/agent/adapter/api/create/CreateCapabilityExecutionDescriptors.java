package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.execution.construction.CapabilityExecutionDescriptor;
import dev.stevecreate.agent.core.execution.construction.CapabilitySupport;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ModeCapabilityDeclaration;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Adapter-owned C-05 through C-10 descriptors for the frozen Executor Contract v1. */
public final class CreateCapabilityExecutionDescriptors {
    public static final ResourceId ADAPTER_ID = id("steve_industrial:create_runtime_1_20_1_6_0_6");
    public static final ResourceId DIRECT_EXECUTOR_CAPABILITY = id(
            "steve_industrial:direct_world_executor_v1");
    public static final ResourceId RUNTIME_OBSERVER_CAPABILITY = id(
            "steve_industrial:create_v606_runtime_observer");
    public static final ResourceId HYBRID_ROUTER_CAPABILITY = id(
            "steve_industrial:verified_direct_fallback_router_v1");
    public static final ResourceId BOT_UNSUPPORTED = id(
            "construction:bot_execution_capability_unsupported");

    private static final Set<TaskKind> TASK_KINDS = EnumSet.of(
            TaskKind.FETCH_MATERIAL,
            TaskKind.TRANSPORT_MATERIAL,
            TaskKind.PLACE_COMPONENT,
            TaskKind.CONNECT_COMPONENTS,
            TaskKind.SAFE_MACHINE_INTERACTION,
            TaskKind.VERIFY_STATE,
            TaskKind.VERIFY_OUTPUT);

    private CreateCapabilityExecutionDescriptors() {
    }

    public static CapabilityExecutionDescriptor forRecipe(
            CapabilityRecipeSemantics semantics,
            CapabilityImplementationBindingMetadata metadata) {
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.capability() != semantics.capability()
                || !metadata.recipeType().equals(semantics.recipeType())) {
            throw new IllegalArgumentException("Capability metadata and runtime recipe semantics disagree");
        }

        boolean executable = semantics.support() == CapabilityRecipeSupport.SUPPORTED_PHASE_I;
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        if (executable) {
            modes.put(ExecutionMode.DIRECT, constrained(
                    ExecutionMode.DIRECT,
                    Set.of(DIRECT_EXECUTOR_CAPABILITY, RUNTIME_OBSERVER_CAPABILITY),
                    "Requires A-owned DirectWorldExecutor schema validation and exact v606 readback"));
            modes.put(ExecutionMode.BOTS, unsupported(
                    ExecutionMode.BOTS,
                    BOT_UNSUPPORTED + ": no verified Bot machine-interaction adapter exists"));
            modes.put(ExecutionMode.HYBRID, constrained(
                    ExecutionMode.HYBRID,
                    Set.of(DIRECT_EXECUTOR_CAPABILITY, HYBRID_ROUTER_CAPABILITY,
                            RUNTIME_OBSERVER_CAPABILITY),
                    "Hybrid may route only to validated Direct support; Bot execution is unsupported"));
        } else {
            String limitation = "Runtime recipe support=" + semantics.support()
                    + "; limitations=" + semantics.limitations().stream()
                    .map(value -> value.code().name()).distinct().sorted()
                    .collect(Collectors.joining(","));
            for (ExecutionMode mode : ExecutionMode.values()) {
                modes.put(mode, unsupported(mode, limitation));
            }
        }
        return new CapabilityExecutionDescriptor(
                semantics.capability().implementationCapabilityId(),
                metadata.metadataId(),
                ADAPTER_ID,
                semantics.runtimeFingerprint(),
                TASK_KINDS,
                modes,
                parameters(semantics, metadata));
    }

    private static Map<ResourceId, String> parameters(
            CapabilityRecipeSemantics semantics,
            CapabilityImplementationBindingMetadata metadata) {
        ProcessingEnvironmentRequirement environment = semantics.environment();
        Map<ResourceId, String> values = new LinkedHashMap<>();
        values.put(id("steve_industrial:contract_schema"),
                "create-capability-execution-descriptor/v1");
        values.put(id("steve_industrial:phase"), semantics.capability().phaseCode());
        values.put(id("steve_industrial:recipe_id"), semantics.recipeId().toString());
        values.put(id("steve_industrial:recipe_type"), semantics.recipeType().toString());
        values.put(id("steve_industrial:heat"), environment.heat().name());
        values.put(id("steve_industrial:medium"), environment.medium().name());
        values.put(id("steve_industrial:directional_flow"),
                Boolean.toString(environment.directionalFlow().required()));
        values.put(id("steve_industrial:rotation_direction"),
                environment.directionalFlow().rotationDirection().name());
        values.put(id("steve_industrial:airflow_required"),
                Boolean.toString(environment.airflow().required()));
        values.put(id("steve_industrial:airflow_minimum_reach"),
                Integer.toString(environment.airflow().minimumReachBlocks()));
        values.put(id("steve_industrial:basin_required"),
                Boolean.toString(environment.basin().required()));
        values.put(id("steve_industrial:basin_maximum_item_inputs"),
                Integer.toString(environment.basin().maximumItemInputs()));
        values.put(id("steve_industrial:basin_maximum_fluid_inputs"),
                Integer.toString(environment.basin().maximumFluidInputs()));
        values.put(id("steve_industrial:tool"), environment.tool().tool()
                .map(RecipeIngredient::canonicalIdentity).orElse("none"));
        values.put(id("steve_industrial:tool_consumption"),
                environment.tool().consumption().name());
        values.put(id("steve_industrial:held_item"), environment.heldItem().heldItem()
                .map(RecipeIngredient::canonicalIdentity).orElse("none"));
        values.put(id("steve_industrial:held_item_consumed"),
                Boolean.toString(environment.heldItem().consumed()));
        values.put(id("steve_industrial:runtime_observation_signals"),
                environment.runtimeObservation().signals().stream()
                        .map(Enum::name).sorted().collect(Collectors.joining(",")));
        values.put(id("steve_industrial:runtime_observation_maximum_ticks"),
                Integer.toString(environment.runtimeObservation().maximumObservationTicks()));
        values.put(id("steve_industrial:fixed_sleep_forbidden"),
                Boolean.toString(environment.runtimeObservation().fixedSleepForbidden()));
        values.put(id("steve_industrial:item_processing_only"),
                Boolean.toString(environment.itemProcessingOnly()));
        values.put(id("steve_industrial:arbitrary_world_interaction_forbidden"),
                Boolean.toString(environment.arbitraryWorldInteractionForbidden()));
        values.put(id("steve_industrial:player_inventory_forbidden"), "true");
        values.put(id("steve_industrial:metadata_id"), metadata.metadataId().toString());
        values.put(id("steve_industrial:placement_authority"), "false");
        values.put(id("steve_industrial:executor_provided_by_adapter"), "false");
        return values;
    }

    private static ModeCapabilityDeclaration constrained(
            ExecutionMode mode,
            Set<ResourceId> capabilities,
            String limitation) {
        return new ModeCapabilityDeclaration(
                mode, CapabilitySupport.CONSTRAINED, capabilities, limitation);
    }

    private static ModeCapabilityDeclaration unsupported(ExecutionMode mode, String limitation) {
        return new ModeCapabilityDeclaration(
                mode, CapabilitySupport.UNSUPPORTED, Set.of(), limitation);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
