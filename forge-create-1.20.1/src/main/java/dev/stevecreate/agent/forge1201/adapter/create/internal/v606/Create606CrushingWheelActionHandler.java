package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crusher.CrushingRecipe;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CrushingWheelEvidence;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.CrushingProcessSpec;
import dev.stevecreate.agent.core.plan.CrushingWheelGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelPlacement;
import dev.stevecreate.agent.core.plan.CrushingWheelPlan;
import dev.stevecreate.agent.core.plan.CrushingWheelRole;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.PlanBlockFacing;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 real-world actions behind the bounded C-05 runner. */
final class Create606CrushingWheelActionHandler implements StepActionHandler {
    private static final ResourceId INTEGER_VALUE =
            id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE =
            id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE =
            id("steve_industrial:value/recipe");
    private static final ResourceId KINETIC_VALUE =
            id("create:value/signed_rpm_capacity_load");
    private static final ResourceId PROBABILITY_VALUE =
            id("create:value/probability_per_million");
    private static final ResourceId TICK_RANGE_VALUE =
            id("steve_industrial:value/tick_range");

    private final ServerLevel level;
    private final CrushingWheelPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;

    private int placementIndex;
    private long feedTick = -1;
    private UUID inputEntityId;
    private CrushingRecipe crushingRecipe;
    private Map<ResourceId, Integer> probabilities = Map.of();
    private CrushingWheelEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606CrushingWheelActionHandler(
            ServerLevel level,
            CrushingWheelPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, null);
    }

    Create606CrushingWheelActionHandler(
            ServerLevel level,
            CrushingWheelPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"));
    }

    Create606CrushingWheelActionHandler(
            ServerLevel level,
            CrushingWheelPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal journal,
            int placementIndex,
            Create606WorldResourceBuffer resourceBuffer) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (placementIndex < 0 || placementIndex > plan.placements().size()) {
            throw new IllegalArgumentException(
                    "Recovered C-05 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level, sessionId, Objects.requireNonNull(journal, "journal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(
            ServerLevel level,
            CrushingWheelPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (CrushingWheelPlacement placement : plan.placements()) {
            Block block = registeredBlock(placement.blockId());
            if (block == null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing runtime block for " + placement));
            }
            BlockPos target = position(placement.position());
            if (!level.getBlockState(target).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Plan target is not replaceable at " + placement.position()));
            }
            String stateFailure = validateTypedState(placement, block.defaultBlockState());
            if (stateFailure != null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED, stateFailure));
            }
        }
        if (!level.getBlockState(position(plan.controllerPosition())).canBeReplaced()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "Crushing controller gap is obstructed at " + plan.controllerPosition()));
        }
        if (!level.getBlockState(position(plan.inputSpawnPosition())).canBeReplaced()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "Crushing input lane is obstructed at " + plan.inputSpawnPosition()));
        }
        return Optional.empty();
    }

    @Override
    public ActionHandlerResult invoke(
            StepActionDescriptor action,
            StepRunnerContext context) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(context, "context");
        if (!level.getServer().isSameThread()) {
            return fail(
                    AdapterFailureCode.WRONG_THREAD,
                    "C-05 action handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(
                    AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(
                CrushingWheelGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-05 action descriptor did not match the registered v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "C-05 preflight area unloaded during execution: "
                            + chunk.x + "," + chunk.z);
        }

        if (matches(action, context,
                CrushingWheelGenericExecutionPlan.BUILD_OPERATION_ID,
                CrushingWheelGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        if (matches(action, context,
                CrushingWheelGenericExecutionPlan.POWER_OPERATION_ID,
                CrushingWheelGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(action, context,
                CrushingWheelGenericExecutionPlan.FEED_OPERATION_ID,
                CrushingWheelGenericExecutionPlan.FEED_STEP_ID)) {
            return feedController(context);
        }
        if (matches(action, context,
                CrushingWheelGenericExecutionPlan.PROCESS_OPERATION_ID,
                CrushingWheelGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-05 operation and step identity did not match the fixed generic plan");
    }

    int placementIndex() {
        return placementIndex;
    }

    Optional<CrushingWheelEvidence> completedEvidence() {
        return Optional.ofNullable(completedEvidence);
    }

    Optional<FailureDetail> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    Optional<ChunkPos> firstUnloadedChunk() {
        return preflightChunks.stream()
                .filter(chunk -> !level.hasChunk(chunk.x, chunk.z))
                .findFirst();
    }

    WorldChangeJournal worldChangeJournal() {
        return worldChanges.snapshot();
    }

    void stabilizeRecoveryAfterStates() {
        worldChanges.stabilizeBlockEntityAfterStates();
    }

    RollbackReport rollback() {
        return worldChanges.rollback();
    }

    private ActionHandlerResult buildOne(StepRunnerContext context) {
        if (placementIndex >= plan.placements().size()) {
            return buildSucceeded(context);
        }
        CrushingWheelPlacement placement = plan.placements().get(placementIndex);
        BlockPos target = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(target);
        WorldBlockSnapshot controllerBefore = null;
        List<BlockPos> flowTargets = List.of();
        List<WorldBlockSnapshot> flowBefore = List.of();
        if (placement.role() == CrushingWheelRole.LEFT_WATER_SOURCE
                || placement.role() == CrushingWheelRole.RIGHT_WATER_SOURCE) {
            flowTargets = List.of(target.below(), target.below(2));
            flowBefore = flowTargets.stream().map(worldChanges::capture).toList();
        }
        if (placement.role() == CrushingWheelRole.RIGHT_WHEEL) {
            controllerBefore = worldChanges.capture(position(plan.controllerPosition()));
        }
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-05 placement target changed at " + placement.position());
        }
        Block block = registeredBlock(placement.blockId());
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Runtime C-05 block disappeared: " + placement.blockId());
        }
        BlockState state = applyTypedState(placement, block.defaultBlockState());
        if (!level.setBlockAndUpdate(target, state)) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected C-05 placement " + placement);
        }
        worldChanges.recordBlockChange(context, target, before);
        for (int index = 0; index < flowTargets.size(); index++) {
            BlockPos flowTarget = flowTargets.get(index);
            if (!level.getBlockState(flowTarget).canBeReplaced()
                    || !level.setBlockAndUpdate(flowTarget,
                    Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock())) {
                return fail(AdapterFailureCode.PLACEMENT_FAILED,
                        "World rejected C-05 atomic bounded flow at "
                                + flowTarget.toShortString());
            }
            worldChanges.recordBlockChange(context, flowTarget, flowBefore.get(index));
        }
        if (placement.role() == CrushingWheelRole.RIGHT_WHEEL) {
            ensureControllerCreated();
            BlockPos controller = position(plan.controllerPosition());
            worldChanges.recordBlockChange(context, controller, controllerBefore);
            if (!AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(
                    level.getBlockState(controller))) {
                return fail(
                        AdapterFailureCode.PLACEMENT_FAILED,
                        "Create did not create the bounded crushing controller");
            }
        }
        String mismatch = placementMismatch(placement);
        if (mismatch != null) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
        }
        placementIndex++;
        if (placementIndex < plan.placements().size()) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private void ensureControllerCreated() {
        CrushingWheelPlacement left = plan.placement(CrushingWheelRole.LEFT_WHEEL);
        CrushingWheelPlacement right = plan.placement(CrushingWheelRole.RIGHT_WHEEL);
        BlockPos leftPos = position(left.position());
        BlockState leftState = level.getBlockState(leftPos);
        if (!(leftState.getBlock() instanceof CrushingWheelBlock wheel)) return;
        int dx = Integer.compare(right.position().x(), left.position().x());
        int dz = Integer.compare(right.position().z(), left.position().z());
        Direction towardPair = Direction.getNearest(dx, 0, dz);
        wheel.updateControllers(leftState, level, leftPos, towardPair);
    }

    private ActionHandlerResult buildSucceeded(StepRunnerContext context) {
        for (CrushingWheelPlacement expected : plan.placements()) {
            String mismatch = placementMismatch(expected);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
            }
        }
        if (!AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(
                level.getBlockState(position(plan.controllerPosition())))) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-05 runtime controller is absent after paired-wheel construction");
        }
        return success(evidence(
                id("create:c05/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                CrushingWheelGenericExecutionPlan.BUILD_EVIDENCE_REQUIREMENT,
                context,
                CrushingWheelGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.placements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(StepRunnerContext context) {
        AdapterResult<KineticEvidence> result = captureKinetics();
        if (result instanceof AdapterResult.Failure<KineticEvidence> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        KineticEvidence observed =
                ((AdapterResult.Success<KineticEvidence>) result).value();
        String controllerFailure = controllerFailure(true);
        if (controllerFailure != null) {
            if (controllerFailure.startsWith("settling:")) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(AdapterFailureCode.PROCESSING_FAILED, controllerFailure);
        }
        return success(evidence(
                id("create:c05/observation/opposed_power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                CrushingWheelGenericExecutionPlan.POWER_EVIDENCE_REQUIREMENT,
                context,
                CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                KINETIC_VALUE,
                kineticValue(observed),
                KINETIC_VALUE,
                "opposed_equal_wheels;controller=valid_down;stressLoad<=stressCapacity"));
    }

    private ActionHandlerResult feedController(StepRunnerContext context) {
        AdapterResult<KineticEvidence> speedResult = captureKinetics();
        if (speedResult instanceof AdapterResult.Failure<KineticEvidence> failure) {
            return fail(failure.code(), failure.detail());
        }
        String controllerFailure = controllerFailure(true);
        if (controllerFailure != null) {
            return fail(AdapterFailureCode.PROCESSING_FAILED, controllerFailure);
        }
        CrushingWheelControllerBlockEntity controller = controller();
        if (controller == null || controller.isOccupied()
                || !controller.inventory.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-05 controller was absent or not empty before feed");
        }
        ChestBlockEntity chest = outputChest();
        HopperBlockEntity hopper = outputHopper();
        if (chest == null || hopper == null || !chest.isEmpty() || !hopper.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-05 hopper/chest output path was absent or not empty before feed");
        }

        CrushingProcessSpec spec = plan.process();
        Item inputItem = registeredItem(spec.inputItem());
        if (inputItem == null) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Missing runtime C-05 input item " + spec.inputItem());
        }
        ItemStack offered = new ItemStack(inputItem, spec.inputCount());
        ItemStackHandler recipeInput = new ItemStackHandler(1);
        recipeInput.setStackInSlot(0, offered.copy());
        Optional<CrushingRecipe> recipe = level.getRecipeManager().getRecipeFor(
                AllRecipeTypes.CRUSHING.getType(),
                new RecipeWrapper(recipeInput),
                level);
        if (recipe.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live Create crushing recipe for " + spec.inputItem());
        }
        crushingRecipe = recipe.orElseThrow();
        String recipeFailure = validateLiveRecipe(crushingRecipe, spec);
        if (recipeFailure != null) {
            return fail(AdapterFailureCode.RECIPE_NOT_FOUND, recipeFailure);
        }
        probabilities = probabilityEvidence(crushingRecipe, spec);

        if (resourceBuffer != null) {
            AdapterResult<ItemStack> extracted =
                    resourceBuffer.extractExact(spec.inputItem(), spec.inputCount());
            if (extracted instanceof AdapterResult.Failure<ItemStack> failure) {
                return fail(failure.code(), failure.detail());
            }
            offered = ((AdapterResult.Success<ItemStack>) extracted).value();
        }
        BlockPos3i spawn = plan.inputSpawnPosition();
        ItemEntity entity = new ItemEntity(
                level,
                spawn.x() + 0.5D,
                spawn.y() + 0.05D,
                spawn.z() + 0.5D,
                offered);
        entity.setDeltaMovement(0.0D, -0.05D, 0.0D);
        if (!level.addFreshEntity(entity)) {
            if (resourceBuffer != null) resourceBuffer.insertExact(offered);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "World rejected the reserved C-05 input entity");
        }
        inputEntityId = entity.getUUID();
        feedTick = level.getGameTime();
        worldChanges.recordInjectedResource(
                context, position(spawn), spec.genericSpec().inputs().get(0));
        String stack = spec.inputItem() + " x" + spec.inputCount();
        return success(evidence(
                id("create:c05/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                CrushingWheelGenericExecutionPlan.FEED_EVIDENCE_REQUIREMENT,
                context,
                CrushingWheelGenericExecutionPlan.INPUT_BOUNDARY_NODE_ID,
                ITEM_STACK_VALUE,
                stack + " entity=" + inputEntityId,
                ITEM_STACK_VALUE,
                stack));
    }

    private ActionHandlerResult observeProcessing(StepRunnerContext context) {
        AdapterResult<KineticEvidence> speedResult = captureKinetics();
        if (speedResult instanceof AdapterResult.Failure<KineticEvidence> failure) {
            return fail(failure.code(), failure.detail());
        }
        KineticEvidence kinetics =
                ((AdapterResult.Success<KineticEvidence>) speedResult).value();
        String controllerFailure = controllerFailure(true);
        if (controllerFailure != null) {
            return fail(AdapterFailureCode.PROCESSING_FAILED, controllerFailure);
        }
        CrushingWheelControllerBlockEntity controller = controller();
        ChestBlockEntity chest = outputChest();
        HopperBlockEntity hopper = outputHopper();
        if (controller == null || chest == null || hopper == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-05 controller or output logistics disappeared");
        }

        OutputObservation output = observeOutput(chest, plan.process());
        if (output.unexpectedItem() != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-05 output path contains unexpected item " + output.unexpectedItem());
        }
        boolean inputStillPresent = inputEntityId != null
                && entityPresent(inputEntityId);
        boolean outputInFlight = !hopper.isEmpty()
                || !boundedItemEntities().isEmpty();
        if (controller.isOccupied()
                || inputStillPresent
                || outputInFlight
                || output.primaryCount() < plan.process().minimumOutputCount()) {
            return ActionHandlerResult.inProgress(false);
        }

        for (CrushingWheelPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PROCESSING_FAILED, mismatch);
            }
        }
        Map<ResourceId, Integer> byproductCounts = new LinkedHashMap<>();
        for (ProcessResource byproduct : plan.process().optionalByproducts()) {
            byproductCounts.put(
                    byproduct.resourceId(),
                    output.counts().getOrDefault(byproduct.resourceId(), 0));
        }
        completedEvidence = new CrushingWheelEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                plan.origin(),
                plan.placements(),
                kinetics.signedSpeeds(),
                kinetics.capacities(),
                kinetics.loads(),
                plan.process().recipeId(),
                plan.process().recipeType(),
                crushingRecipe.getProcessingDuration(),
                plan.process().inputItem(),
                plan.process().inputCount(),
                plan.process().expectedOutputItem(),
                output.primaryCount(),
                byproductCounts,
                probabilities,
                true,
                true,
                true);
        List<ProcessResource> produced = new ArrayList<>();
        produced.add(new ProcessResource(
                plan.process().expectedOutputItem(),
                GenericResourceType.ITEM,
                output.primaryCount()));
        byproductCounts.forEach((id, count) -> {
            if (count > 0) {
                produced.add(new ProcessResource(id, GenericResourceType.ITEM, count));
            }
        });
        worldChanges.recordIrreversibleProcessing(
                context,
                plan.process().recipeId(),
                plan.process().genericSpec().inputs(),
                produced,
                affectedPositions());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private List<BlockPos3i> affectedPositions() {
        ArrayList<BlockPos3i> values = new ArrayList<>();
        plan.placements().forEach(value -> values.add(value.position()));
        values.add(plan.controllerPosition());
        return List.copyOf(values);
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            CrushingWheelEvidence physical) {
        return List.of(
                evidence(
                        id("create:c05/observation/input_consumed"),
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        CrushingWheelGenericExecutionPlan.INPUT_CONSUMED_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.INPUT_BOUNDARY_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.consumedInputCount()),
                        INTEGER_VALUE,
                        Integer.toString(plan.process().inputCount())),
                evidence(
                        id("create:c05/observation/process_completed"),
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        CrushingWheelGenericExecutionPlan.PROCESS_COMPLETED_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                        TICK_RANGE_VALUE,
                        physical.feedTick() + ".." + physical.completionTick(),
                        RECIPE_VALUE,
                        physical.recipeId() + " duration="
                                + physical.recipeProcessingDuration()),
                evidence(
                        id("create:c05/observation/output_produced"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        CrushingWheelGenericExecutionPlan.OUTPUT_PRODUCED_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.observedOutputCount()),
                        INTEGER_VALUE,
                        ">=" + plan.process().minimumOutputCount()),
                evidence(
                        id("create:c05/observation/controller_valid"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_VALID_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                        ITEM_STACK_VALUE,
                        "create:crushing_wheel_controller[valid=true,facing=down]",
                        ITEM_STACK_VALUE,
                        "create:crushing_wheel_controller[valid=true,facing=down]"),
                evidence(
                        id("create:c05/observation/wheel_directions"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        CrushingWheelGenericExecutionPlan.WHEEL_DIRECTIONS_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                        KINETIC_VALUE,
                        physical.observedSignedSpeedRpm().toString()
                                + " capacity=" + physical.observedStressCapacity()
                                + " load=" + physical.observedStressLoad(),
                        KINETIC_VALUE,
                        "equal_magnitude_opposed_and_not_overstressed"),
                evidence(
                        id("create:c05/observation/probabilistic_outputs"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        CrushingWheelGenericExecutionPlan.PROBABILISTIC_OUTPUTS_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.CONTROLLER_NODE_ID,
                        PROBABILITY_VALUE,
                        physical.observedByproductCounts().toString()
                                + " probabilities=" + physical.probabilityPerMillion(),
                        PROBABILITY_VALUE,
                        probabilities.toString()),
                evidence(
                        id("create:c05/observation/chest_output"),
                        VerificationEvidenceKind.OUTPUT_STORED,
                        CrushingWheelGenericExecutionPlan.CHEST_OUTPUT_REQUIREMENT,
                        context,
                        CrushingWheelGenericExecutionPlan.OUTPUT_CHEST_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x" + physical.observedOutputCount()
                                + " byproducts=" + physical.observedByproductCounts(),
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x>="
                                + plan.process().minimumOutputCount()));
    }

    private AdapterResult<KineticEvidence> captureKinetics() {
        for (CrushingWheelRole role : List.of(
                CrushingWheelRole.LEFT_DRIVE, CrushingWheelRole.RIGHT_DRIVE)) {
            BlockEntity entity = level.getBlockEntity(position(plan.placement(role).position()));
            if (entity instanceof WaterWheelBlockEntity wheel && wheel.flowScore == 0) {
                wheel.determineAndApplyFlowScore();
            }
        }
        EnumMap<CrushingWheelRole, Double> speeds =
                new EnumMap<>(CrushingWheelRole.class);
        EnumMap<CrushingWheelRole, Double> capacities =
                new EnumMap<>(CrushingWheelRole.class);
        EnumMap<CrushingWheelRole, Double> loads =
                new EnumMap<>(CrushingWheelRole.class);
        for (CrushingWheelRole role : kineticRoles()) {
            AdapterResult<KineticSnapshot> result = Create606KineticReader.capture(
                    level, plan.placement(role).position(), runtime);
            if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) {
                return adapterFailure(failure.code(), role + ": " + failure.detail());
            }
            KineticSnapshot snapshot =
                    ((AdapterResult.Success<KineticSnapshot>) result).value();
            if (snapshot.speedRpm() == 0
                    || snapshot.rotationDirection() == KineticRotationDirection.STATIONARY) {
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " has not received live power");
            }
            if (snapshot.overstressed()) {
                return adapterFailure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        role + " is overstressed");
            }
            double signed = snapshot.rotationDirection()
                    == KineticRotationDirection.POSITIVE
                    ? snapshot.speedRpm()
                    : -snapshot.speedRpm();
            speeds.put(role, signed);
            capacities.put(role, snapshot.stressCapacity());
            loads.put(role, snapshot.stressLoad());
        }
        double left = speeds.get(CrushingWheelRole.LEFT_WHEEL);
        double right = speeds.get(CrushingWheelRole.RIGHT_WHEEL);
        if (Math.signum(left) == Math.signum(right)
                || Double.compare(Math.abs(left), Math.abs(right)) != 0) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-05 wheels are not equal-magnitude opposed rotation");
        }
        return new AdapterResult.Success<>(new KineticEvidence(
                Map.copyOf(speeds), Map.copyOf(capacities), Map.copyOf(loads)));
    }

    private String controllerFailure(boolean requireValid) {
        BlockState state = level.getBlockState(position(plan.controllerPosition()));
        if (!AllBlocks.CRUSHING_WHEEL_CONTROLLER.has(state)) {
            return "C-05 crushing controller disappeared";
        }
        if (!state.hasProperty(CrushingWheelControllerBlock.VALID)
                || !state.hasProperty(BlockStateProperties.FACING)) {
            return "C-05 crushing controller state is incomplete";
        }
        if (requireValid && !state.getValue(CrushingWheelControllerBlock.VALID)) {
            return "settling:C-05 crushing controller is not valid yet";
        }
        if (requireValid
                && state.getValue(BlockStateProperties.FACING) != Direction.DOWN) {
            return "C-05 wheel rotation drives the controller "
                    + state.getValue(BlockStateProperties.FACING)
                    + " instead of DOWN";
        }
        return null;
    }

    private CrushingWheelControllerBlockEntity controller() {
        BlockEntity value = level.getBlockEntity(position(plan.controllerPosition()));
        return value instanceof CrushingWheelControllerBlockEntity controller
                ? controller
                : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(CrushingWheelRole.OUTPUT_CHEST).position()));
        return value instanceof ChestBlockEntity chest ? chest : null;
    }

    private HopperBlockEntity outputHopper() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(CrushingWheelRole.OUTPUT_HOPPER).position()));
        return value instanceof HopperBlockEntity hopper ? hopper : null;
    }

    private boolean entityPresent(UUID id) {
        Entity entity = level.getEntity(id);
        return entity != null && entity.isAlive();
    }

    private List<ItemEntity> boundedItemEntities() {
        BlockPos center = position(plan.controllerPosition());
        return level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(center).inflate(1.25D, 3.0D, 1.25D),
                Entity::isAlive);
    }

    private static OutputObservation observeOutput(
            ChestBlockEntity chest,
            CrushingProcessSpec spec) {
        Set<ResourceId> allowed = new LinkedHashSet<>();
        allowed.add(spec.expectedOutputItem());
        spec.optionalByproducts().forEach(value -> allowed.add(value.resourceId()));
        LinkedHashMap<ResourceId, Integer> counts = new LinkedHashMap<>();
        ResourceId unexpected = null;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceId item = itemId(stack);
            if (item == null || !allowed.contains(item)) {
                unexpected = item == null ? id("minecraft:air") : item;
                continue;
            }
            counts.merge(item, stack.getCount(), Math::addExact);
        }
        return new OutputObservation(
                counts.getOrDefault(spec.expectedOutputItem(), 0),
                Map.copyOf(counts),
                unexpected);
    }

    private static String validateLiveRecipe(
            CrushingRecipe recipe,
            CrushingProcessSpec spec) {
        if (!key(spec.recipeId()).equals(recipe.getId())) {
            return "Live crushing recipe mismatch: expected "
                    + spec.recipeId() + " found " + recipe.getId();
        }
        if (!key(spec.recipeType()).equals(AllRecipeTypes.CRUSHING.getId())
                || recipe.getType() != AllRecipeTypes.CRUSHING.getType()) {
            return "Live recipe is not Create crushing type " + spec.recipeType();
        }
        if (recipe.getProcessingDuration() < 1
                || recipe.getProcessingDuration() > spec.processingTimeoutTicks()) {
            return "Live crushing duration is outside the typed timeout: "
                    + recipe.getProcessingDuration();
        }
        boolean primaryGuaranteed = false;
        LinkedHashSet<ResourceId> secondary = new LinkedHashSet<>();
        for (ProcessingOutput output : recipe.getRollableResults()) {
            ResourceId item = itemId(output.getStack());
            if (item == null || output.getStack().getCount() < 1
                    || !Float.isFinite(output.getChance())
                    || output.getChance() <= 0.0F
                    || output.getChance() > 1.0F) {
                return "Live crushing output contains invalid item/count/probability";
            }
            if (item.equals(spec.expectedOutputItem())
                    && output.getChance() == 1.0F) {
                primaryGuaranteed = true;
            } else if (!secondary.add(item)) {
                return "Live crushing output repeats a secondary resource " + item;
            }
        }
        Set<ResourceId> expectedSecondary = new LinkedHashSet<>();
        spec.optionalByproducts().forEach(value ->
                expectedSecondary.add(value.resourceId()));
        if (!primaryGuaranteed || !secondary.equals(expectedSecondary)) {
            return "Live crushing outputs differ from the typed primary/byproduct set";
        }
        return null;
    }

    private static Map<ResourceId, Integer> probabilityEvidence(
            CrushingRecipe recipe,
            CrushingProcessSpec spec) {
        LinkedHashMap<ResourceId, Integer> values = new LinkedHashMap<>();
        Set<ResourceId> expected = new LinkedHashSet<>();
        spec.optionalByproducts().forEach(value -> expected.add(value.resourceId()));
        for (ProcessingOutput output : recipe.getRollableResults()) {
            ResourceId item = itemId(output.getStack());
            if (item != null && expected.contains(item)) {
                values.put(item, Math.round(output.getChance() * 1_000_000.0F));
            }
        }
        return Map.copyOf(values);
    }

    private String placementMismatch(CrushingWheelPlacement placement) {
        BlockState state = level.getBlockState(position(placement.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "C-05 placement mismatch at " + placement.position()
                    + ": expected " + placement.blockId() + " found " + actual;
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state) != axis(placement.rotationAxis())) {
            return "C-05 placement axis mismatch at " + placement.position();
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state) != direction(placement.facing())) {
            return "C-05 placement facing mismatch at " + placement.position();
        }
        return null;
    }

    private static String validateTypedState(
            CrushingWheelPlacement placement,
            BlockState state) {
        if (placement.facing() != PlanBlockFacing.NONE && !hasFacing(state)) {
            return placement.blockId()
                    + " has no facing property required by the typed C-05 plan";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)) {
            return placement.blockId()
                    + " has no rotation property required by the typed C-05 plan";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && placement.facing() != PlanBlockFacing.NONE
                && direction(placement.facing()).getAxis()
                        != axis(placement.rotationAxis())) {
            return placement.blockId()
                    + " typed C-05 facing and rotation axis disagree";
        }
        return null;
    }

    private static BlockState applyTypedState(
            CrushingWheelPlacement placement,
            BlockState state) {
        String failure = validateTypedState(placement, state);
        if (failure != null) throw new IllegalArgumentException(failure);
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(
                    BlockStateProperties.AXIS,
                    axis(placement.rotationAxis()));
        } else if (placement.rotationAxis() != PlanBlockAxis.NONE && hasFacing(state)) {
            state = withFacing(state, axisFacing(placement.rotationAxis()));
        }
        if (placement.facing() != PlanBlockFacing.NONE) {
            state = withFacing(state, direction(placement.facing()));
        }
        return state;
    }

    private static BlockState withFacing(BlockState state, Direction facing) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.setValue(BlockStateProperties.FACING, facing);
        }
        if (state.hasProperty(HopperBlock.FACING)) {
            return state.setValue(HopperBlock.FACING, facing);
        }
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    private static Direction axisFacing(PlanBlockAxis axis) {
        return switch (axis) {
            case X -> Direction.WEST;
            case Y -> Direction.UP;
            case Z -> Direction.NORTH;
            case NONE -> throw new IllegalArgumentException("NONE has no Minecraft facing");
        };
    }

    private VerificationEvidence evidence(
            ResourceId evidenceId,
            VerificationEvidenceKind kind,
            ResourceId requirementId,
            StepRunnerContext context,
            ResourceId targetId,
            ResourceId observedSchema,
            String observed,
            ResourceId expectedSchema,
            String expected) {
        return new VerificationEvidence(
                evidenceId,
                kind,
                requirementId,
                context.stepId(),
                CrushingWheelGenericExecutionPlan.ACTION_HANDLER_ID,
                targetId,
                new EvidenceValue(observedSchema, observed),
                new EvidenceValue(expectedSchema, expected),
                context.gameTick(),
                true,
                Optional.empty());
    }

    private ActionHandlerResult success(VerificationEvidence evidence) {
        return ActionHandlerResult.succeeded(
                List.of(evidence), worldChanges.drainInvocationReferences());
    }

    private ActionHandlerResult fail(
            AdapterFailureCode code,
            String failureDetail) {
        String normalized = stripPrefix(failureDetail);
        lastFailure = detail(code, normalized);
        return ActionHandlerResult.failed(
                failureId(code),
                normalized,
                worldChanges.drainInvocationReferences());
    }

    private static String kineticValue(KineticEvidence evidence) {
        StringBuilder value = new StringBuilder();
        for (CrushingWheelRole role : kineticRoles()) {
            if (!value.isEmpty()) value.append(';');
            value.append(role.name().toLowerCase(Locale.ROOT))
                    .append("=rpm:")
                    .append(evidence.signedSpeeds().get(role))
                    .append(",capacity:")
                    .append(evidence.capacities().get(role))
                    .append(",load:")
                    .append(evidence.loads().get(role));
        }
        return value.toString();
    }

    private static List<CrushingWheelRole> kineticRoles() {
        return List.of(
                CrushingWheelRole.LEFT_DRIVE,
                CrushingWheelRole.RIGHT_DRIVE,
                CrushingWheelRole.LEFT_WHEEL,
                CrushingWheelRole.RIGHT_WHEEL);
    }

    private static boolean matches(
            StepActionDescriptor action,
            StepRunnerContext context,
            ResourceId operationId,
            ResourceId stepId) {
        return action.operationId().equals(operationId)
                && context.stepId().equals(stepId);
    }

    private static boolean hasFacing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                || state.hasProperty(HopperBlock.FACING)
                || state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static Direction stateFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(HopperBlock.FACING)) {
            return state.getValue(HopperBlock.FACING);
        }
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : null;
    }

    private static Direction.Axis stateRotationAxis(BlockState state) {
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            return state.getValue(BlockStateProperties.AXIS);
        }
        Direction facing = stateFacing(state);
        return facing == null ? null : facing.getAxis();
    }

    private static Direction.Axis axis(PlanBlockAxis axis) {
        return switch (axis) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException("NONE has no Minecraft axis");
        };
    }

    private static Direction direction(PlanBlockFacing facing) {
        return switch (facing) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case NONE -> throw new IllegalArgumentException(
                    "NONE has no Minecraft direction");
        };
    }

    private static Block registeredBlock(ResourceId blockId) {
        ResourceLocation key = key(blockId);
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        return block != null && key.equals(ForgeRegistries.BLOCKS.getKey(block))
                ? block
                : null;
    }

    private static Item registeredItem(ResourceId itemId) {
        ResourceLocation key = key(itemId);
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item != null && key.equals(ForgeRegistries.ITEMS.getKey(item))
                ? item
                : null;
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation actual = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return actual == null
                ? null
                : new ResourceId(actual.getNamespace(), actual.getPath());
    }

    private static BlockPos position(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static ResourceLocation key(ResourceId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }

    private static ResourceId failureId(AdapterFailureCode code) {
        return id("create:v606/failure/" + code.name().toLowerCase(Locale.ROOT));
    }

    private static FailureDetail detail(
            AdapterFailureCode code,
            String failureDetail) {
        return new FailureDetail(
                code,
                "Create 6.0.6 crushing-wheel handler: "
                        + stripPrefix(failureDetail));
    }

    private static String stripPrefix(String value) {
        String prefix = "Create 6.0.6 crushing-wheel handler: ";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static <T> AdapterResult<T> adapterFailure(
            AdapterFailureCode code,
            String value) {
        FailureDetail failure = detail(code, value);
        return new AdapterResult.Failure<>(failure.code(), failure.detail());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    record FailureDetail(AdapterFailureCode code, String detail) {
        FailureDetail {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private record KineticEvidence(
            Map<CrushingWheelRole, Double> signedSpeeds,
            Map<CrushingWheelRole, Double> capacities,
            Map<CrushingWheelRole, Double> loads) {
        KineticEvidence {
            signedSpeeds = Map.copyOf(signedSpeeds);
            capacities = Map.copyOf(capacities);
            loads = Map.copyOf(loads);
        }
    }

    private record OutputObservation(
            int primaryCount,
            Map<ResourceId, Integer> counts,
            ResourceId unexpectedItem) {
    }
}
