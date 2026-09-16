package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPolicy;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps one reviewed C-10 owned-workpiece application to the shared Executor Contract v1.
 *
 * <p>The graph grants no generic use authority. Every task is bound to the exact verified plan,
 * session, recipe, resource, bounded region and workpiece carried by the loader-neutral policy.
 * Direct, Bots and Hybrid therefore consume the same typed authority envelope.</p>
 */
public final class OwnedWorkpieceApplicationTaskGraphFactory {
    public static final ResourceId CAPABILITY_ID = id(
            "construction:c10_owned_workpiece_application");
    public static final ResourceId IMPLEMENTATION_ID = id(
            "construction:create_v606_owned_workpiece_application");
    public static final ResourceId ADAPTER_ID = id("construction:forge_create_v606");
    public static final ResourceId DIRECT_EXECUTOR_CAPABILITY = id(
            "construction:bounded_server_world_action");
    public static final ResourceId BOT_EXECUTOR_CAPABILITY = id(
            "construction:bot_create_v606_bounded_action");
    public static final ResourceId HYBRID_EXECUTOR_CAPABILITY = id(
            "construction:hybrid_router_v1");

    private static final Set<ExecutionMode> ALL_MODES = EnumSet.allOf(ExecutionMode.class);
    private static final ResourceId SCHEMA_PLAN = id("schema:verified_physical_plan");
    private static final ResourceId SCHEMA_SESSION = id("schema:session");
    private static final ResourceId SCHEMA_RECIPE = id("schema:recipe");
    private static final ResourceId SCHEMA_INITIAL_BLOCK = id("schema:initial_block");
    private static final ResourceId SCHEMA_HELD_ITEM = id("schema:held_item");
    private static final ResourceId SCHEMA_RESULT_BLOCK = id("schema:result_block");
    private static final ResourceId SCHEMA_RESOURCE_BUFFER = id("schema:resource_buffer_position");
    private static final ResourceId SCHEMA_WORKPIECE = id("schema:workpiece_position");
    private static final ResourceId SCHEMA_DEPLOYER = id("schema:deployer_position");
    private static final ResourceId SCHEMA_DRIVE = id("schema:drive_position");
    private static final ResourceId SCHEMA_REGION_MIN = id("schema:region_minimum");
    private static final ResourceId SCHEMA_REGION_MAX = id("schema:region_maximum");
    private static final ResourceId SCHEMA_QUANTITY = id("schema:quantity");
    private static final ResourceId SCHEMA_CONSUMED = id("schema:consumed");
    private static final ResourceId SCHEMA_RESTRICTIONS = id("schema:forbidden_authorities");
    private static final ResourceId SCHEMA_CLEANUP = id("schema:cleanup");
    private static final String FORBIDDEN_AUTHORITIES = String.join(",",
            "arbitrary_position_use",
            "container_opening",
            "entity_interaction",
            "combat",
            "player_inventory",
            "private_storage",
            "unknown_nbt_mutation",
            "unknown_block_entity_mutation",
            "unknown_world_side_effects");

