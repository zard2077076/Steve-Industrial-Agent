package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinMixerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinMixerPlacement;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import dev.stevecreate.agent.core.plan.BasinPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinPressPlacement;
import dev.stevecreate.agent.core.plan.BasinPressPlan;
import dev.stevecreate.agent.core.plan.CompactingProcessSpec;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.CrushingProcessSpec;
import dev.stevecreate.agent.core.plan.CrushingWheelGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelPlacement;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.DeployerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.DeployerInteractionPolicy;
import dev.stevecreate.agent.core.plan.DeployerPlacement;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import dev.stevecreate.agent.core.plan.DeployingProcessSpec;
import dev.stevecreate.agent.core.plan.FanProcessingGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import dev.stevecreate.agent.core.plan.FanProcessingPlacement;
import dev.stevecreate.agent.core.plan.FanProcessingPlan;
import dev.stevecreate.agent.core.plan.FanProcessingSpec;
import dev.stevecreate.agent.core.plan.MillingProcessSpec;
import dev.stevecreate.agent.core.plan.MixingProcessSpec;
import dev.stevecreate.agent.core.plan.HeldItemDisposition;
import dev.stevecreate.agent.core.plan.CuttingProcessSpec;
import dev.stevecreate.agent.core.plan.MechanicalSawGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawPlacement;
import dev.stevecreate.agent.core.plan.MechanicalSawPlan;
import dev.stevecreate.agent.core.plan.PlanAnchor;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.PlanBlockFacing;
import dev.stevecreate.agent.core.plan.PressingProcessSpec;
import dev.stevecreate.agent.core.plan.ResolvedPlanPlacement;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
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
    private static final ResourceId CRUSHING = id("create:crushing");
    private static final ResourceId CUTTING = id("create:cutting");
    private static final ResourceId COMPACTING = id("create:compacting");
    private static final ResourceId MIXING = id("create:mixing");
    private static final ResourceId DEPLOYING = id("create:deploying");

    MaterializationResult materialize(ExecutionReadyPlan ready) {
        return materialize(ready, null);
    }

    MaterializationResult materialize(
            ExecutionReadyPlan ready,
            CreateV606VerifiedExecutionMetadata metadata) {
        Objects.requireNonNull(ready, "ready");
        if (metadata != null
                && (!metadata.sessionId().equals(ready.sessionId())
                || !metadata.runtimeFingerprint().equals(
                        ready.physicalPlan().candidate().boundPlan().graph()
                                .runtimeFingerprint()))) {
            return failure(
                    "Verified recipe metadata differs from the ready session/runtime");
        }
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
            RecipeHeatTier heatTier = metadata == null
                    ? RecipeHeatTier.NONE
                    : metadata.heatTier(bound.stepId());
            MaterializationResult result = materializeNode(
                    bound, placement, heatTier,
                    metadata == null ? List.of() : metadata.fluidInputs(bound.stepId()));
            if (result instanceof MaterializationFailure) return result;
            nodes.add(((MaterializationSuccess) result).nodes().get(0));
        }
        if (nodes.isEmpty()) return failure("Execution materialization produced no process nodes");
        return new MaterializationSuccess(nodes);
    }

    private MaterializationResult materializeNode(
            BoundMachineNode bound,
            PhysicalMachinePlacement placement,
            RecipeHeatTier heatTier,
            List<ProcessResource> fluidInputs) {
        List<ProcessResource> inputs = bound.quantityConversion().inputs();
        List<ProcessResource> outputs = bound.quantityConversion().outputs();
        if (inputs.isEmpty() || outputs.size() != 1
                || inputs.stream().anyMatch(value ->
                        value.resourceType() != GenericResourceType.ITEM)
                || outputs.get(0).resourceType() != GenericResourceType.ITEM
                || bound.quantityConversion().byproducts().stream()
                        .anyMatch(value -> value.resourceType() != GenericResourceType.ITEM)) {
            return failure(
                    "Initial execution supports one or more ITEM inputs, one ITEM output and ITEM-only byproducts");
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
                    CreateRuntimeMachineImplementationCatalog
                            .BASIN_MIXER_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(MIXING)) {
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure(
                            "C-08 deterministic Phase I rejects byproducts");
                }
                List<ProcessResource> processInputs = new ArrayList<>(inputs);
                processInputs.addAll(fluidInputs);
                BasinMixerPlan plan = BasinMixerPlan.forProcess(
                        anchor,
                        new MixingProcessSpec(
                                bound.recipeId(),
                                processInputs,
                                outputs.get(0).resourceId(),
                                outputCount,
                                heatTier == RecipeHeatTier.HEATED
                                        ? BasinHeatMode.HEATED
                                        : BasinHeatMode.NONE,
                                400,
                                2_000));
                String mismatch = compareMixing(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(
                        new BasinMixerNode(
                                bound, placement, plan,
                                BasinMixerGenericExecutionPlan.from(plan),
                                BasinMixerGenericExecutionPlan
                                        .verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog
                            .BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(COMPACTING)) {
                if (heatTier != RecipeHeatTier.NONE) {
                    return failure(
                            "C-09 remains unheated and refuses recipe heat metadata");
                }
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure(
                            "C-09 deterministic Phase I rejects byproducts");
                }
                List<ProcessResource> processInputs = new ArrayList<>(inputs);
                processInputs.addAll(fluidInputs);
                BasinPressPlan plan = BasinPressPlan.forProcess(
                        anchor,
                        new CompactingProcessSpec(
                                bound.recipeId(),
                                processInputs,
                                outputs.get(0).resourceId(),
                                outputCount,
                                BasinHeatMode.NONE,
                                400,
                                2_000));
                String mismatch = compareCompacting(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(
                        new BasinPressNode(
                                bound, placement, plan,
                                BasinPressGenericExecutionPlan.from(plan),
                                BasinPressGenericExecutionPlan
                                        .verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog
                            .DEPLOYER_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(DEPLOYING)) {
                if (!bound.quantityConversion().byproducts().isEmpty()
                        || bound.recipeInputs().size() != 2) {
                    return failure(
                            "C-10 deterministic Phase I requires one processed item, one exact held item and no byproducts");
                }
                var processed = bound.recipeInputs().get(0);
                var held = bound.recipeInputs().get(1);
                DeployerPlan plan = DeployerPlan.forProcess(
                        anchor,
                        new DeployingProcessSpec(
                                bound.recipeId(),
                                processed.selectedResource(),
                                Math.toIntExact(processed.amount()),
                                held.selectedResource(),
                                HeldItemDisposition.CONSUMED,
                                outputs.get(0).resourceId(),
                                outputCount,
                                DeployerInteractionPolicy
                                        .safeDepotItemOnly(),
                                400,
                                2_000));
                String mismatch = compareDeploying(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(
                        new DeployerNode(
                                bound,
                                placement,
                                plan,
                                DeployerGenericExecutionPlan.from(plan),
                                DeployerGenericExecutionPlan
                                        .verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog.CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(CRUSHING)) {
                if (inputs.size() != 1) {
                    return failure("C-05 requires exactly one ITEM input");
                }
                CrushingWheelPlan plan = CrushingWheelPlan.forProcess(
                        anchor, new CrushingProcessSpec(
                                bound.recipeId(), bound.recipeType(),
                                inputs.get(0).resourceId(), inputCount,
                                outputs.get(0).resourceId(), outputCount,
                                bound.quantityConversion().byproducts(),
                                400, 2_400));
                String mismatch = compareCrushing(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(new CrusherNode(
                        bound, placement, plan,
                        CrushingWheelGenericExecutionPlan.from(plan),
                        CrushingWheelGenericExecutionPlan.verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog.SAW_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(CUTTING)) {
                if (inputs.size() != 1) {
                    return failure("C-07 requires exactly one ITEM input");
                }
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure("The C-07 Phase I handler rejects probabilistic byproducts");
                }
                MechanicalSawPlan plan = MechanicalSawPlan.forProcess(
                        anchor, new CuttingProcessSpec(
                                bound.recipeId(), bound.recipeType(),
                                inputs.get(0).resourceId(), inputCount,
                                outputs.get(0).resourceId(), outputCount,
                                400, 1_200));
                String mismatch = compareSaw(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(new SawNode(
                        bound, placement, plan,
                        MechanicalSawGenericExecutionPlan.from(plan),
                        MechanicalSawGenericExecutionPlan.verificationRules(plan))));
            }
            FanProcessingMode fanMode = fanMode(bound);
            if (fanMode != null) {
                if (inputs.size() != 1) {
                    return failure("C-06 requires exactly one ITEM input");
                }
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure("The C-06 Phase I handler rejects probabilistic byproducts");
                }
                FanProcessingPlan plan = FanProcessingPlan.forProcess(
                        anchor, new FanProcessingSpec(
                                bound.recipeId(), bound.recipeType(),
                                inputs.get(0).resourceId(), inputCount,
                                outputs.get(0).resourceId(), outputCount,
                                400, 1_200));
                if (plan.process().mode() != fanMode) {
                    return failure("C-06 implementation and recipe medium differ");
                }
                String mismatch = compareFan(placement, plan);
                if (mismatch != null) return failure(mismatch);
                return new MaterializationSuccess(List.of(new FanNode(
                        bound, placement, plan,
                        FanProcessingGenericExecutionPlan.from(plan),
                        FanProcessingGenericExecutionPlan.verificationRules(plan))));
            }
            if (bound.implementationId().equals(
                    CreateRuntimeMachineImplementationCatalog.MILLSTONE_IMPLEMENTATION_ID)
                    && bound.recipeType().equals(MILLING)) {
                if (inputs.size() != 1) {
                    return failure("C-03 requires exactly one ITEM input");
                }
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure("The accepted C-03 handler cannot execute byproducts");
                }
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
                if (inputs.size() != 1) {
                    return failure("C-04 requires exactly one ITEM input");
                }
                if (!bound.quantityConversion().byproducts().isEmpty()) {
                    return failure("The accepted C-04 handler cannot execute byproducts");
                }
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

    private static String compareCrushing(
            PhysicalMachinePlacement placement,
            CrushingWheelPlan plan) {
        Map<String, ResolvedGeometryComponent> components = components(placement);
        if (components.size() != plan.placements().size()) {
            return "Crushing-wheel component count changed";
        }
        for (CrushingWheelPlacement expected : plan.placements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "Crushing-wheel physical component differs from typed role " + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null && actual.blockState().containsKey("axis")
                    && !axis.equals(actual.blockState().get("axis"))) {
                return "Crushing-wheel physical axis differs for " + role;
            }
            if (facing != null && actual.blockState().containsKey("facing")
                    && !facing.equals(actual.blockState().get("facing"))) {
                return "Crushing-wheel physical facing differs for " + role;
            }
        }
        return null;
    }

    private static String compareCompacting(
            PhysicalMachinePlacement placement,
            BasinPressPlan plan) {
        Map<String, ResolvedGeometryComponent> components =
                components(placement);
        if (components.size() != plan.placements().size()) {
            return "C-09 Basin/Press component count changed";
        }
        for (BasinPressPlacement expected : plan.placements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "C-09 physical component differs from typed role "
                        + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null && actual.blockState().containsKey("axis")
                    && !axis.equals(actual.blockState().get("axis"))) {
                return "C-09 physical axis differs for " + role;
            }
            if (facing != null && actual.blockState().containsKey("facing")
                    && !facing.equals(actual.blockState().get("facing"))) {
                return "C-09 physical facing differs for " + role;
            }
        }
        return null;
    }

    private static String compareMixing(
            PhysicalMachinePlacement placement,
            BasinMixerPlan plan) {
        Map<String, ResolvedGeometryComponent> components =
                components(placement);
        if (components.size() != plan.placements().size()) {
            return "C-08 Basin/Mixer component count changed";
        }
        for (BasinMixerPlacement expected : plan.placements()) {
            String role =
                    expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual =
                    components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(
                            expected.position())) {
                return "C-08 physical component differs from typed role "
                        + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null
                    && actual.blockState().containsKey("axis")
                    && !axis.equals(
                            actual.blockState().get("axis"))) {
                return "C-08 physical axis differs for " + role;
            }
            if (facing != null
                    && actual.blockState().containsKey("facing")
                    && !facing.equals(
                            actual.blockState().get("facing"))) {
                return "C-08 physical facing differs for " + role;
            }
        }
        return null;
    }

    private static String compareDeploying(
            PhysicalMachinePlacement placement,
            DeployerPlan plan) {
        Map<String, ResolvedGeometryComponent> components =
                components(placement);
        if (components.size() != plan.placements().size()) {
            return "C-10 Deployer component count changed";
        }
        for (DeployerPlacement expected : plan.placements()) {
            String role =
                    expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(
                            expected.position())) {
                return "C-10 physical component differs from typed role "
                        + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null
                    && actual.blockState().containsKey("axis")
                    && !axis.equals(
                            actual.blockState().get("axis"))) {
                return "C-10 physical axis differs for " + role;
            }
            if (facing != null
                    && actual.blockState().containsKey("facing")
                    && !facing.equals(
                            actual.blockState().get("facing"))) {
                return "C-10 physical facing differs for " + role;
            }
        }
        return null;
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

    private static String compareSaw(
            PhysicalMachinePlacement placement,
            MechanicalSawPlan plan) {
        Map<String, ResolvedGeometryComponent> components = components(placement);
        if (components.size() != plan.placements().size()) {
            return "Mechanical-saw component count changed";
        }
        for (MechanicalSawPlacement expected : plan.placements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "Mechanical-saw physical component differs from typed role " + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null && actual.blockState().containsKey("axis")
                    && !axis.equals(actual.blockState().get("axis"))) {
                return "Mechanical-saw physical axis differs for " + role;
            }
            if (facing != null && actual.blockState().containsKey("facing")
                    && !facing.equals(actual.blockState().get("facing"))) {
                return "Mechanical-saw physical facing differs for " + role;
            }
        }
        return null;
    }

    private static String compareFan(
            PhysicalMachinePlacement placement,
            FanProcessingPlan plan) {
        Map<String, ResolvedGeometryComponent> components = components(placement);
        if (components.size() != plan.placements().size()) {
            return "Fan-processing component count changed";
        }
        for (FanProcessingPlacement expected : plan.placements()) {
            String role = expected.role().name().toLowerCase(Locale.ROOT);
            ResolvedGeometryComponent actual = components.get(role);
            if (actual == null
                    || !actual.blockId().equals(expected.blockId())
                    || !actual.position().equals(expected.position())) {
                return "Fan-processing physical component differs from typed role " + role;
            }
            String axis = state(expected.rotationAxis());
            String facing = state(expected.facing());
            if (axis != null && actual.blockState().containsKey("axis")
                    && !axis.equals(actual.blockState().get("axis"))) {
                return "Fan-processing physical axis differs for " + role;
            }
            if (facing != null && actual.blockState().containsKey("facing")
                    && !facing.equals(actual.blockState().get("facing"))) {
                return "Fan-processing physical facing differs for " + role;
            }
        }
        return null;
    }

    private static FanProcessingMode fanMode(BoundMachineNode bound) {
        ResourceId implementation = bound.implementationId();
        if (implementation.equals(
                CreateRuntimeMachineImplementationCatalog.FAN_WASHING_IMPLEMENTATION_ID)) {
            return FanProcessingMode.WASHING;
        }
        if (implementation.equals(
                CreateRuntimeMachineImplementationCatalog.FAN_SMOKING_IMPLEMENTATION_ID)) {
            return FanProcessingMode.SMOKING;
        }
        if (implementation.equals(
                CreateRuntimeMachineImplementationCatalog.FAN_HAUNTING_IMPLEMENTATION_ID)) {
            return FanProcessingMode.HAUNTING;
        }
        if (implementation.equals(
                CreateRuntimeMachineImplementationCatalog.FAN_BLASTING_IMPLEMENTATION_ID)) {
            return FanProcessingMode.BLASTING;
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

    sealed interface ExecutableNode
            permits BasinMixerNode, BasinPressNode, CrusherNode, DeployerNode, FanNode, MillstoneNode,
                    PressNode, SawNode {
        BoundMachineNode boundNode();
        PhysicalMachinePlacement physicalPlacement();
        GenericExecutionPlan genericPlan();
        Map<ResourceId, GenericVerificationRule> verificationRules();
    }

    record CrusherNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            CrushingWheelPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules) implements ExecutableNode {}

    record BasinPressNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            BasinPressPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules)
            implements ExecutableNode {}

    record BasinMixerNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            BasinMixerPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules)
            implements ExecutableNode {}

    record DeployerNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            DeployerPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules)
            implements ExecutableNode {}

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

    record SawNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            MechanicalSawPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules) implements ExecutableNode {}

    record FanNode(
            BoundMachineNode boundNode,
            PhysicalMachinePlacement physicalPlacement,
            FanProcessingPlan plan,
            GenericExecutionPlan genericPlan,
            Map<ResourceId, GenericVerificationRule> verificationRules) implements ExecutableNode {}

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
