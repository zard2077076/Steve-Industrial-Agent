package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.press.PressingRecipe;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressEvidence;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressBuildStep;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.PlanBlockFacing;
import dev.stevecreate.agent.core.plan.PressingProcessSpec;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 world actions behind the shared bounded C-04 runner. */
final class Create606BeltPressActionHandler implements StepActionHandler {
    private static final List<BeltPressRole> BELT_ROLES = List.of(
            BeltPressRole.BELT_START,
            BeltPressRole.BELT_PRESSING,
            BeltPressRole.BELT_END);

    private static final ResourceId BOOLEAN_VALUE = id("steve_industrial:value/boolean");
    private static final ResourceId INTEGER_VALUE = id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE = id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE = id("steve_industrial:value/recipe");
    private static final ResourceId ROLE_RPM_VALUE = id("steve_industrial:value/role_rpm");
    private static final ResourceId TICK_RANGE_VALUE = id("steve_industrial:value/tick_range");

    private final ServerLevel level;
    private final BeltPressPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;

    private int buildStepIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private PressingRecipe pressingRecipe;
    private boolean inputObservedOnBelt;
    private boolean pressCycleObserved;
    private BeltPressEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606BeltPressActionHandler(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, 0, null);
    }

    Create606BeltPressActionHandler(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"));
    }

    Create606BeltPressActionHandler(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal existingJournal,
            int buildStepIndex) {
        this(level, plan, runtime, sessionId, existingJournal, buildStepIndex, 0, null);
    }

    Create606BeltPressActionHandler(
            ServerLevel level,
            BeltPressPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal existingJournal,
            int buildStepIndex,
            int pilotFlowPlacementIndex,
            Create606WorldResourceBuffer resourceBuffer) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (buildStepIndex < 0 || buildStepIndex > plan.buildSteps().size()) {
            throw new IllegalArgumentException("Recovered C-04 build cursor is outside the plan");
        }
        this.buildStepIndex = buildStepIndex;
        if (pilotFlowPlacementIndex < 0 || pilotFlowPlacementIndex > 2) {
            throw new IllegalArgumentException(
                    "Recovered C-04 pilot-flow cursor is outside the two bounded channels");
        }
        this.pilotFlowPlacementIndex = pilotFlowPlacementIndex;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level, sessionId, Objects.requireNonNull(existingJournal, "existingJournal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(ServerLevel level, BeltPressPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (BeltPressPlacement placement : plan.finalPlacements()) {
            Block block = registeredBlock(placement.blockId());
            if (block == null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing final runtime block for " + placement));
            }
            if (!level.getBlockState(position(placement.position())).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Final belt/press target is not replaceable at " + placement.position()));
            }
        }
        for (BeltPressBuildStep step : plan.buildSteps()) {
            if (step instanceof BeltPressBuildStep.PlaceBlock place) {
                Block block = registeredBlock(place.materialId());
                if (block == null) {
                    return Optional.of(detail(
                            AdapterFailureCode.PLAN_REJECTED,
                            "Missing runtime build block for " + place));
                }
                String propertyFailure = validateTypedState(place, block.defaultBlockState());
                if (propertyFailure != null) {
                    return Optional.of(detail(AdapterFailureCode.PLAN_REJECTED, propertyFailure));
                }
            } else if (step instanceof BeltPressBuildStep.ConnectBelt connection
                    && registeredItem(connection.materialId()) == null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing runtime belt material " + connection.materialId()));
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
                    "C-04 action handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(BeltPressGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-04 action descriptor did not match the registered v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "Belt/press preflight area unloaded during execution: "
                            + chunk.x + "," + chunk.z);
        }

        if (matches(
                action,
                context,
                BeltPressGenericExecutionPlan.BUILD_OPERATION_ID,
                BeltPressGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        if (matches(
                action,
                context,
                BeltPressGenericExecutionPlan.POWER_OPERATION_ID,
                BeltPressGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(
                action,
                context,
                BeltPressGenericExecutionPlan.FEED_OPERATION_ID,
                BeltPressGenericExecutionPlan.FEED_STEP_ID)) {
            return feedBelt(context);
        }
        if (matches(
                action,
                context,
                BeltPressGenericExecutionPlan.PROCESS_OPERATION_ID,
                BeltPressGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-04 operation and step identity did not match the fixed generic plan");
    }

    int buildStepIndex() {
        return buildStepIndex;
    }

    Optional<BeltPressEvidence> completedEvidence() {
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
        if (buildStepIndex >= plan.buildSteps().size()) {
            return placePilotFlowCell(context);
        }
        BeltPressBuildStep step = plan.buildSteps().get(buildStepIndex);
        FailureDetail failure = step instanceof BeltPressBuildStep.PlaceBlock place
                ? placeOne(context, place)
                : connectBelt(context, (BeltPressBuildStep.ConnectBelt) step);
        if (failure != null) {
            return fail(failure.code(), failure.detail());
        }

        buildStepIndex++;
        if (buildStepIndex < plan.buildSteps().size() || pilotFlowRequired()) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        for (BeltPressPlacement placement : plan.finalPlacements()) {
            String mismatch = finalPlacementMismatch(placement);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
            }
        }
        return success(evidence(
                id("create:c04/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                BeltPressGenericExecutionPlan.BUILD_EVIDENCE_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.finalPlacements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.finalPlacements().size())));
    }

    private ActionHandlerResult placePilotFlowCell(StepRunnerContext context) {
        if (!pilotFlowRequired()) {
            return fail(AdapterFailureCode.PLAN_REJECTED,
                    "C-04 build handler was invoked after every typed build step completed");
        }
        // The plan owns this rule; the recovery adapter and the acceptance fixture read
        // the same list, so a topology change moves all three at once.
        BlockPos target = position(plan.pilotFlowCells().get(pilotFlowPlacementIndex));
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "C-04 flowing-water target changed at " + target.toShortString());
        }
        if (!level.setBlockAndUpdate(target,
                Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock())) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected C-04 bounded flowing water at " + target.toShortString());
        }
        worldChanges.recordBlockChange(context, target, before);
        pilotFlowPlacementIndex++;
        return pilotFlowRequired()
                ? ActionHandlerResult.inProgress(true, worldChanges.drainInvocationReferences())
                : success(evidence(
                        id("create:c04/observation/placements_verified"),
                        VerificationEvidenceKind.BLOCK_STATE_MATCH,
                        BeltPressGenericExecutionPlan.BUILD_EVIDENCE_REQUIREMENT,
                        context, BeltPressGenericExecutionPlan.GRAPH_ID,
                        INTEGER_VALUE, Integer.toString(plan.finalPlacements().size()),
                        INTEGER_VALUE, Integer.toString(plan.finalPlacements().size())));
    }

    private boolean pilotFlowRequired() {
        return pilotFlowPlacementIndex < 2;
    }

    private FailureDetail placeOne(
            StepRunnerContext context,
            BeltPressBuildStep.PlaceBlock place) {
        BlockPos blockPos = position(place.position());
        WorldBlockSnapshot before = worldChanges.capture(blockPos);
        if (!level.getBlockState(blockPos).canBeReplaced()) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Build-step target changed at " + place.position());
        }
        Block block = registeredBlock(place.materialId());
        if (block == null) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Runtime build block disappeared: " + place.materialId());
        }
        BlockState state = block.defaultBlockState();
        String propertyFailure = validateTypedState(place, state);
        if (propertyFailure != null) {
            return detail(AdapterFailureCode.PLACEMENT_FAILED, propertyFailure);
        }
        state = applyTypedState(place, state);
        if (!level.setBlockAndUpdate(blockPos, state)) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected build step " + place);
        }
        if (place.role() == dev.stevecreate.agent.core.plan.BeltPressBuildRole.BELT_WATER_SOURCE
                || place.role() == dev.stevecreate.agent.core.plan.BeltPressBuildRole.PRESS_WATER_SOURCE) {
            level.scheduleTick(blockPos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        worldChanges.recordBlockChange(context, blockPos, before);
        String mismatch = buildStepMismatch(place);
        return mismatch == null
                ? null
                : detail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
    }

    private FailureDetail connectBelt(
            StepRunnerContext context,
            BeltPressBuildStep.ConnectBelt connection) {
        BlockPos start = position(connection.startPosition());
        BlockPos end = position(connection.endPosition());
        BlockPos interiorPosition = position(
                plan.placement(BeltPressRole.BELT_PRESSING).position());
        Map<BlockPos, WorldBlockSnapshot> before = Map.of(
                start, worldChanges.capture(start),
                interiorPosition, worldChanges.capture(interiorPosition),
                end, worldChanges.capture(end));
        Direction.Axis pulleyAxis = axis(
                plan.placement(BeltPressRole.BELT_START).rotationAxis());
        if (!shaftWithAxis(level, start, pulleyAxis)
                || !shaftWithAxis(level, end, pulleyAxis)) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Typed belt endpoints are not the planned " + pulleyAxis
                            + "-axis pulley shafts");
        }
        BeltPressPlacement interior = plan.placement(BeltPressRole.BELT_PRESSING);
        if (!level.getBlockState(position(interior.position())).canBeReplaced()) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Typed belt interior changed before connection at " + interior.position());
        }
        if (!BeltConnectorItem.canConnect(level, start, end)) {
            return detail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Create 6.0.6 rejected the validated bounded belt endpoints");
        }
        BeltConnectorItem.createBelts(level, start, end);
        worldChanges.recordBlockChange(context, start, before.get(start));
        worldChanges.recordBlockChange(
                context, interiorPosition, before.get(interiorPosition));
        worldChanges.recordBlockChange(context, end, before.get(end));
        for (BeltPressRole role : BELT_ROLES) {
            String mismatch = finalPlacementMismatch(plan.placement(role));
            if (mismatch != null) {
                return detail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
            }
        }
        return null;
    }

    private ActionHandlerResult awaitPower(StepRunnerContext context) {
        tickPilotLine();
        AdapterResult<Map<BeltPressRole, Double>> result = capturePositiveKineticSpeeds();
        if (result instanceof AdapterResult.Failure<Map<BeltPressRole, Double>> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        Map<BeltPressRole, Double> speeds =
                ((AdapterResult.Success<Map<BeltPressRole, Double>>) result).value();
        return success(evidence(
                id("create:c04/observation/power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                BeltPressGenericExecutionPlan.POWER_EVIDENCE_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID,
                ROLE_RPM_VALUE,
                speedValue(speeds),
                ROLE_RPM_VALUE,
                "belt_drive,belt_start,belt_pressing,belt_end,press_drive,mechanical_press>0"));
    }

    private ActionHandlerResult feedBelt(StepRunnerContext context) {
        AdapterResult<Map<BeltPressRole, Double>> speedResult = capturePositiveKineticSpeeds();
        if (speedResult instanceof AdapterResult.Failure<Map<BeltPressRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        BeltBlockEntity controller = beltController();
        if (controller == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Typed belt lost its initialized controller");
        }
        if (!controller.getInventory().getTransportedItems().isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Belt inventory was not empty before feed");
        }
        ChestBlockEntity chest = outputChest();
        if (chest == null || !chest.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Output chest was absent or not empty before feed");
        }

        PressingProcessSpec spec = plan.process();
        Item inputItem = registeredItem(spec.inputItem());
        if (inputItem == null) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Missing runtime input item " + spec.inputItem());
        }
        ItemStack offered = new ItemStack(inputItem, spec.inputCount());
        ItemStackHandler recipeInput = new ItemStackHandler(1);
        recipeInput.setStackInSlot(0, offered.copy());
        Optional<PressingRecipe> recipe = level.getRecipeManager().getRecipeFor(
                AllRecipeTypes.PRESSING.getType(),
                new RecipeWrapper(recipeInput),
                level);
        if (recipe.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live Create pressing recipe for " + spec.inputItem());
        }
        pressingRecipe = recipe.orElseThrow();
        String recipeFailure = validateLiveRecipe(pressingRecipe, spec);
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

        BeltBlockEntity start = beltSegment(BeltPressRole.BELT_START);
        if (start == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Typed belt start lost its block entity");
        }
        IItemHandler input = start.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (input == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Create belt exposed no real item handler");
        }
        ItemStack remainder = input.insertItem(0, offered, false);
        if (!remainder.isEmpty()) {
            if (resourceBuffer != null) resourceBuffer.insertExact(offered);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Create belt rejected typed input " + spec.inputItem());
        }
        worldChanges.recordInjectedResource(
                context,
                position(plan.placement(BeltPressRole.BELT_START).position()),
                spec.genericSpec().inputs().get(0));
        feedTick = level.getGameTime();
        String stack = spec.inputItem() + " x" + spec.inputCount();
        return success(evidence(
                id("create:c04/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                BeltPressGenericExecutionPlan.FEED_EVIDENCE_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.BELT_START_NODE_ID,
                ITEM_STACK_VALUE,
                stack,
                ITEM_STACK_VALUE,
                stack));
    }

    private ActionHandlerResult observeProcessing(StepRunnerContext context) {
        tickPilotLine();
        AdapterResult<Map<BeltPressRole, Double>> speedResult = capturePositiveKineticSpeeds();
        if (speedResult instanceof AdapterResult.Failure<Map<BeltPressRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        Map<BeltPressRole, Double> speeds =
                ((AdapterResult.Success<Map<BeltPressRole, Double>>) speedResult).value();

        MechanicalPressBlockEntity press = mechanicalPress();
        if (press == null || press.pressingBehaviour == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Mechanical press behaviour disappeared");
        }
        PressingBehaviour behaviour = press.pressingBehaviour;
        if (behaviour.mode == PressingBehaviour.Mode.BELT
                && (behaviour.running || behaviour.finished || behaviour.runningTicks > 0)) {
            pressCycleObserved = true;
        }

        BeltBlockEntity controller = beltController();
        ChestBlockEntity chest = outputChest();
        if (controller == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Belt controller or output chest disappeared");
        }
        ItemObservation beltItems = observeBeltItems(controller, plan.process());
        ItemObservation chestItems = observeChestItems(chest, plan.process());
        if (beltItems.unexpectedItem() != null || chestItems.unexpectedItem() != null) {
            ResourceId unexpected = beltItems.unexpectedItem() != null
                    ? beltItems.unexpectedItem()
                    : chestItems.unexpectedItem();
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Belt/press line observed unexpected item " + unexpected);
        }
        if (beltItems.inputCount() > 0) {
            inputObservedOnBelt = true;
        }
        if (chestItems.inputCount() > 0) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Unpressed input reached the output chest");
        }
        if (beltItems.inputCount() > plan.process().inputCount()
                || beltItems.outputCount() + chestItems.outputCount()
                > PressingProcessSpec.MAX_ITEM_COUNT) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Belt/press item counts exceeded typed bounds");
        }
        if (beltItems.inputCount() != 0
                || beltItems.outputCount() != 0
                || chestItems.outputCount() < plan.process().minimumOutputCount()) {
            return ActionHandlerResult.inProgress(false);
        }
        if (!inputObservedOnBelt || !pressCycleObserved) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Iron sheet appeared without complete belt-input and mechanical-press evidence");
        }
        for (BeltPressPlacement placement : plan.finalPlacements()) {
            String mismatch = finalPlacementMismatch(placement);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PROCESSING_FAILED, mismatch);
            }
        }

        completedEvidence = new BeltPressEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                plan.origin(),
                plan.finalPlacements(),
                speeds,
                plan.process().recipeId(),
                plan.process().recipeType(),
                pressingRecipe.getProcessingDuration(),
                PressingBehaviour.CYCLE,
                plan.process().inputItem(),
                plan.process().inputCount(),
                plan.process().expectedOutputItem(),
                chestItems.outputCount(),
                inputObservedOnBelt,
                pressCycleObserved,
                true);
        worldChanges.recordIrreversibleProcessing(
                context,
                plan.process().recipeId(),
                plan.process().genericSpec().inputs(),
                plan.process().genericSpec().outputs(),
                plan.finalPlacements().stream().map(BeltPressPlacement::position).toList());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private void tickPilotLine() {
        if (!pilotEnabled()) {
            return;
        }
        for (BeltPressPlacement placement : plan.finalPlacements()) {
            BlockEntity blockEntity = level.getBlockEntity(position(placement.position()));
            if (blockEntity instanceof SmartBlockEntity smart) smart.tick();
        }
    }

    private boolean pilotEnabled() {
        return resourceBuffer != null
                && Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY);
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            BeltPressEvidence physical) {
        VerificationEvidence inputConsumed = evidence(
                id("create:c04/observation/input_consumed"),
                VerificationEvidenceKind.INPUT_CONSUMED,
                BeltPressGenericExecutionPlan.INPUT_CONSUMED_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.BELT_START_NODE_ID,
                INTEGER_VALUE,
                Integer.toString(physical.consumedInputCount()),
                INTEGER_VALUE,
                Integer.toString(plan.process().inputCount()));
        VerificationEvidence processCompleted = evidence(
                id("create:c04/observation/process_completed"),
                VerificationEvidenceKind.PROCESS_COMPLETED,
                BeltPressGenericExecutionPlan.PROCESS_COMPLETED_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID,
                TICK_RANGE_VALUE,
                physical.feedTick() + ".." + physical.completionTick(),
                RECIPE_VALUE,
                physical.recipeId() + " pressCycleTicks=" + physical.pressCycleTicks());
        VerificationEvidence outputProduced = evidence(
                id("create:c04/observation/output_produced"),
                VerificationEvidenceKind.OUTPUT_PRODUCED,
                BeltPressGenericExecutionPlan.OUTPUT_PRODUCED_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID,
                INTEGER_VALUE,
                Integer.toString(physical.observedOutputCount()),
                INTEGER_VALUE,
                ">=" + plan.process().minimumOutputCount());
        VerificationEvidence beltInput = evidence(
                id("create:c04/observation/belt_input_observed"),
                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                BeltPressGenericExecutionPlan.BELT_INPUT_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.BELT_START_NODE_ID,
                BOOLEAN_VALUE,
                Boolean.toString(physical.inputObservedOnBelt()),
                BOOLEAN_VALUE,
                "true");
        VerificationEvidence pressCycle = evidence(
                id("create:c04/observation/press_cycle_observed"),
                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                BeltPressGenericExecutionPlan.PRESS_CYCLE_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.MECHANICAL_PRESS_NODE_ID,
                INTEGER_VALUE,
                Integer.toString(physical.pressCycleTicks()),
                INTEGER_VALUE,
                "240");
        VerificationEvidence chestOutput = evidence(
                id("create:c04/observation/chest_output_observed"),
                VerificationEvidenceKind.OUTPUT_STORED,
                BeltPressGenericExecutionPlan.CHEST_OUTPUT_REQUIREMENT,
                context,
                BeltPressGenericExecutionPlan.OUTPUT_CHEST_NODE_ID,
                ITEM_STACK_VALUE,
                physical.outputItem() + " x" + physical.observedOutputCount(),
                ITEM_STACK_VALUE,
                physical.outputItem() + " x>=" + plan.process().minimumOutputCount());
        return List.of(
                inputConsumed,
                processCompleted,
                outputProduced,
                beltInput,
                pressCycle,
                chestOutput);
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
                BeltPressGenericExecutionPlan.ACTION_HANDLER_ID,
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

    private AdapterResult<Map<BeltPressRole, Double>> capturePositiveKineticSpeeds() {
        for (BeltPressRole role : List.of(
                BeltPressRole.BELT_WATER_WHEEL, BeltPressRole.PRESS_WATER_WHEEL)) {
            BlockEntity blockEntity = level.getBlockEntity(position(plan.placement(role).position()));
            if (blockEntity instanceof WaterWheelBlockEntity wheel && wheel.flowScore == 0) {
                wheel.determineAndApplyFlowScore();
            }
        }
        String beltFailure = validateInitializedBelt();
        if (beltFailure != null) {
            return adapterFailure(AdapterFailureCode.LIFECYCLE_NOT_READY, beltFailure);
        }
        EnumMap<BeltPressRole, Double> speeds = new EnumMap<>(BeltPressRole.class);
        for (BeltPressRole role : BeltPressRole.values()) {
            if (!role.isKinetic()) {
                continue;
            }
            BeltPressPlacement placement = plan.placement(role);
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
                String diagnostic = role == BeltPressRole.BELT_WATER_WHEEL
                        ? waterWheelDiagnostic(
                                BeltPressRole.BELT_WATER_WHEEL,
                                BeltPressRole.BELT_WATER_SOURCE)
                        : role == BeltPressRole.PRESS_WATER_WHEEL
                                ? waterWheelDiagnostic(
                                        BeltPressRole.PRESS_WATER_WHEEL,
                                        BeltPressRole.PRESS_WATER_SOURCE)
                                : "";
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " has not received power" + lifecycle + diagnostic);
            }
            speeds.put(role, Math.abs((double) speed));
        }
        BeltBlockEntity start = beltSegment(BeltPressRole.BELT_START);
        Direction plannedMovement = direction(
                plan.placement(BeltPressRole.BELT_START).facing());
        if (start == null || start.getMovementFacing() != plannedMovement) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Powered belt is not moving from typed input toward typed output");
        }
        return new AdapterResult.Success<>(Map.copyOf(speeds));
    }

    private String waterWheelDiagnostic(BeltPressRole wheelRole, BeltPressRole sourceRole) {
        BlockPos wheelPosition = position(plan.placement(wheelRole).position());
        BlockPos sourcePosition = position(plan.placement(sourceRole).position());
        BlockPos flowPosition = sourcePosition.below();
        BlockEntity entity = level.getBlockEntity(wheelPosition);
        int flowScore = entity instanceof WaterWheelBlockEntity wheel ? wheel.flowScore : Integer.MIN_VALUE;
        return " [flowScore=" + flowScore
                + ", wheelState=" + level.getBlockState(wheelPosition)
                + ", source=" + level.getFluidState(sourcePosition)
                + ", flow=" + level.getFluidState(flowPosition)
                + ", flowVector=" + level.getFluidState(flowPosition).getFlow(level, flowPosition)
                + ", north=" + fluidDiagnostic(wheelPosition.north())
                + ", south=" + fluidDiagnostic(wheelPosition.south())
                + ", up=" + fluidDiagnostic(wheelPosition.above())
                + ", down=" + fluidDiagnostic(wheelPosition.below())
                + "]";
    }

    private String fluidDiagnostic(BlockPos position) {
        return level.getFluidState(position) + "/"
                + level.getFluidState(position).getFlow(level, position);
    }

    private String validateInitializedBelt() {
        BlockPos expectedController = position(
                plan.placement(BeltPressRole.BELT_START).position());
        for (int index = 0; index < BELT_ROLES.size(); index++) {
            BeltBlockEntity segment = beltSegment(BELT_ROLES.get(index));
            if (segment == null) {
                return BELT_ROLES.get(index) + " belt block entity is still initializing";
            }
            if (!expectedController.equals(segment.getController())
                    || segment.beltLength != BELT_ROLES.size()
                    || segment.index != index) {
                return BELT_ROLES.get(index) + " has not joined the fixed three-segment belt";
            }
        }
        return beltController() == null
                ? "Fixed belt controller is still initializing"
                : null;
    }

    private BeltBlockEntity beltSegment(BeltPressRole role) {
        BlockEntity blockEntity = level.getBlockEntity(position(plan.placement(role).position()));
        return blockEntity instanceof BeltBlockEntity value ? value : null;
    }

    private BeltBlockEntity beltController() {
        return BeltHelper.getControllerBE(
                level,
                position(plan.placement(BeltPressRole.BELT_START).position()));
    }

    private MechanicalPressBlockEntity mechanicalPress() {
        BlockEntity blockEntity = level.getBlockEntity(position(
                plan.placement(BeltPressRole.MECHANICAL_PRESS).position()));
        return blockEntity instanceof MechanicalPressBlockEntity value ? value : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity blockEntity = level.getBlockEntity(position(
                plan.placement(BeltPressRole.OUTPUT_CHEST).position()));
        return blockEntity instanceof ChestBlockEntity value ? value : null;
    }

    private String buildStepMismatch(BeltPressBuildStep.PlaceBlock place) {
        BlockState state = level.getBlockState(position(place.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(place.materialId()).equals(actual)) {
            return "Build-step readback mismatch at " + place.position()
                    + ": expected " + place.materialId() + " found " + actual;
        }
        if (place.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state) != axis(place.rotationAxis())) {
            return "Build-step rotation-axis mismatch at " + place.position();
        }
        if (place.facing() != PlanBlockFacing.NONE
                && stateFacing(state) != direction(place.facing())) {
            return "Build-step facing mismatch at " + place.position();
        }
        return null;
    }

    private String finalPlacementMismatch(BeltPressPlacement placement) {
        BlockState state = level.getBlockState(position(placement.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "Final placement mismatch at " + placement.position()
                    + ": expected " + placement.blockId() + " found " + actual;
        }
        if (placement.role().isBelt()) {
            if (!(state.getBlock() instanceof BeltBlock belt)
                    || state.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL
                    || state.getValue(BeltBlock.PART) != expectedBeltPart(placement.role())
                    || state.getValue(BeltBlock.HORIZONTAL_FACING)
                    != direction(placement.facing())
                    || belt.getRotationAxis(state) != axis(placement.rotationAxis())) {
                return "Final belt state mismatch at " + placement.position()
                        + " for " + placement.role();
            }
            return null;
        }
        if (placement.role() == BeltPressRole.OUTPUT_FUNNEL
                && (!(state.getBlock() instanceof FunnelBlock)
                        || state.getValue(FunnelBlock.EXTRACTING)
                        || state.getValue(AbstractFunnelBlock.POWERED))) {
            return "Final output funnel is not in unpowered insertion mode at "
                    + placement.position();
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state) != axis(placement.rotationAxis())) {
            return "Final rotation-axis mismatch at " + placement.position();
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state) != direction(placement.facing())) {
            return "Final facing mismatch at " + placement.position();
        }
        return null;
    }

    private static String validateLiveRecipe(
            PressingRecipe recipe,
            PressingProcessSpec spec) {
        if (!key(spec.recipeId()).equals(recipe.getId())) {
            return "Live pressing recipe mismatch: expected " + spec.recipeId()
                    + " found " + recipe.getId();
        }
        if (!key(spec.recipeType()).equals(AllRecipeTypes.PRESSING.getId())
                || recipe.getType() != AllRecipeTypes.PRESSING.getType()) {
            return "Live recipe is not Create pressing type " + spec.recipeType();
        }
        if (recipe.getProcessingDuration() < 0
                || recipe.getProcessingDuration() > spec.processingTimeoutTicks()) {
            return "Live pressing recipe duration is outside the typed timeout: "
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

    private static ItemObservation observeBeltItems(
            BeltBlockEntity controller,
            PressingProcessSpec spec) {
        int inputCount = 0;
        int outputCount = 0;
        ResourceId unexpected = null;
        for (TransportedItemStack transported : controller.getInventory().getTransportedItems()) {
            ItemStack stack = transported.stack;
            if (stack.isEmpty()) {
                continue;
            }
            ResourceId itemId = itemId(stack);
            if (spec.inputItem().equals(itemId)) {
                inputCount += stack.getCount();
            } else if (spec.expectedOutputItem().equals(itemId)) {
                outputCount += stack.getCount();
            } else {
                unexpected = itemId;
            }
        }
        return new ItemObservation(inputCount, outputCount, unexpected);
    }

    private static ItemObservation observeChestItems(
            ChestBlockEntity chest,
            PressingProcessSpec spec) {
        int inputCount = 0;
        int outputCount = 0;
        ResourceId unexpected = null;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceId itemId = itemId(stack);
            if (spec.inputItem().equals(itemId)) {
                inputCount += stack.getCount();
            } else if (spec.expectedOutputItem().equals(itemId)) {
                outputCount += stack.getCount();
            } else {
                unexpected = itemId;
            }
        }
        return new ItemObservation(inputCount, outputCount, unexpected);
    }

    private static String speedValue(Map<BeltPressRole, Double> speeds) {
        StringBuilder value = new StringBuilder();
        for (BeltPressRole role : BeltPressRole.values()) {
            if (!role.isKinetic()) {
                continue;
            }
            if (!value.isEmpty()) {
                value.append(';');
            }
            value.append(role.name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(speeds.get(role));
        }
        return value.toString();
    }

    private static boolean matches(
            StepActionDescriptor action,
            StepRunnerContext context,
            ResourceId operationId,
            ResourceId stepId) {
        return action.operationId().equals(operationId)
                && context.stepId().equals(stepId);
    }

    private static String validateTypedState(
            BeltPressBuildStep.PlaceBlock place,
            BlockState state) {
        if (place.facing() != PlanBlockFacing.NONE && !hasFacing(state)) {
            return place.materialId()
                    + " has no facing property required by the typed C-04 plan";
        }
        if (place.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)) {
            return place.materialId()
                    + " has no rotation property required by the typed C-04 plan";
        }
        if (place.rotationAxis() != PlanBlockAxis.NONE
                && place.facing() != PlanBlockFacing.NONE
                && direction(place.facing()).getAxis() != axis(place.rotationAxis())) {
            return place.materialId() + " typed facing and rotation axis disagree";
        }
        return null;
    }

    private static BlockState applyTypedState(
            BeltPressBuildStep.PlaceBlock place,
            BlockState state) {
        if ((place.role() == dev.stevecreate.agent.core.plan.BeltPressBuildRole.BELT_WATER_WHEEL
                || place.role() == dev.stevecreate.agent.core.plan.BeltPressBuildRole.PRESS_WATER_WHEEL)) {
            state = state.setValue(BlockStateProperties.FACING,
                    place.rotationAxis() == PlanBlockAxis.X ? Direction.EAST : Direction.SOUTH);
        } else if (place.rotationAxis() != PlanBlockAxis.NONE
                && state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(
                    BlockStateProperties.AXIS,
                    axis(place.rotationAxis()));
        }
        if (place.facing() != PlanBlockFacing.NONE) {
            Direction facing = direction(place.facing());
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            } else {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        return state;
    }

    private static boolean hasFacing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                || state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static Direction stateFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
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

    private static BeltPart expectedBeltPart(BeltPressRole role) {
        return switch (role) {
            case BELT_START -> BeltPart.START;
            case BELT_END -> BeltPart.END;
            case BELT_PRESSING -> BeltPart.MIDDLE;
            default -> throw new IllegalArgumentException(role + " is not a belt role");
        };
    }

    private static boolean shaftWithAxis(
            ServerLevel level,
            BlockPos position,
            Direction.Axis expectedAxis) {
        BlockState state = level.getBlockState(position);
        return key(ResourceId.parse("create:shaft"))
                        .equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))
                && state.hasProperty(BlockStateProperties.AXIS)
                && state.getValue(BlockStateProperties.AXIS) == expectedAxis;
    }

    private static boolean itemMatches(ItemStack stack, ResourceId expected) {
        return expected.equals(itemId(stack));
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation actual = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return actual == null
                ? null
                : new ResourceId(actual.getNamespace(), actual.getPath());
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
            case NONE -> throw new IllegalArgumentException("NONE has no Minecraft direction");
        };
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
                "Create 6.0.6 belt/press handler: " + stripPrefix(detail));
    }

    private static String stripPrefix(String detail) {
        String prefix = "Create 6.0.6 belt/press handler: ";
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

    private record ItemObservation(
            int inputCount,
            int outputCount,
            ResourceId unexpectedItem) {
    }
}