    public ConstructionTaskGraph create(
            OwnedWorkpieceApplicationPlan plan,
            String runtimeFingerprint,
            String worldSnapshotFingerprint) {
        Objects.requireNonNull(plan, "plan");
        OwnedWorkpieceApplicationPolicy policy = plan.policy();
        CapabilityExecutionDescriptor descriptor = descriptor(runtimeFingerprint);
        ResourceId materialReservation = child(policy.sessionId(), "c10_owned_workpiece_material");
        ResourceId placementReservation = child(policy.sessionId(), "c10_owned_workpiece_placement");
        ResourceId physicalElement = child(
                policy.verifiedPhysicalPlanId(), "c10_owned_workpiece");

        ConstructionTask deliver = new ConstructionTask(
                child(policy.sessionId(), "c10_owned_workpiece_deliver"),
                source(policy, TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT, physicalElement, 0),
                CAPABILITY_ID,
                TaskKind.TRANSPORT_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                ALL_MODES,
                List.of(pre(
                        policy.sessionId(), "material_reserved",
                        TaskConditionKind.MATERIAL_RESERVED, policy.heldItem(),
                        ExecutionEvidenceKind.MATERIAL_RESERVED,
                        Map.of(SCHEMA_SESSION, policy.sessionId().toString()))),
                List.of(post(
                        policy.sessionId(), "held_item_delivered",
                        TaskConditionKind.MATERIAL_DELIVERED, policy.heldItem(),
                        ExecutionEvidenceKind.MATERIAL_DELIVERED,
                        Map.of(
                                SCHEMA_HELD_ITEM, policy.heldItem().toString(),
                                SCHEMA_QUANTITY, "1",
                                SCHEMA_RESOURCE_BUFFER, position(plan.resourceBufferPosition())))),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                CleanupPolicy.NONE,
                Set.of(),
                Set.of(materialReservation),
                Set.of(),
                1_200,
                exactParameters(plan));

        ConstructionTask apply = new ConstructionTask(
                child(policy.sessionId(), "c10_owned_workpiece_apply"),
                source(policy, TaskSourceKind.VERIFIED_PLACEMENT, physicalElement, 1),
                CAPABILITY_ID,
                TaskKind.SAFE_MACHINE_INTERACTION,
                ConstructionTaskClass.CREATE_MACHINE,
                ALL_MODES,
                List.of(
                        pre(
                                policy.sessionId(), "region_authorized",
                                TaskConditionKind.REGION_AUTHORIZED, physicalElement,
                                ExecutionEvidenceKind.PRECONDITION,
                                Map.of(
                                        SCHEMA_PLAN, policy.verifiedPhysicalPlanId().toString(),
                                        SCHEMA_REGION_MIN, position(policy.regionMinimum()),
                                        SCHEMA_REGION_MAX, position(policy.regionMaximum()))),
                        pre(
                                policy.sessionId(), "session_owned",
                                TaskConditionKind.SESSION_OWNERSHIP_MATCHES, physicalElement,
                                ExecutionEvidenceKind.OWNERSHIP_VERIFIED,
                                Map.of(SCHEMA_SESSION, policy.sessionId().toString())),
                        pre(
                                policy.sessionId(), "initial_block_exact",
                                TaskConditionKind.CURRENT_STATE_MATCHES, policy.initialBlock(),
                                ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                                Map.of(
                                        SCHEMA_WORKPIECE, position(policy.workpiecePosition()),
                                        SCHEMA_INITIAL_BLOCK, policy.initialBlock().toString())),
                        pre(
                                policy.sessionId(), "safe_machine_face",
                                TaskConditionKind.SAFE_MACHINE_FACE, physicalElement,
                                ExecutionEvidenceKind.PRECONDITION,
                                Map.of(
                                        SCHEMA_RECIPE, policy.recipeId().toString(),
                                        SCHEMA_RESTRICTIONS, FORBIDDEN_AUTHORITIES))),
                List.of(
                        post(
                                policy.sessionId(), "application_verified",
                                TaskConditionKind.CURRENT_STATE_MATCHES, physicalElement,
                                ExecutionEvidenceKind.MACHINE_INTERACTION_VERIFIED,
                                Map.of(
                                        SCHEMA_RECIPE, policy.recipeId().toString(),
                                        SCHEMA_HELD_ITEM, policy.heldItem().toString(),
                                        SCHEMA_CONSUMED, "1")),
                        post(
                                policy.sessionId(), "result_block_exact",
                                TaskConditionKind.OUTPUT_PRESENT, policy.resultBlock(),
                                ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                                Map.of(
                                        SCHEMA_WORKPIECE, position(policy.workpiecePosition()),
                                        SCHEMA_RESULT_BLOCK, policy.resultBlock().toString())),
                        post(
                                policy.sessionId(), "owned_machine_cleanup",
                                TaskConditionKind.CLEANUP_COMPLETE, physicalElement,
                                ExecutionEvidenceKind.CLEANUP_VERIFIED,
                                Map.of(SCHEMA_CLEANUP, "session_owned_machine_removed"))),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                new CleanupPolicy(
                        CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                        true,
                        true,
                        3),
                Set.of(placementReservation),
                Set.of(materialReservation),
                Set.of(),
                2_400,
                exactParameters(plan));

        ConstructionTask verify = new ConstructionTask(
                child(policy.sessionId(), "c10_owned_workpiece_verify"),
                source(policy, TaskSourceKind.VERIFIED_SYSTEM_CHECK, physicalElement, 2),
                CAPABILITY_ID,
                TaskKind.VERIFY_OUTPUT,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                ALL_MODES,
                List.of(),
                List.of(
                        post(
                                policy.sessionId(), "output_rescan",
                                TaskConditionKind.OUTPUT_PRESENT, policy.resultBlock(),
                                ExecutionEvidenceKind.OUTPUT_VERIFIED,
                                Map.of(
                                        SCHEMA_PLAN, policy.verifiedPhysicalPlanId().toString(),
                                        SCHEMA_WORKPIECE, position(policy.workpiecePosition()),
                                        SCHEMA_RESULT_BLOCK, policy.resultBlock().toString())),
                        post(
                                policy.sessionId(), "boundary_rescan",
                                TaskConditionKind.CURRENT_STATE_MATCHES, physicalElement,
                                ExecutionEvidenceKind.CLEANUP_VERIFIED,
                                Map.of(
                                        SCHEMA_CLEANUP, "session_owned_machine_absent",
                                        SCHEMA_RESTRICTIONS, FORBIDDEN_AUTHORITIES))),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                CleanupPolicy.NONE,
                Set.of(),
                Set.of(),
                Set.of(),
                1_200,
                exactParameters(plan));

        TaskDependency deliveredBeforeApply = new TaskDependency(
                deliver.taskId(), apply.taskId(), TaskDependencyKind.MATERIAL_DELIVERY,
                deliver.postconditionIds());
        TaskDependency appliedBeforeVerify = new TaskDependency(
                apply.taskId(), verify.taskId(), TaskDependencyKind.VERIFICATION_GATE,
                apply.postconditionIds());
        return new ConstructionTaskGraph(
                child(policy.sessionId(), "c10_owned_workpiece_graph"),
                policy.verifiedPhysicalPlanId(),
                runtimeFingerprint,
                worldSnapshotFingerprint,
                List.of(descriptor),
                List.of(deliver, apply, verify),
                List.of(deliveredBeforeApply, appliedBeforeVerify));
    }

