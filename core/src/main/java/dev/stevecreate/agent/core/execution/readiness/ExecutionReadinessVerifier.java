package dev.stevecreate.agent.core.execution.readiness;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.layout.LayoutCellState;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.PhysicalRoute;
import dev.stevecreate.agent.core.layout.PhysicalizationVerificationCheck;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Independent fail-closed gate immediately before any execution session is created. */
public final class ExecutionReadinessVerifier {
    public ExecutionReadinessResult verify(
            VerifiedPhysicalPlan plan,
            ExecutionReadinessContext context) {
        if (plan == null || context == null) {
            ResourceId missing = ResourceId.parse("execution:missing_physical_plan");
            ResourceId session = context == null
                    ? ResourceId.parse("execution:missing_session") : context.requestedSessionId();
            return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    ExecutionReadinessStage.PLAN_VALIDATION, missing, session,
                    null, null, "A verified physical plan and readiness context are required");
        }
        List<ExecutionReadinessEvidence> evidence = new ArrayList<>();
        var candidate = plan.candidate();
        var boundGraph = candidate.boundPlan().graph();

        if (!plan.unifiedGraph().equals(candidate.unifiedGraph())
                || plan.evidence().size() != PhysicalizationVerificationCheck.values().length
                || !plan.evidence().keySet().equals(EnumSet.allOf(PhysicalizationVerificationCheck.class))) {
            return refusal(ExecutionReadinessFailureCode.PHYSICAL_PLAN_STALE,
                    ExecutionReadinessStage.PLAN_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "Physical verification evidence or unified graph identity is incomplete");
        }
        add(evidence, ExecutionReadinessVerificationCheck.PHYSICAL_PLAN_VERIFIED,
                "complete physical verification evidence and exact unified graph retained");

        if (!boundGraph.runtimeFingerprint().equals(context.runtimeFingerprint())
                || !context.runtimeFingerprint().equals(context.currentSnapshot().runtimeFingerprint())
                || !context.runtimeFingerprint().equals(candidate.snapshotFingerprint())) {
            return refusal(ExecutionReadinessFailureCode.PHYSICAL_PLAN_STALE,
                    ExecutionReadinessStage.RUNTIME_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "Runtime, binding, physical plan and current snapshot fingerprints differ");
        }
        add(evidence, ExecutionReadinessVerificationCheck.RUNTIME_FINGERPRINT_MATCHES,
                "runtime fingerprint=" + context.runtimeFingerprint());

