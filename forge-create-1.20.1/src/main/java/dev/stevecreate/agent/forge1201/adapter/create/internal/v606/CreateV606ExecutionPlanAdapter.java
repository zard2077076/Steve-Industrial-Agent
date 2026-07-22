package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.MillingProcessSpec;
import dev.stevecreate.agent.core.plan.PlanAnchor;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.PlanBlockFacing;
import dev.stevecreate.agent.core.plan.PressingProcessSpec;
import dev.stevecreate.agent.core.plan.ResolvedPlanPlacement;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.GenericVerificationRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Exact v606 bridge from verified physical nodes to the existing C-03/C-04 runner plans. */
final class CreateV606ExecutionPlanAdapter {
    private static final ResourceId MILLING = id("create:milling");
    private static final ResourceId PRESSING = id("create:pressing");

    MaterializationResult materialize(ExecutionReadyPlan ready) {
        Objects.requireNonNull(ready, "ready");
        Map<ResourceId, PhysicalMachinePlacement> placements = new LinkedHashMap<>();
        ready.physicalPlan().placements().forEach(value -> placements.put(value.logicalNodeId(), value));
        Map<ResourceId, BoundMachineNode> byStep = new LinkedHashMap<>();
        ready.physicalPlan().candidate().boundPlan().graph().boundProcessNodes().values()
                .forEach(value -> byStep.put(value.stepId(), value));
        List<ExecutableNode> nodes = new ArrayList<>();
        for (ResourceId stepId : ready.physicalPlan().candidate().boundPlan().graph()
                .logicalPlan().candidate().processingOrder()) {
            BoundMachineNode bound = byStep.get(stepId);
            if (bound == null) return failure("Processing order references an unbound step " + stepId);
            PhysicalMachinePlacement placement = placements.get(bound.logicalNodeId());
            if (placement == null) return failure("Bound process node has no physical placement " + bound.logicalNodeId());
            MaterializationResult result = materializeNode(bound, placement);
            if (result instanceof MaterializationFailure) return result;
            nodes.add(((MaterializationSuccess) result).nodes().get(0));
        }
        if (nodes.isEmpty()) return failure("Execution materialization produced no process nodes");
        return new MaterializationSuccess(nodes);
    }

    private MaterializationResult materializeNode(
            BoundMachineNode bound,
            PhysicalMachinePlacement placement) {
        List<ProcessResource> inputs = bound.quantityConversion().inputs();
        List<ProcessResource> outputs = bound.quantityConversion().outputs();
        if (inputs.size() != 1 || outputs.size() != 1
                || inputs.get(0).resourceType() != GenericResourceType.ITEM
                || outputs.get(0).resourceType() != GenericResourceType.ITEM
                || !bound.quantityConversion().byproducts().isEmpty()) {
            return failure("Initial execution supports exactly one ITEM input/output and no byproduct");
        }
        int inputCount;
        int outputCount;
        try {
            inputCount = Math.toIntExact(inputs.get(0).amount());
            outputCount = Math.toIntExact(outputs.get(0).amount());
        } catch (ArithmeticException exception) {
            return failure("Process quantity exceeds the bounded Create handler count");
        }
        PlanAnchor anchor = new PlanAnchor(placement.anchor(), placement.orientation());
        try {
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(MILLING)) {
                WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.forProcess(
                        anchor, new MillingProcessSpec(
                                bound.recipeId(), bound.recipeType(), inputs.get(0).resourceId(), inputCount,
                                outputs.get(0).resourceId(), outputCount, 400, 2_400));
                String mismatch = compareMillstone(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(new MillstoneNode(
                        bound, placement, plan, WaterWheelMillstoneGenericExecutionPlan.from(plan),
                        WaterWheelMillstoneGenericExecutionPlan.verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog.PRESS_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(PRESSING)) {
                BeltPressPlan plan = BeltPressPlan.forProcess(
                        anchor, new PressingProcessSpec(
                                bound.recipeId(), bound.recipeType(), inputs.get(0).resourceId(), inputCount,
                                outputs.get(0).resourceId(), outputCount, 400, 2_400));
                String mismatch = comparePress(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(new PressNode(
                        bound, placement, plan, BeltPressGenericExecutionPlan.from(plan),
                        BeltPressGenericExecutionPlan.verificationRules(plan))));
            }
        } catch (IllegalArgumentException exception) {
            return failure("Typed Create process materialization was rejected: " + exception.getMessage());
        }
        return failure("No accepted v606 handler exists for " + bound.implementationId()
                + " / " + bound.recipeType());
    }

    private static String compareMillstone(
            PhysicalMachinePlacement placement,
            WaterWheelMillstonePlan plan) {
        Map<String, ResolvedGeometryComponent> components = components(placement);
        if (components.size() != plan.placements().size()) return "Millstone component count changed";
        for (ResolvedPlanPlacement expected : plan.placements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "Millstone physical component differs from typed role " + role;
            }
            String axis = state(expected.axis());
            if (axis != null && !axis.equals(actual.blockState().get("axis"))) {
                return "Millstone physical axis differs for " + role;
            }
        }
        return null;
    }

    private static String comparePress(
            PhysicalMachinePlacement placement,
            BeltPressPlan plan) {
        Map<String, ResolvedGeometryComponent> components = components(placement);
        if (components.size() != plan.finalPlacements().size()) return "Press component count changed";
        for (BeltPressPlacement expected : plan.finalPlacements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "Press physical component differs from typed role " + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null && actual.blockState().containsKey("axis")
                    && !axis.equals(actual.blockState().get("axis"))) {
                return "Press physical axis differs for " + role;
            }
            if (facing != null && actual.blockState().containsKey("facing")
                    && !facing.equals(actual.blockState().get("facing"))) {
                return "Press physical facing differs for " + role;
            }
        }
        return null;
    }

    private static Map<String, ResolvedGeometryComponent> components(PhysicalMachinePlacement placement) {
        Map<String, ResolvedGeometryComponent> values = new LinkedHashMap<>();
        placement.components().stream().sorted(Comparator.comparing(value -> value.roleId().toString()))
                .forEach(value -> {
                    String path = value.roleId().path();
                    values.put(path.substring(path.lastIndexOf('/') + 1), value);
                });
        return values;
    }

    private static String state(PlanBlockAxis value) {
        return value == PlanBlockAxis.NONE ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static String state(PlanBlockFacing value) {
        return value == PlanBlockFacing.NONE ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static MaterializationFailure failure(String detail) {
        return new MaterializationFailure(ExecutionReadinessFailureCode.EXECUTION_NOT_READY, detail);
    }

    sealed interface MaterializationResult permits MaterializationSuccess, MaterializationFailure {}

    record MaterializationSuccess(List<ExecutableNode> nodes) implements MaterializationResult {
        MaterializationSuccess {
            nodes = List.copyOf(nodes);
            if (nodes.isEmpty()) throw new IllegalArgumentException("Executable nodes are empty");
        }
    }

    record MaterializationFailure(
            ExecutionReadinessFailureCode code,
            String detail) implements MaterializationResult {
        MaterializationFailure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail is blank");
        }
    }

    sealed interface ExecutableNode permits MillstoneNode, PressNode {
        BoundMachineNode boundNode();
        PhysicalMachinePlacement physicalPlacement();
        GenericExecutionPlan genericPlan();
        Map<ResourceId, GenericVerificationRule> verificationRules();
    }

    record MillstoneNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            WaterWheelMillstonePlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules) implements ExecutableNode {}

    record PressNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            BeltPressPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules) implements ExecutableNode {}

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