    private static CapabilityExecutionDescriptor descriptor(String runtimeFingerprint) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes =
                new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, supported(
                ExecutionMode.DIRECT, DIRECT_EXECUTOR_CAPABILITY));
        modes.put(ExecutionMode.BOTS, supported(
                ExecutionMode.BOTS, BOT_EXECUTOR_CAPABILITY));
        modes.put(ExecutionMode.HYBRID, supported(
                ExecutionMode.HYBRID, HYBRID_EXECUTOR_CAPABILITY));
        return new CapabilityExecutionDescriptor(
                CAPABILITY_ID,
                IMPLEMENTATION_ID,
                ADAPTER_ID,
                runtimeFingerprint,
                EnumSet.of(
                        TaskKind.TRANSPORT_MATERIAL,
                        TaskKind.SAFE_MACHINE_INTERACTION,
                        TaskKind.VERIFY_OUTPUT),
                modes,
                Map.of(
                        id("construction:contract"), "executor-v1",
                        id("construction:authority"), "verified-session-owned-workpiece-only"));
    }

    private static ModeCapabilityDeclaration supported(
            ExecutionMode mode, ResourceId requiredCapability) {
        return new ModeCapabilityDeclaration(
                mode, CapabilitySupport.SUPPORTED, Set.of(requiredCapability), "");
    }

    private static VerifiedPlanTaskSource source(
            OwnedWorkpieceApplicationPolicy policy,
            TaskSourceKind sourceKind,
            ResourceId physicalElement,
            int ordinal) {
        return new VerifiedPlanTaskSource(
                policy.verifiedPhysicalPlanId(), sourceKind, physicalElement, ordinal);
    }

    private static TaskPrecondition pre(
            ResourceId sessionId,
            String suffix,
            TaskConditionKind kind,
            ResourceId subject,
            ExecutionEvidenceKind evidence,
            Map<ResourceId, String> expected) {
        return new TaskPrecondition(
                child(sessionId, "c10_pre_" + suffix), kind, subject, evidence, expected);
    }

    private static TaskPostcondition post(
            ResourceId sessionId,
            String suffix,
            TaskConditionKind kind,
            ResourceId subject,
            ExecutionEvidenceKind evidence,
            Map<ResourceId, String> expected) {
        return new TaskPostcondition(
                child(sessionId, "c10_post_" + suffix), kind, subject, evidence, expected);
    }

    private static Map<ResourceId, String> exactParameters(
            OwnedWorkpieceApplicationPlan plan) {
        OwnedWorkpieceApplicationPolicy policy = plan.policy();
        Map<ResourceId, String> values = new LinkedHashMap<>();
        values.put(SCHEMA_PLAN, policy.verifiedPhysicalPlanId().toString());
        values.put(SCHEMA_SESSION, policy.sessionId().toString());
        values.put(SCHEMA_RECIPE, policy.recipeId().toString());
        values.put(SCHEMA_INITIAL_BLOCK, policy.initialBlock().toString());
        values.put(SCHEMA_HELD_ITEM, policy.heldItem().toString());
        values.put(SCHEMA_RESULT_BLOCK, policy.resultBlock().toString());
        values.put(SCHEMA_RESOURCE_BUFFER, position(plan.resourceBufferPosition()));
        values.put(SCHEMA_WORKPIECE, position(policy.workpiecePosition()));
        values.put(SCHEMA_DEPLOYER, position(plan.deployerPosition()));
        values.put(SCHEMA_DRIVE, position(plan.drivePosition()));
        values.put(SCHEMA_REGION_MIN, position(policy.regionMinimum()));
        values.put(SCHEMA_REGION_MAX, position(policy.regionMaximum()));
        values.put(SCHEMA_RESTRICTIONS, FORBIDDEN_AUTHORITIES);
        return Map.copyOf(values);
    }

    private static String position(BlockPos3i value) {
        return value.x() + "," + value.y() + "," + value.z();
    }

    private static ResourceId child(ResourceId parent, String suffix) {
        return new ResourceId(parent.namespace(), parent.path() + "/" + suffix);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
