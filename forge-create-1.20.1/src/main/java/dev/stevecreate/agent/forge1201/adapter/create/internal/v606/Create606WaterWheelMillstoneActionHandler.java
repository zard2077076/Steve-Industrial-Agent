package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillingRecipe;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.adapter.api.WaterWheelMillstoneEvidence;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.MillingProcessSpec;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.ResolvedPlanPlacement;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneRole;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 world actions behind the shared bounded C-03 runner. */
final class Create606WaterWheelMillstoneActionHandler implements StepActionHandler {
    private static final ResourceId INTEGER_VALUE = id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE = id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE = id("steve_industrial:value/recipe");
    private static final ResourceId ROLE_RPM_VALUE = id("steve_industrial:value/role_rpm");
    private static final ResourceId TICK_RANGE_VALUE = id("steve_industrial:value/tick_range");

    private final ServerLevel level;
    private final WaterWheelMillstonePlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;

    private int placementIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private MillingRecipe millingRecipe;
    private WaterWheelMillstoneEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606WaterWheelMillstoneActionHandler(
            ServerLevel level,
            WaterWheelMillstonePlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, null);
    }

    Create606WaterWheelMillstoneActionHandler(
            ServerLevel level,
            WaterWheelMillstonePlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"));
    }

    Create606WaterWheelMillstoneActionHandler(
            ServerLevel level,
            WaterWheelMillstonePlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal existingJournal,
            int placementIndex) {
        this(level, plan, runtime, sessionId, existingJournal, placementIndex, null);
    }

    Create606WaterWheelMillstoneActionHandler(
            ServerLevel level,
            WaterWheelMillstonePlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal existingJournal,
            int placementIndex,
            Create606WorldResourceBuffer resourceBuffer) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (placementIndex < 0 || placementIndex > plan.placements().size()) {
            throw new IllegalArgumentException("Recovered C-03 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level, sessionId, Objects.requireNonNull(existingJournal, "existingJournal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(
            ServerLevel level,
            WaterWheelMillstonePlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (ResolvedPlanPlacement placement : plan.placements()) {
            ResourceLocation key = key(placement.blockId());
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            if (block == null || !key.equals(ForgeRegistries.BLOCKS.getKey(block))) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing runtime block for " + placement));
            }
            BlockPos position = position(placement.position());
            if (!level.getBlockState(position).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Plan target is not replaceable at " + placement.position()));
            }
            BlockState state = block.defaultBlockState();
            if (placement.axis() != PlanBlockAxis.NONE && !supportsPlanAxis(placement, state)) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        placement.blockId() + " has no axis property required by the typed plan"));
            }
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
                    "C-03 action handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(WaterWheelMillstoneGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-03 action descriptor did not match the registered v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "Plan preflight area unloaded during execution: " + chunk.x + "," + chunk.z);
        }

        if (matches(
                action,
                context,
                WaterWheelMillstoneGenericExecutionPlan.BUILD_OPERATION_ID,
                WaterWheelMillstoneGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        if (matches(
                action,
                context,
                WaterWheelMillstoneGenericExecutionPlan.POWER_OPERATION_ID,
                WaterWheelMillstoneGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(
                action,
                context,
                WaterWheelMillstoneGenericExecutionPlan.FEED_OPERATION_ID,
                WaterWheelMillstoneGenericExecutionPlan.FEED_STEP_ID)) {
            return feedMillstone(context);
        }
        if (matches(
                action,
                context,
                WaterWheelMillstoneGenericExecutionPlan.PROCESS_OPERATION_ID,
                WaterWheelMillstoneGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-03 operation and step identity did not match the fixed generic plan");
    }

    int placementIndex() {
        return placementIndex;
    }

    Optional<WaterWheelMillstoneEvidence> completedEvidence() {
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
            return placePilotFlowCell(context);
        }
        ResolvedPlanPlacement placement = plan.placements().get(placementIndex);
        BlockPos blockPos = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(blockPos);
        if (!level.getBlockState(blockPos).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Placement target changed at " + placement.position());
        }

        Block block = ForgeRegistries.BLOCKS.getValue(key(placement.blockId()));
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Runtime block disappeared: " + placement.blockId());
        }
        BlockState state = block.defaultBlockState();
        if (placement.axis() != PlanBlockAxis.NONE) {
            if (!supportsPlanAxis(placement, state)) {
                return fail(
                        AdapterFailureCode.PLACEMENT_FAILED,
                        "Axis property disappeared: " + placement.blockId());
            }
            state = applyPlanAxis(placement, state);
        }
        if (!level.setBlockAndUpdate(blockPos, state)) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected placement " + placement);
        }
        if (placement.role() == WaterWheelMillstoneRole.WATER_SOURCE) {
            level.scheduleTick(blockPos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        worldChanges.recordBlockChange(context, blockPos, before);
        String mismatch = placementMismatch(placement);
        if (mismatch != null) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
        }

        placementIndex++;
        if (placementIndex < plan.placements().size() || pilotFlowRequired()) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private ActionHandlerResult placePilotFlowCell(StepRunnerContext context) {
        if (!pilotFlowRequired() || pilotFlowPlacementIndex >= 2) {
            return buildSucceeded(context);
        }
        BlockPos source = position(plan.placement(
                WaterWheelMillstoneRole.WATER_SOURCE).position());
        BlockPos target = source.below(pilotFlowPlacementIndex + 1);
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "Pilot flowing-water target changed at " + target.toShortString());
        }
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        if (!level.setBlockAndUpdate(target, flowing)) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected bounded pilot flowing-water placement at "
                            + target.toShortString());
        }
        worldChanges.recordBlockChange(context, target, before);
        pilotFlowPlacementIndex++;
        if (pilotFlowPlacementIndex < 2) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private boolean pilotFlowRequired() {
        return pilotEnabled()
                && pilotFlowPlacementIndex < 2;
    }

    private boolean pilotEnabled() {
        return resourceBuffer != null
                && Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY);
    }

    private ActionHandlerResult buildSucceeded(StepRunnerContext context) {
        for (ResolvedPlanPlacement expected : plan.placements()) {
            String finalMismatch = placementMismatch(expected);
            if (finalMismatch != null) {
                return fail(AdapterFailureCode.PLACEMENT_FAILED, finalMismatch);
            }
        }
        return success(evidence(
                id("create:c03/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                WaterWheelMillstoneGenericExecutionPlan.BUILD_EVIDENCE_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(placementIndex),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(StepRunnerContext context) {
        refreshPilotWaterWheelFlow();
        AdapterResult<Map<WaterWheelMillstoneRole, Double>> result =
                capturePositiveKineticSpeeds();
        if (result instanceof AdapterResult.Failure<Map<WaterWheelMillstoneRole, Double>> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        Map<WaterWheelMillstoneRole, Double> speeds =
                ((AdapterResult.Success<Map<WaterWheelMillstoneRole, Double>>) result).value();
        String observed = speedValue(speeds);
        return success(evidence(
                id("create:c03/observation/power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                WaterWheelMillstoneGenericExecutionPlan.POWER_EVIDENCE_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                ROLE_RPM_VALUE,
                observed,
                ROLE_RPM_VALUE,
                "water_wheel,gearbox,shaft,millstone>0"));
    }

    private void refreshPilotWaterWheelFlow() {
        if (!pilotEnabled()) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(position(
                plan.placement(WaterWheelMillstoneRole.WATER_WHEEL).position()));
        if (blockEntity instanceof WaterWheelBlockEntity waterWheel
                && waterWheel.flowScore == 0) {
            waterWheel.determineAndApplyFlowScore();
        }
    }

    private ActionHandlerResult feedMillstone(StepRunnerContext context) {
        AdapterResult<Map<WaterWheelMillstoneRole, Double>> speedResult =
                capturePositiveKineticSpeeds();
        if (speedResult instanceof AdapterResult.Failure<Map<WaterWheelMillstoneRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        MillstoneBlockEntity millstone = millstone();
        if (millstone == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Typed millstone position lost its block entity");
        }
        if (!millstone.inputInv.getStackInSlot(0).isEmpty() || !allOutputsEmpty(millstone)) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Millstone inventories were not empty before feed");
        }

        MillingProcessSpec spec = plan.process();
        Item inputItem = registeredItem(spec.inputItem());
        if (inputItem == null) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Missing runtime input item " + spec.inputItem());
        }
        ItemStack offered = new ItemStack(inputItem, spec.inputCount());
        ItemStackHandler recipeInput = new ItemStackHandler(1);
        recipeInput.setStackInSlot(0, offered.copy());
        Optional<MillingRecipe> recipe = level.getRecipeManager().getRecipeFor(
                AllRecipeTypes.MILLING.getType(),
                new RecipeWrapper(recipeInput),
                level);
        if (recipe.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live Create milling recipe for " + spec.inputItem());
        }
        millingRecipe = recipe.orElseThrow();
        String recipeFailure = validateLiveRecipe(millingRecipe, spec);
        if (recipeFailure != null) {
            return fail(AdapterFailureCode.RECIPE_NOT_FOUND, recipeFailure);
        }

        if (resourceBuffer != null) {
            AdapterResult<ItemStack> extracted = resourceBuffer.extractExact(
                    spec.inputItem(), spec.inputCount());
            if (extracted instanceof AdapterResult.Failure<ItemStack> failure) {
                return fail(failure.code(), failure.detail());
            }
            offered = ((AdapterResult.Success<ItemStack>) extracted).value();
        }

        ItemStack remainder = millstone.inputInv.insertItem(0, offered, false);
        if (!remainder.isEmpty()) {
            if (resourceBuffer != null) resourceBuffer.insertExact(offered);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Create millstone rejected typed input " + spec.inputItem());
        }
        worldChanges.recordInjectedResource(
                context,
                position(plan.placement(WaterWheelMillstoneRole.MILLSTONE).position()),
                spec.genericSpec().inputs().get(0));
        ItemStack inserted = millstone.inputInv.getStackInSlot(0);
        if (!itemMatches(inserted, spec.inputItem())
                || inserted.getCount() != spec.inputCount()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Millstone input readback changed after feed");
        }
        millstone.setChanged();
        feedTick = level.getGameTime();
        String stack = spec.inputItem() + " x" + spec.inputCount();
        return success(evidence(
                id("create:c03/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                WaterWheelMillstoneGenericExecutionPlan.FEED_EVIDENCE_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                ITEM_STACK_VALUE,
                stack,
                ITEM_STACK_VALUE,
                stack));
    }

    private ActionHandlerResult observeProcessing(StepRunnerContext context) {
        tickPilotMillstone();
        AdapterResult<Map<WaterWheelMillstoneRole, Double>> speedResult =
                capturePositiveKineticSpeeds();
        if (speedResult instanceof AdapterResult.Failure<Map<WaterWheelMillstoneRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        Map<WaterWheelMillstoneRole, Double> speeds =
                ((AdapterResult.Success<Map<WaterWheelMillstoneRole, Double>>) speedResult).value();
        MillstoneBlockEntity millstone = millstone();
        if (millstone == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Millstone block entity disappeared during processing");
        }

        MillingProcessSpec spec = plan.process();
        ItemStack input = millstone.inputInv.getStackInSlot(0);
        if (!input.isEmpty()
                && (!itemMatches(input, spec.inputItem())
                || input.getCount() < 1
                || input.getCount() > spec.inputCount())) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Millstone input changed to an unexpected stack");
        }
        Set<String> declaredByproducts = spec.genericSpec().optionalByproducts().stream()
                .map(value -> value.resourceId().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        OutputObservation output = observeOutputs(
                millstone, spec.expectedOutputItem(), declaredByproducts);
        if (output.unexpectedItem() != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Millstone produced unexpected item " + output.unexpectedItem());
        }
        if (!input.isEmpty() || output.expectedCount() < spec.minimumOutputCount()) {
            return ActionHandlerResult.inProgress(false);
        }

        for (ResolvedPlanPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PROCESSING_FAILED, mismatch);
            }
        }
        completedEvidence = new WaterWheelMillstoneEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                plan.origin(),
                plan.placements(),
                speeds,
                spec.recipeId(),
                spec.recipeType(),
                millingRecipe.getProcessingDuration(),
                spec.inputItem(),
                spec.inputCount(),
                spec.expectedOutputItem(),
                output.expectedCount());
        worldChanges.recordIrreversibleProcessing(
                context,
                spec.recipeId(),
                spec.genericSpec().inputs(),
                spec.genericSpec().outputs(),
                plan.placements().stream().map(ResolvedPlanPlacement::position).toList());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private void tickPilotMillstone() {
        if (!pilotEnabled()) {
            return;
        }
        MillstoneBlockEntity value = millstone();
        if (value != null) value.tick();
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            WaterWheelMillstoneEvidence physical) {
        VerificationEvidence inputConsumed = evidence(
                id("create:c03/observation/input_consumed"),
                VerificationEvidenceKind.INPUT_CONSUMED,
                WaterWheelMillstoneGenericExecutionPlan.INPUT_CONSUMED_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                INTEGER_VALUE,
                Integer.toString(physical.consumedInputCount()),
                INTEGER_VALUE,
                Integer.toString(plan.process().inputCount()));
        VerificationEvidence processCompleted = evidence(
                id("create:c03/observation/process_completed"),
                VerificationEvidenceKind.PROCESS_COMPLETED,
                WaterWheelMillstoneGenericExecutionPlan.PROCESS_COMPLETED_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                TICK_RANGE_VALUE,
                physical.feedTick() + ".." + physical.completionTick(),
                RECIPE_VALUE,
                physical.recipeId() + " duration=" + physical.recipeProcessingDuration());
        VerificationEvidence outputProduced = evidence(
                id("create:c03/observation/output_produced"),
                VerificationEvidenceKind.OUTPUT_PRODUCED,
                WaterWheelMillstoneGenericExecutionPlan.OUTPUT_PRODUCED_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                INTEGER_VALUE,
                Integer.toString(physical.observedOutputCount()),
                INTEGER_VALUE,
                ">=" + plan.process().minimumOutputCount());
        VerificationEvidence inventoryOutput = evidence(
                id("create:c03/observation/millstone_inventory_output"),
                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_OUTPUT_REQUIREMENT,
                context,
                WaterWheelMillstoneGenericExecutionPlan.MILLSTONE_NODE_ID,
                ITEM_STACK_VALUE,
                physical.outputItem() + " x" + physical.observedOutputCount(),
                ITEM_STACK_VALUE,
                physical.outputItem() + " x>=" + plan.process().minimumOutputCount());
        return List.of(inputConsumed, processCompleted, outputProduced, inventoryOutput);
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
                WaterWheelMillstoneGenericExecutionPlan.ACTION_HANDLER_ID,
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

    private ActionHandlerResult fail(AdapterFailureCode code, String detail) {
        String normalized = stripPrefix(detail);
        lastFailure = detail(code, normalized);
        return ActionHandlerResult.failed(
                failureId(code), normalized, worldChanges.drainInvocationReferences());
    }

    private AdapterResult<Map<WaterWheelMillstoneRole, Double>> capturePositiveKineticSpeeds() {
        EnumMap<WaterWheelMillstoneRole, Double> speeds =
                new EnumMap<>(WaterWheelMillstoneRole.class);
        for (WaterWheelMillstoneRole role : kineticRoles()) {
            ResolvedPlanPlacement placement = plan.placement(role);
            BlockEntity blockEntity = level.getBlockEntity(position(placement.position()));
            if (!(blockEntity instanceof KineticBlockEntity kinetic)) {
                return adapterFailure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        role + " is not a Create kinetic block entity");
            }
            if ((kinetic.networkDirty || kinetic.needsSpeedUpdate()) && !pilotEnabled()) {
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " kinetic state is still settling");
            }
            float speed = kinetic.getSpeed();
            if (!Float.isFinite(speed)) {
                return adapterFailure(
                        AdapterFailureCode.CREATE_API_FAILURE,
                        role + " exposed non-finite speed");
            }
            if (kinetic.isOverStressed()) {
                return adapterFailure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        role + " is overstressed");
            }
            if (speed == 0) {
                String lifecycle = kinetic.networkDirty || kinetic.needsSpeedUpdate()
                        ? " while its kinetic state is still settling" : "";
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " has not received power" + lifecycle);
            }
            speeds.put(role, Math.abs((double) speed));
        }
        return new AdapterResult.Success<>(Map.copyOf(speeds));
    }

    private static String validateLiveRecipe(MillingRecipe recipe, MillingProcessSpec spec) {
        ResourceLocation actualId = recipe.getId();
        if (!key(spec.recipeId()).equals(actualId)) {
            return "Live milling recipe mismatch: expected " + spec.recipeId()
                    + " found " + actualId;
        }
        if (!key(spec.recipeType()).equals(AllRecipeTypes.MILLING.getId())
                || recipe.getType() != AllRecipeTypes.MILLING.getType()) {
            return "Live recipe is not Create milling type " + spec.recipeType();
        }
        if (recipe.getProcessingDuration() < 1
                || recipe.getProcessingDuration() > spec.processingTimeoutTicks()) {
            return "Live recipe duration is outside the typed timeout: "
                    + recipe.getProcessingDuration();
        }
        boolean deterministicExpectedOutput = false;
        for (ProcessingOutput output : recipe.getRollableResults()) {
            ItemStack stack = output.getStack();
            if (itemMatches(stack, spec.expectedOutputItem())
                    && stack.getCount() > 0
                    && output.getChance() == 1.0f) {
                deterministicExpectedOutput = true;
            }
        }
        return deterministicExpectedOutput
                ? null
                : "Live recipe has no deterministic expected output "
                        + spec.expectedOutputItem();
    }

    private MillstoneBlockEntity millstone() {
        BlockEntity blockEntity = level.getBlockEntity(position(
                plan.placement(WaterWheelMillstoneRole.MILLSTONE).position()));
        return blockEntity instanceof MillstoneBlockEntity value ? value : null;
    }

    private String placementMismatch(ResolvedPlanPlacement placement) {
        BlockState state = level.getBlockState(position(placement.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "Placement readback mismatch at " + placement.position()
                    + ": expected " + placement.blockId() + " found " + actual;
        }
        if (placement.axis() != PlanBlockAxis.NONE && !planAxisMatches(placement, state)) {
            return "Placement axis mismatch at " + placement.position();
        }
        return null;
    }

    private static String speedValue(Map<WaterWheelMillstoneRole, Double> speeds) {
        StringBuilder value = new StringBuilder();
        for (WaterWheelMillstoneRole role : kineticRoles()) {
            if (!value.isEmpty()) {
                value.append(';');
            }
            value.append(role.name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(speeds.get(role));
        }
        return value.toString();
    }

    private static List<WaterWheelMillstoneRole> kineticRoles() {
        return List.of(
                WaterWheelMillstoneRole.WATER_WHEEL,
                WaterWheelMillstoneRole.GEARBOX,
                WaterWheelMillstoneRole.VERTICAL_SHAFT,
                WaterWheelMillstoneRole.MILLSTONE);
    }

    private static boolean matches(
            StepActionDescriptor action,
            StepRunnerContext context,
            ResourceId operationId,
            ResourceId stepId) {
        return action.operationId().equals(operationId) && context.stepId().equals(stepId);
    }

    private static boolean allOutputsEmpty(MillstoneBlockEntity millstone) {
        for (int slot = 0; slot < millstone.outputInv.getSlots(); slot++) {
            if (!millstone.outputInv.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the millstone holds, counting the product and tolerating declared byproducts.
     *
     * <p>Anything other than the primary product used to be "unexpected" and failed the
     * step. Milling drops a byproduct in 32 of the registry's recipes, so those recipes
     * could not be admitted at all — the refusal was upstream in the catalog, and this is
     * the check that would have failed had they got this far.
     *
     * <p>A byproduct is tolerated, not required: it is a chance drop, and demanding one
     * would fail a batch for being unlucky. Anything the recipe never mentioned is still
     * unexpected, which is the case this check exists for.</p>
     */
    private static OutputObservation observeOutputs(
            MillstoneBlockEntity millstone,
            ResourceId expectedItem,
            Set<String> declaredByproducts) {
        int count = 0;
        ResourceId unexpected = null;
        for (int slot = 0; slot < millstone.outputInv.getSlots(); slot++) {
            ItemStack stack = millstone.outputInv.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) {
                unexpected = new ResourceId("minecraft", "air");
            } else if (expectedItem.toString().equals(key.toString())) {
                count += stack.getCount();
            } else if (!declaredByproducts.contains(key.toString())) {
                unexpected = new ResourceId(key.getNamespace(), key.getPath());
            }
        }
        return new OutputObservation(count, unexpected);
    }

    private static boolean itemMatches(ItemStack stack, ResourceId expected) {
        ResourceLocation actual = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return actual != null && key(expected).equals(actual);
    }

    private static Item registeredItem(ResourceId itemId) {
        ResourceLocation key = key(itemId);
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item != null && key.equals(ForgeRegistries.ITEMS.getKey(item)) ? item : null;
    }

    private static Direction.Axis axis(PlanBlockAxis axis) {
        return switch (axis) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException("NONE has no Minecraft axis");
        };
    }

    private static boolean supportsPlanAxis(
            ResolvedPlanPlacement placement,
            BlockState state) {
        if (placement.role() == WaterWheelMillstoneRole.WATER_WHEEL) {
            return placement.axis() != PlanBlockAxis.Y
                    && state.hasProperty(BlockStateProperties.FACING);
        }
        return state.hasProperty(BlockStateProperties.AXIS);
    }

    private static BlockState applyPlanAxis(
            ResolvedPlanPlacement placement,
            BlockState state) {
        if (placement.role() == WaterWheelMillstoneRole.WATER_WHEEL) {
            Direction facing = switch (placement.axis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "Water-wheel plan axis must be horizontal");
            };
            return state.setValue(BlockStateProperties.FACING, facing);
        }
        return state.setValue(BlockStateProperties.AXIS, axis(placement.axis()));
    }

    private static boolean planAxisMatches(
            ResolvedPlanPlacement placement,
            BlockState state) {
        if (placement.role() == WaterWheelMillstoneRole.WATER_WHEEL) {
            return state.hasProperty(BlockStateProperties.FACING)
                    && state.getValue(BlockStateProperties.FACING).getAxis()
                    == axis(placement.axis());
        }
        return state.hasProperty(BlockStateProperties.AXIS)
                && state.getValue(BlockStateProperties.AXIS) == axis(placement.axis());
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

    private static FailureDetail detail(AdapterFailureCode code, String detail) {
        return new FailureDetail(
                code,
                "Create 6.0.6 water-wheel/millstone handler: " + stripPrefix(detail));
    }

    private static String stripPrefix(String detail) {
        String prefix = "Create 6.0.6 water-wheel/millstone handler: ";
        return detail.startsWith(prefix) ? detail.substring(prefix.length()) : detail;
    }

    private static <T> AdapterResult<T> adapterFailure(
            AdapterFailureCode code,
            String detail) {
        FailureDetail failure = detail(code, detail);
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

    private record OutputObservation(int expectedCount, ResourceId unexpectedItem) {
    }
}
