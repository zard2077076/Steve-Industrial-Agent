package dev.stevecreate.agent.core.execution.construction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnedWorkpieceApplicationTaskGraphFactoryTest {
    private static final ResourceId PLAN_ID = id("test:verified_plan");
    private static final ResourceId SESSION_ID = id("test:session");
    private static final ResourceId RECIPE_ID = id(
            "create:item_application/andesite_casing_from_log");
    private static final ResourceId HELD_ITEM = id("create:andesite_alloy");
    private static final ResourceId RESULT_BLOCK = id("create:andesite_casing");

    @Test
    void mapsExactOwnedWorkpieceToOneSharedThreeModeContract() {
        ConstructionTaskGraph graph = graph();

        assertThat(graph.graphId()).isEqualTo(id("test:session/c10_owned_workpiece_graph"));
        assertThat(graph.verifiedPhysicalPlanId()).isEqualTo(PLAN_ID);
        assertThat(graph.runtimeFingerprint()).isEqualTo("runtime:create-6.0.6");
        assertThat(graph.worldSnapshotFingerprint()).isEqualTo("snapshot:test-world:1");
        assertThat(graph.topologicalOrder())
                .extracting(ConstructionTask::kind)
                .containsExactly(
                        TaskKind.TRANSPORT_MATERIAL,
                        TaskKind.SAFE_MACHINE_INTERACTION,
                        TaskKind.VERIFY_OUTPUT);

        CapabilityExecutionDescriptor descriptor = graph.descriptor(
                OwnedWorkpieceApplicationTaskGraphFactory.CAPABILITY_ID);
        assertThat(descriptor.implementationId()).isEqualTo(
                OwnedWorkpieceApplicationTaskGraphFactory.IMPLEMENTATION_ID);
        assertThat(descriptor.modeCapabilities().keySet())
                .containsExactlyElementsOf(EnumSet.allOf(ExecutionMode.class));
        assertThat(descriptor.modeCapability(ExecutionMode.DIRECT)
                .requiredExecutorCapabilities()).containsExactly(
                        OwnedWorkpieceApplicationTaskGraphFactory.DIRECT_EXECUTOR_CAPABILITY);
        assertThat(descriptor.modeCapability(ExecutionMode.BOTS)
                .requiredExecutorCapabilities()).containsExactly(
                        OwnedWorkpieceApplicationTaskGraphFactory.BOT_EXECUTOR_CAPABILITY);
        assertThat(descriptor.modeCapability(ExecutionMode.HYBRID)
                .requiredExecutorCapabilities()).containsExactly(
                        OwnedWorkpieceApplicationTaskGraphFactory.HYBRID_EXECUTOR_CAPABILITY);
    }

    @Test
    void bindsEveryTaskToExactPlanSessionRecipeRegionAndPositions() {
        ConstructionTaskGraph graph = graph();

        assertThat(graph.tasks().values()).allSatisfy(task -> {
            assertThat(task.source().verifiedPhysicalPlanId()).isEqualTo(PLAN_ID);
            assertThat(task.allowedModes()).containsExactlyInAnyOrderElementsOf(
                    EnumSet.allOf(ExecutionMode.class));
            assertThat(task.parameters())
                    .containsEntry(id("schema:verified_physical_plan"), PLAN_ID.toString())
                    .containsEntry(id("schema:session"), SESSION_ID.toString())
                    .containsEntry(id("schema:recipe"), RECIPE_ID.toString())
                    .containsEntry(id("schema:initial_block"), "minecraft:stripped_oak_log")
                    .containsEntry(id("schema:held_item"), HELD_ITEM.toString())
                    .containsEntry(id("schema:result_block"), RESULT_BLOCK.toString())
                    .containsEntry(id("schema:resource_buffer_position"), "8,64,8")
                    .containsEntry(id("schema:workpiece_position"), "10,64,10")
                    .containsEntry(id("schema:deployer_position"), "10,66,10")
                    .containsEntry(id("schema:drive_position"), "10,66,11")
                    .containsEntry(id("schema:region_minimum"), "8,63,8")
                    .containsEntry(id("schema:region_maximum"), "12,68,12");
        });
    }

    @Test
    void carriesAllForbiddenAuthoritiesWithoutGenericUseEscapeHatch() {
        ConstructionTaskGraph graph = graph();
        String restrictions = graph.topologicalOrder().get(1).parameters().get(
                id("schema:forbidden_authorities"));

        assertThat(restrictions).contains(
                "arbitrary_position_use",
                "container_opening",
                "entity_interaction",
                "combat",
                "player_inventory",
                "private_storage",
                "unknown_nbt_mutation",
                "unknown_block_entity_mutation",
                "unknown_world_side_effects");
        assertThat(graph.tasks().values())
                .allSatisfy(task -> assertThat(task.parameters().keySet())
                        .doesNotContain(id("schema:arbitrary_target"), id("schema:free_text")));
    }

    @Test
    void interactionConsumesOnlyReservedHeldItemAndUsesExactCleanup() {
        ConstructionTask apply = graph().topologicalOrder().get(1);

        assertThat(apply.kind()).isEqualTo(TaskKind.SAFE_MACHINE_INTERACTION);
        assertThat(apply.taskClass()).isEqualTo(ConstructionTaskClass.CREATE_MACHINE);
        assertThat(apply.requiredPlacementReservationIds())
                .containsExactly(id("test:session/c10_owned_workpiece_placement"));
        assertThat(apply.requiredMaterialReservationIds())
                .containsExactly(id("test:session/c10_owned_workpiece_material"));
        assertThat(apply.retryPolicy().allowsRetry()).isFalse();
        assertThat(apply.recoveryPolicy()).isEqualTo(RecoveryPolicy.REFUSE);
        assertThat(apply.cleanupPolicy().scope())
                .isEqualTo(CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY);
        assertThat(apply.cleanupPolicy().exactStateVerificationRequired()).isTrue();
        assertThat(apply.postconditions())
                .extracting(TaskPostcondition::requiredEvidenceKind)
                .containsExactlyInAnyOrder(
                        ExecutionEvidenceKind.MACHINE_INTERACTION_VERIFIED,
                        ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        ExecutionEvidenceKind.CLEANUP_VERIFIED);
    }

    @Test
    void dependenciesRequireDeliveryThenEveryApplicationPostcondition() {
        ConstructionTaskGraph graph = graph();
        List<ConstructionTask> tasks = graph.topologicalOrder();

        assertThat(graph.dependencies()).hasSize(2);
        TaskDependency delivery = graph.dependencies().stream()
                .filter(value -> value.predecessorTaskId().equals(tasks.get(0).taskId()))
                .findFirst().orElseThrow();
        assertThat(delivery.requiredPostconditionIds())
                .containsExactlyInAnyOrderElementsOf(tasks.get(0).postconditionIds());
        TaskDependency verification = graph.dependencies().stream()
                .filter(value -> value.predecessorTaskId().equals(tasks.get(1).taskId()))
                .findFirst().orElseThrow();
        assertThat(verification.requiredPostconditionIds())
                .containsExactlyInAnyOrderElementsOf(tasks.get(1).postconditionIds());

        ConstructionFleetTaskAdapter fleetAdapter =
                new ConstructionFleetTaskAdapter(PriorityPolicy.TOPOLOGICAL_THEN_ID);
        assertThat(fleetAdapter.workerContinuityPredecessorId(graph, tasks.get(1)))
                .as("an explicit material-delivery edge permits a typed worker hand-off")
                .isEmpty();
    }

    private static ConstructionTaskGraph graph() {
        return new OwnedWorkpieceApplicationTaskGraphFactory().create(
                OwnedWorkpieceApplicationPlan.andesiteCasing(
                        PLAN_ID,
                        SESSION_ID,
                        new BlockPos3i(10, 64, 10),
                        new BlockPos3i(8, 64, 8),
                        new BlockPos3i(8, 63, 8),
                        new BlockPos3i(12, 68, 12)),
                "runtime:create-6.0.6",
                "snapshot:test-world:1");
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