        for (BoundMachineNode node : boundGraph.boundProcessNodes().values()) {
            if (!context.availableAdapterIds().contains(node.adapterId())) {
                return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        ExecutionReadinessStage.RUNTIME_VALIDATION, plan.id(), context.requestedSessionId(),
                        null, node.adapterId(), "Required Adapter is unavailable");
            }
            if (!context.availableImplementationIds().contains(node.implementationId())) {
                return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        ExecutionReadinessStage.RUNTIME_VALIDATION, plan.id(), context.requestedSessionId(),
                        null, node.implementationId(), "Required implementation is unavailable");
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.IMPLEMENTATION_AND_ADAPTER_AVAILABLE,
                "all " + boundGraph.boundProcessNodes().size() + " bound implementations are available");

        if (!context.authoritativeServerThread()) {
            return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    ExecutionReadinessStage.WORLD_AUTHORITY, plan.id(), context.requestedSessionId(),
                    null, context.dimensionId(), "Execution readiness must run on the authoritative server thread");
        }
        add(evidence, ExecutionReadinessVerificationCheck.AUTHORITATIVE_WORLD_THREAD,
                "authoritative dimension=" + context.dimensionId());

        Set<BlockPos3i> componentCells = componentCells(plan);
        Set<BlockPos3i> requiredCells = requiredCells(plan);
        for (BlockPos3i position : requiredCells) {
            if (!context.loadedPositions().contains(position)
                    || context.currentSnapshot().state(position).orElse(LayoutCellState.UNLOADED)
                    == LayoutCellState.UNLOADED) {
                return refusal(ExecutionReadinessFailureCode.REQUIRED_CHUNK_UNLOADED,
                        ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                        position, null, "A required physical cell is not currently loaded and observed");
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.REQUIRED_CHUNKS_LOADED,
                "loaded observed cells=" + requiredCells.size());

        for (BlockPos3i position : requiredCells) {
            LayoutCellState state = context.currentSnapshot().state(position).orElseThrow();
            if (state != LayoutCellState.REPLACEABLE) {
                return refusal(ExecutionReadinessFailureCode.TARGET_AREA_CHANGED,
                        ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                        position, null, "A placement, clearance or route cell changed after physicalization: " + state);
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.TARGET_AREA_UNCHANGED,
                "every planned cell remains replaceable");

        if (componentCells.size() != plan.placements().stream().mapToInt(value -> value.components().size()).sum()) {
            return refusal(ExecutionReadinessFailureCode.BLOCK_PLACEMENT_BLOCKED,
                    ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "Physical component positions overlap");
        }
        for (PhysicalMachinePlacement placement : plan.placements()) {
            if (!new HashSet<>(placement.clearance()).containsAll(
                    placement.components().stream().map(value -> value.position()).toList())) {
                return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                        placement.anchor(), placement.implementationId(),
                        "Implementation footprint is not contained by its verified clearance");
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.FOOTPRINT_AND_CLEARANCE_VALID,
                "components=" + componentCells.size() + " placements=" + plan.placements().size());

        for (PhysicalRoute route : plan.routes()) {
            if (route.resourceType() != GenericResourceType.ITEM
                    || route.capacity() < route.requiredAmount()
                    || !contiguous(route.positions())) {
                return refusal(ExecutionReadinessFailureCode.ROUTE_CONSTRUCTION_FAILED,
                        ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                        route.positions().get(0), route.id(), "An ITEM route is invalid or lacks capacity");
            }
        }
        for (PhysicalMachinePlacement placement : plan.placements()) {
            if (!contiguous(placement.rotationalPowerRoute())) {
                return refusal(ExecutionReadinessFailureCode.POWER_SOURCE_MISSING,
                        ExecutionReadinessStage.AREA_REVALIDATION, plan.id(), context.requestedSessionId(),
                        placement.anchor(), placement.implementationId(),
                        "A descriptor-derived rotational power route is discontinuous");
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.ITEM_AND_POWER_ROUTES_VALID,
                "item routes=" + plan.routes().size() + " power routes=" + plan.placements().size());

        Map<ResourceId, Long> requiredInputs = requiredInputs(plan);
        for (Map.Entry<ResourceId, Long> requirement : requiredInputs.entrySet()) {
            long available = context.availableInputResources().getOrDefault(requirement.getKey(), 0L);
            if (available < requirement.getValue()) {
                return refusal(ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                        ExecutionReadinessStage.RESOURCE_VALIDATION, plan.id(), context.requestedSessionId(),
                        null, requirement.getKey(), "Required input=" + requirement.getValue()
                                + " available=" + available);
            }
        }
        add(evidence, ExecutionReadinessVerificationCheck.INPUT_RESOURCES_AVAILABLE,
                "initial input kinds=" + requiredInputs.size());

        if (context.rotationalPowerCapacity() < 1) {
            return refusal(ExecutionReadinessFailureCode.POWER_SOURCE_MISSING,
                    ExecutionReadinessStage.RESOURCE_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "No bounded rotational power source is available");
        }
        long requiredStress = plan.placements().stream().mapToLong(PhysicalMachinePlacement::stressImpact).sum();
        if (context.stressCapacity() < requiredStress) {
            return refusal(ExecutionReadinessFailureCode.STRESS_CAPACITY_INSUFFICIENT,
                    ExecutionReadinessStage.RESOURCE_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "Required stress=" + requiredStress + " available=" + context.stressCapacity());
        }
        add(evidence, ExecutionReadinessVerificationCheck.POWER_AND_STRESS_AVAILABLE,
                "power=" + context.rotationalPowerCapacity() + " stress=" + context.stressCapacity());

        int constructionActions = componentCells.size() + routeConstructionCells(plan).size();
        if (constructionActions > context.constructionBudget()) {
            return refusal(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    ExecutionReadinessStage.SAFETY_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "Construction actions=" + constructionActions
                            + " exceed budget=" + context.constructionBudget());
        }
        add(evidence, ExecutionReadinessVerificationCheck.CONSTRUCTION_BUDGET_BOUNDED,
                "construction actions=" + constructionActions + "/" + context.constructionBudget());

        if (!context.rollbackSafe()) {
            return refusal(ExecutionReadinessFailureCode.ROLLBACK_UNSAFE,
                    ExecutionReadinessStage.SAFETY_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "The declared conservative rollback boundary is unavailable");
        }
        add(evidence, ExecutionReadinessVerificationCheck.ROLLBACK_BOUNDARY_SAFE,
                "all pre-resource construction changes are journal-owned and bounded");

        if (!context.journalWritable()) {
            return refusal(ExecutionReadinessFailureCode.JOURNAL_UNAVAILABLE,
                    ExecutionReadinessStage.SAFETY_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "A writable bounded world-change journal is required");
        }
        add(evidence, ExecutionReadinessVerificationCheck.JOURNAL_WRITABLE,
                "bounded journal is writable before session creation");

        if (!context.reloadRecoverySafe()) {
            return refusal(ExecutionReadinessFailureCode.RELOAD_RECOVERY_UNSAFE,
                    ExecutionReadinessStage.SAFETY_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, null, "The typed BUILD-complete recovery boundary is unavailable");
        }
        add(evidence, ExecutionReadinessVerificationCheck.RELOAD_RECOVERY_SAFE,
                "reload may resume only after exact checkpoint reconciliation");

        if (context.activeSessionIds().contains(context.requestedSessionId())) {
            return refusal(ExecutionReadinessFailureCode.SESSION_ALREADY_EXISTS,
                    ExecutionReadinessStage.SESSION_VALIDATION, plan.id(), context.requestedSessionId(),
                    null, context.requestedSessionId(), "The requested session identity is already active");
        }
        add(evidence, ExecutionReadinessVerificationCheck.SESSION_ID_UNIQUE,
                "session=" + context.requestedSessionId());

        if (context.worldClassification() != ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST) {
            return refusal(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                    ExecutionReadinessStage.WORLD_AUTHORITY, plan.id(), context.requestedSessionId(),
                    null, context.dimensionId(), "Only a repository-owned disposable test world is executable");
        }
        add(evidence, ExecutionReadinessVerificationCheck.FORMAL_WORLD_EXCLUDED,
                "world classification=" + context.worldClassification());

        List<String> trace = new ArrayList<>(candidate.trace());
        trace.add("execution:readiness_checks=" + evidence.size());
        trace.add("execution:session=" + context.requestedSessionId());
        trace.add("execution:goal=" + boundGraph.logicalPlan().candidate().goal().target()
                + "x" + boundGraph.logicalPlan().candidate().goal().quantity());
        return new ExecutionReadinessSuccess(new ExecutionReadyPlan(
                context.requestedSessionId(), plan, evidence, trace));
    }

    private static Map<ResourceId, Long> requiredInputs(VerifiedPhysicalPlan plan) {
        Map<ResourceId, Long> required = new LinkedHashMap<>();
        var candidate = plan.candidate().boundPlan().graph().logicalPlan().candidate();
        List<ProcessResource> resources = new ArrayList<>(candidate.rawMaterials());
        resources.addAll(candidate.ownedResourcesUsed());
        resources.stream().sorted(Comparator.comparing(value -> value.resourceId().toString()))
                .forEach(value -> required.merge(value.resourceId(), value.amount(), Math::addExact));
        return required;
    }

    private static Set<BlockPos3i> componentCells(VerifiedPhysicalPlan plan) {
        Set<BlockPos3i> values = new LinkedHashSet<>();
        plan.placements().forEach(placement -> placement.components()
                .forEach(component -> values.add(component.position())));
        return values;
    }

    private static Set<BlockPos3i> routeConstructionCells(VerifiedPhysicalPlan plan) {
        Set<BlockPos3i> values = new LinkedHashSet<>();
        plan.routes().forEach(route -> values.addAll(route.positions()));
        return values;
    }

    private static Set<BlockPos3i> requiredCells(VerifiedPhysicalPlan plan) {
        Set<BlockPos3i> values = new LinkedHashSet<>(componentCells(plan));
        plan.placements().forEach(placement -> {
            values.addAll(placement.clearance());
            values.addAll(placement.rotationalPowerRoute());
            placement.ports().values().forEach(port -> values.add(port.position()));
        });
        values.addAll(routeConstructionCells(plan));
        return values;
    }

    private static boolean contiguous(List<BlockPos3i> values) {
        if (values.size() < 2) return false;
        for (int index = 1; index < values.size(); index++) {
            BlockPos3i left = values.get(index - 1);
            BlockPos3i right = values.get(index);
            int distance = Math.abs(left.x() - right.x())
                    + Math.abs(left.y() - right.y()) + Math.abs(left.z() - right.z());
            if (distance != 1) return false;
        }
        return true;
    }

    private static void add(
            List<ExecutionReadinessEvidence> evidence,
            ExecutionReadinessVerificationCheck check,
            String detail) {
        evidence.add(new ExecutionReadinessEvidence(check, detail));
    }

    private static ExecutionReadinessRefusal refusal(
            ExecutionReadinessFailureCode code,
            ExecutionReadinessStage stage,
            ResourceId planId,
            ResourceId sessionId,
            BlockPos3i position,
            ResourceId resourceId,
            String detail) {
        return new ExecutionReadinessRefusal(new ExecutionReadinessFailure(
                code, stage, planId, sessionId, Optional.ofNullable(position),
                Optional.ofNullable(resourceId), detail,
                Map.of("bounded", "true", "worldMutation", "false", "sessionCreated", "false"),
                List.of("execution-readiness:" + code.name().toLowerCase()),
                "Refresh the exact typed observations, correct the reported safety condition, and rerun readiness"));
    }
}
