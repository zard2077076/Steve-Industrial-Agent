package dev.stevecreate.agent.adapter.api.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.execution.construction.CapabilityExecutionDescriptor;
import dev.stevecreate.agent.core.execution.construction.CapabilitySupport;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateCapabilityExecutionContractTest {
    private static final String RUNTIME = "sha256:create-caps-runtime";

    @Test
    void descriptorDeclaresDirectBotsAndHybridWithoutProvidingAnExecutor() {
        CapabilityExecutionDescriptor descriptor = CreateCapabilityExecutionDescriptors.forRecipe(
                semantics(CreateCapabilityId.CRUSHING,
                        CapabilityRecipeSupport.SUPPORTED_PHASE_I, List.of()),
                metadata(CreateCapabilityId.CRUSHING));

        assertThat(descriptor.modeCapabilities()).containsOnlyKeys(ExecutionMode.values());
        assertThat(descriptor.modeCapability(ExecutionMode.DIRECT).support())
                .isEqualTo(CapabilitySupport.CONSTRAINED);
        assertThat(descriptor.modeCapability(ExecutionMode.HYBRID).support())
                .isEqualTo(CapabilitySupport.CONSTRAINED);
        assertThat(descriptor.modeCapability(ExecutionMode.BOTS).support())
                .isEqualTo(CapabilitySupport.UNSUPPORTED);
        assertThat(descriptor.modeCapability(ExecutionMode.BOTS).limitation())
                .contains("construction:bot_execution_capability_unsupported");
        assertThat(descriptor.supportedTaskKinds()).contains(
                TaskKind.PLACE_COMPONENT,
                TaskKind.CONNECT_COMPONENTS,
                TaskKind.SAFE_MACHINE_INTERACTION,
                TaskKind.VERIFY_OUTPUT);
        assertThat(descriptor.parameters())
                .containsEntry(id("steve_industrial:executor_provided_by_adapter"), "false")
                .containsEntry(id("steve_industrial:placement_authority"), "false")
                .containsEntry(id("steve_industrial:fixed_sleep_forbidden"), "true")
                .containsEntry(id("steve_industrial:player_inventory_forbidden"), "true");
    }

    @Test
    void unsupportedRecipeCannotClaimAnyExecutionMode() {
        CapabilityRecipeLimitation limitation = new CapabilityRecipeLimitation(
                CapabilityRecipeLimitationCode.ITEM_INGREDIENT_UNSUPPORTED,
                "itemInputs[0]", "fixture unsupported ingredient", true);
        CapabilityExecutionDescriptor descriptor = CreateCapabilityExecutionDescriptors.forRecipe(
                semantics(CreateCapabilityId.CRUSHING,
                        CapabilityRecipeSupport.UNSUPPORTED, List.of(limitation)),
                metadata(CreateCapabilityId.CRUSHING));

        assertThat(descriptor.modeCapabilities().values())
                .allSatisfy(value -> {
                    assertThat(value.support()).isEqualTo(CapabilitySupport.UNSUPPORTED);
                    assertThat(value.requiredExecutorCapabilities()).isEmpty();
                });
    }

    @Test
    void everyC05ThroughC10CapabilityProducesTheFrozenThreeModeDescriptor() {
        for (CreateCapabilityId capability : CreateCapabilityId.values()) {
            CapabilityExecutionDescriptor descriptor = CreateCapabilityExecutionDescriptors.forRecipe(
                    semantics(capability, CapabilityRecipeSupport.SUPPORTED_PHASE_I, List.of()),
                    metadata(capability));
            assertThat(descriptor.capabilityId()).isEqualTo(capability.implementationCapabilityId());
            assertThat(descriptor.modeCapabilities()).containsOnlyKeys(ExecutionMode.values());
            assertThat(descriptor.modeCapability(ExecutionMode.BOTS).support())
                    .isEqualTo(CapabilitySupport.UNSUPPORTED);
        }
    }

    @Test
    void observationRequestBindsExactAssignmentPlanSourceAndDeltas() {
        CapabilityObservationRequest request = request(TaskSourceKind.VERIFIED_PORT);
        assertThat(request.componentPositions())
                .containsEntry(CapabilityObservationRoles.PRIMARY_MACHINE, new BlockPos3i(1, 2, 3));
        assertThat(request.expectedConsumedItemCounts()).containsEntry(id("minecraft:cobblestone"), 1L);
        assertThat(request.expectedProducedItemCounts()).containsEntry(id("minecraft:gravel"), 1L);

        assertThatThrownBy(() -> request(TaskSourceKind.VERIFIED_SYSTEM_CHECK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified placement, route or port");
        assertThatThrownBy(() -> request(TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified placement, route or port");
    }

    @Test
    void successfulObservationRetainsFreshAssignmentRuntimeWorldAndDeltaEvidence() {
        CapabilityObservationRequest request = request(TaskSourceKind.VERIFIED_PORT);
        CapabilityRuntimeObservation observation = new CapabilityRuntimeObservation(
                request.sessionId(),
                request.graphId(),
                request.taskId(),
                request.assignmentId(),
                request.verifiedPhysicalPlanId(),
                request.capability(),
                request.recipeId(),
                request.runtimeFingerprint(),
                request.worldSnapshotFingerprint(),
                request.requestedAtTick(),
                request.maximumObservationTicks(),
                101,
                Map.of(CapabilityObservationRoles.PRIMARY_MACHINE, id("create:crushing_wheel")),
                Map.of(CapabilityObservationRoles.PRIMARY_MACHINE, 32.0D),
                Set.of(),
                Map.of(id("minecraft:gravel"), 1L),
                true, true, true, true, true, false,
                List.of("assignment=" + request.assignmentId(), "worldMutation=false"));

        assertThat(new CapabilityObservationResult.Success(observation).observation())
                .isSameAs(observation);
        assertThat(observation.assignmentId()).isEqualTo(request.assignmentId());
        assertThat(observation.runtimeFingerprint()).isEqualTo(RUNTIME);
        assertThat(observation.worldSnapshotFingerprint()).isEqualTo("sha256:world-snapshot");
        assertThat(observation.observedServerTick()).isGreaterThanOrEqualTo(request.requestedAtTick());

        assertThatThrownBy(() -> new CapabilityRuntimeObservation(
                request.sessionId(), request.graphId(), request.taskId(), request.assignmentId(),
                request.verifiedPhysicalPlanId(), request.capability(), request.recipeId(),
                request.runtimeFingerprint(), request.worldSnapshotFingerprint(), 100, 1, 102,
                Map.of(), Map.of(), Set.of(), Map.of(),
                true, true, true, true, true, false, List.of("worldMutation=false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void observerFailuresRemainTypedBoundedAndAssignmentAttributed() {
        CapabilityObservationFailure failure = new CapabilityObservationFailure(
                CapabilityObservationFailureCode.AIRFLOW_OBSTRUCTED,
                CreateCapabilityId.CRUSHING,
                id("test:crushing"),
                id("assignment:one"),
                "airflow",
                "Live path is blocked",
                true,
                List.of("observer=create-v606-read-only", "worldMutation=false"));
        assertThat(new CapabilityObservationResult.Failure(failure).failure())
                .isSameAs(failure);
        assertThat(failure.retryable()).isTrue();
    }

    private static CapabilityObservationRequest request(TaskSourceKind kind) {
        return new CapabilityObservationRequest(
                id("session:one"),
                id("graph:one"),
                id("task:one"),
                id("assignment:one"),
                id("layout:verified_one"),
                kind,
                id("physical:port_one"),
                CreateCapabilityId.CRUSHING,
                id("test:crushing"),
                RUNTIME,
                "sha256:world-snapshot",
                Map.of(CapabilityObservationRoles.PRIMARY_MACHINE, new BlockPos3i(1, 2, 3)),
                Map.of(id("minecraft:cobblestone"), 1L),
                Map.of(id("minecraft:cobblestone"), 1L),
                Map.of(id("minecraft:gravel"), 1L),
                100,
                1_200);
    }

    private static CapabilityImplementationBindingMetadata metadata(CreateCapabilityId capability) {
        return new CapabilityImplementationBindingMetadata(
                id("test:" + capability.name().toLowerCase() + "_implementation"),
                capability,
                "1.20.1",
                "6.0.6",
                EnumSet.of(Direction6.NORTH, Direction6.SOUTH),
                List.of(new CapabilityComponentRequirement(
                        CapabilityComponentRole.PRIMARY_MACHINE,
                        new BlockPos3i(0, 0, 0),
                        List.of(id("create:crushing_wheel")),
                        true,
                        true,
                        true)),
                List.of(new CapabilityZoneRequirement(
                        "wheel_gap",
                        new BlockPos3i(0, 0, 0),
                        new BlockPos3i(1, 1, 1),
                        true,
                        true,
                        true)),
                List.of("fixture limitation"),
                true,
                false,
                false);
    }

    private static CapabilityRecipeSemantics semantics(
            CreateCapabilityId capability,
            CapabilityRecipeSupport support,
            List<CapabilityRecipeLimitation> limitations) {
        RecipeIngredient input = new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1);
        CapabilityOutput output = new CapabilityOutput(
                0,
                new ProcessResource(id("minecraft:gravel"), GenericResourceType.ITEM, 1),
                CapabilityOutput.PROBABILITY_DENOMINATOR,
                true);
        RuntimeObservationRequirement observation = new RuntimeObservationRequirement(
                EnumSet.of(
                        RuntimeObservationRequirement.Signal.LIVE_RECIPE,
                        RuntimeObservationRequirement.Signal.KINETIC_SPEED,
                        RuntimeObservationRequirement.Signal.INPUT_CONSUMED,
                        RuntimeObservationRequirement.Signal.OUTPUT_OBSERVED),
                1_200,
                true);
        MediumRequirement medium = switch (capability) {
            case FAN_WASHING -> MediumRequirement.WATER;
            case FAN_SMOKING -> MediumRequirement.FIRE;
            case FAN_HAUNTING -> MediumRequirement.SOUL_FIRE;
            case FAN_BLASTING -> MediumRequirement.LAVA;
            default -> MediumRequirement.NONE;
        };
        boolean fan = medium != MediumRequirement.NONE;
        boolean basin = capability == CreateCapabilityId.MIXING_PHASE_I
                || capability == CreateCapabilityId.COMPACTING_PHASE_I;
        boolean deployer = capability == CreateCapabilityId.DEPLOYING_PHASE_I;
        DirectionalFlowRequirement directional = switch (capability) {
            case CRUSHING -> new DirectionalFlowRequirement(
                    true, RotationDirectionRequirement.OPPOSED_INWARD_PAIR, true, 1);
            case FAN_WASHING, FAN_SMOKING, FAN_HAUNTING, FAN_BLASTING ->
                    new DirectionalFlowRequirement(
                            true, RotationDirectionRequirement.ALONG_MACHINE_FACING, true, 1);
            case CUTTING, DEPLOYING_PHASE_I -> new DirectionalFlowRequirement(
                    true, RotationDirectionRequirement.FORWARD_PROCESSING_DIRECTION, true, 1);
            default -> DirectionalFlowRequirement.none();
        };
        ProcessingEnvironmentRequirement environment = new ProcessingEnvironmentRequirement(
                directional,
                HeatRequirement.NONE,
                fan ? new AirflowRequirement(true, medium, 1, true, true, true)
                        : AirflowRequirement.none(),
                medium,
                ToolRequirement.none(),
                CatalystRequirement.none(),
                basin ? new BasinRequirement(true, 9, 0, false, true) : BasinRequirement.none(),
                deployer
                        ? new HeldItemRequirement(Optional.of(input), true, true, true)
                        : HeldItemRequirement.none(),
                new MinimumSpeedRequirement(OptionalLong.empty(), true),
                observation,
                true,
                true);
        return new CapabilityRecipeSemantics(
                id("test:" + capability.name().toLowerCase()),
                capability.recipeType(),
                capability,
                List.of(input),
                List.of(),
                List.of(),
                new MultiOutputSemantics(List.of(output), true, true),
                new ProbabilisticOutputSemantics(
                        List.of(CapabilityOutput.PROBABILITY_DENOMINATOR),
                        CapabilityOutput.PROBABILITY_DENOMINATOR,
                        true),
                environment,
                OptionalLong.of(100),
                support,
                limitations,
                RUNTIME);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
