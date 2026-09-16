package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.FanProcessingEvidence;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.FanProcessingGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import dev.stevecreate.agent.core.plan.FanProcessingPlacement;
import dev.stevecreate.agent.core.plan.FanProcessingPlan;
import dev.stevecreate.agent.core.plan.FanProcessingRole;
import dev.stevecreate.agent.core.plan.FanProcessingSpec;
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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 fan-processing actions behind the bounded C-06 runner. */
final class Create606FanProcessingActionHandler implements StepActionHandler {
    private static final ResourceId INTEGER_VALUE = id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE = id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE = id("steve_industrial:value/recipe");
    private static final ResourceId KINETIC_VALUE =
            id("create:value/signed_rpm_capacity_load");
    private static final ResourceId AIRFLOW_VALUE = id("create:value/airflow");
    private static final ResourceId TICK_RANGE_VALUE =
            id("steve_industrial:value/tick_range");

    private final ServerLevel level;
    private final FanProcessingPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;

    private int placementIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private boolean inputObserved;
    private boolean liveRecipeVerified;
    private FanProcessingEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606FanProcessingActionHandler(
            ServerLevel level,
            FanProcessingPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, 0, null);
    }

    Create606FanProcessingActionHandler(
            ServerLevel level,
            FanProcessingPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(
                level, plan, runtime, sessionId,
                WorldChangeJournal.empty(sessionId), 0, 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"));
    }

    Create606FanProcessingActionHandler(
            ServerLevel level,
            FanProcessingPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal journal,
            int placementIndex,
            int pilotFlowPlacementIndex,
            Create606WorldResourceBuffer resourceBuffer) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (placementIndex < 0 || placementIndex > plan.placements().size()) {
            throw new IllegalArgumentException("Recovered C-06 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        if (pilotFlowPlacementIndex < 0 || pilotFlowPlacementIndex > 2) {
            throw new IllegalArgumentException(
                    "Recovered C-06 pilot-flow cursor is outside the bounded channel");
        }
        this.pilotFlowPlacementIndex = pilotFlowPlacementIndex;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level, sessionId, Objects.requireNonNull(journal, "journal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(ServerLevel level, FanProcessingPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (FanProcessingPlacement placement : plan.placements()) {
            Block block = registeredBlock(placement.blockId());
            if (block == null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing runtime block for " + placement));
            }
            if (!level.getBlockState(position(placement.position())).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Plan target is not replaceable at " + placement.position()));
            }
            String stateFailure = validateTypedState(placement, block.defaultBlockState());
            if (stateFailure != null) {
                return Optional.of(detail(AdapterFailureCode.PLAN_REJECTED, stateFailure));
            }
        }
        BlockPos3i processing = plan.inputEntityPosition();
        if (!level.getBlockState(position(processing)).canBeReplaced()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-06 depot processing volume is obstructed at " + processing));
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
                    "C-06 action handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(
                    AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(FanProcessingGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-06 action descriptor did not match the registered v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "C-06 preflight area unloaded during execution: " + chunk.x + "," + chunk.z);
        }
        if (matches(
                action, context,
                FanProcessingGenericExecutionPlan.BUILD_OPERATION_ID,
                FanProcessingGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        String physicalFailure = physicalContractFailure();
        if (physicalFailure != null) {
            return fail(AdapterFailureCode.PLAN_REJECTED, physicalFailure);
        }
        if (matches(
                action, context,
                FanProcessingGenericExecutionPlan.POWER_OPERATION_ID,
                FanProcessingGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(
                action, context,
                FanProcessingGenericExecutionPlan.FEED_OPERATION_ID,
                FanProcessingGenericExecutionPlan.FEED_STEP_ID)) {
            return feedDepot(context);
        }
        if (matches(
                action, context,
                FanProcessingGenericExecutionPlan.PROCESS_OPERATION_ID,
                FanProcessingGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-06 operation and step identity did not match the fixed generic plan");
    }

    int placementIndex() {
        return placementIndex;
    }

    Optional<FanProcessingEvidence> completedEvidence() {
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
        FanProcessingPlacement placement = plan.placements().get(placementIndex);
        BlockPos target = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-06 placement target changed at " + placement.position());
        }
        Block block = registeredBlock(placement.blockId());
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Runtime C-06 block disappeared: " + placement.blockId());
        }
        BlockState state;
        try {
            state = applyTypedState(placement, block.defaultBlockState());
        } catch (IllegalArgumentException exception) {
            return fail(AdapterFailureCode.PLAN_REJECTED, exception.getMessage());
        }
        if (!level.setBlockAndUpdate(target, state)) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected C-06 placement " + placement);
        }
        if (placement.role() == FanProcessingRole.WATER_SOURCE) {
            level.scheduleTick(target, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        worldChanges.recordBlockChange(context, target, before);
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

    /**
     * The isolated executor pilot does not wait on vanilla fluid propagation: exactly
     * like C-03, it writes the two bounded flowing cells one per runner invocation.
     * They are world-change-journal owned but are not extra billable source blocks.
     */
    private ActionHandlerResult placePilotFlowCell(StepRunnerContext context) {
        if (!pilotFlowRequired()) return buildSucceeded(context);
        BlockPos source = position(plan.placement(FanProcessingRole.WATER_SOURCE).position());
        BlockPos target = source.below(pilotFlowPlacementIndex + 1);
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "C-06 pilot flowing-water target changed at " + target.toShortString());
        }
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        if (!level.setBlockAndUpdate(target, flowing)) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected bounded C-06 flowing-water placement at "
                            + target.toShortString());
        }
        worldChanges.recordBlockChange(context, target, before);
        pilotFlowPlacementIndex++;
        if (pilotFlowRequired()) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private boolean pilotFlowRequired() {
        return resourceBuffer != null
                && Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                && pilotFlowPlacementIndex < 2;
    }

    private ActionHandlerResult buildSucceeded(StepRunnerContext context) {
        String failure = physicalContractFailure();
        if (failure != null) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED, failure);
        }
        return success(evidence(
                id("create:c06/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                FanProcessingGenericExecutionPlan.BUILD_EVIDENCE,
                context,
                FanProcessingGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.placements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(StepRunnerContext context) {
        AdapterResult<AirflowSnapshot> result = captureAirflow();
        if (result instanceof AdapterResult.Failure<AirflowSnapshot> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        AirflowSnapshot snapshot =
                ((AdapterResult.Success<AirflowSnapshot>) result).value();
        return success(evidence(
                id("create:c06/observation/airflow_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                FanProcessingGenericExecutionPlan.POWER_EVIDENCE,
                context,
                FanProcessingGenericExecutionPlan.FAN_NODE_ID,
                AIRFLOW_VALUE,
                snapshot.direction().getName() + ";reach=" + snapshot.reach(),
                AIRFLOW_VALUE,
                direction(plan.airflowFacing()).getName()
                        + ";reach>=" + plan.process().minimumAirflowReachBlocks()));
    }

    private ActionHandlerResult feedDepot(StepRunnerContext context) {
        AdapterResult<AirflowSnapshot> airflow = captureAirflow();
        if (airflow instanceof AdapterResult.Failure<AirflowSnapshot> failure) {
            return fail(failure.code(), failure.detail());
        }
        DepotBlockEntity depot = depot();
        ChestBlockEntity chest = outputChest();
        if (depot == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 depot or output chest disappeared before feed");
        }
        if (!depot.getHeldItem().isEmpty() || !chest.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 depot and output chest must be empty before feed");
        }
        FanProcessingSpec spec = plan.process();
        Item input = registeredItem(spec.inputItem());
        if (input == null) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Missing runtime C-06 input item " + spec.inputItem());
        }
        ItemStack offered = new ItemStack(input, spec.inputCount());
        String recipeFailure = validateLiveRecipe(offered);
        if (recipeFailure != null) {
            return fail(AdapterFailureCode.RECIPE_NOT_FOUND, recipeFailure);
        }
        liveRecipeVerified = true;
        if (resourceBuffer != null) {
            AdapterResult<ItemStack> extracted =
                    resourceBuffer.extractExact(spec.inputItem(), spec.inputCount());
            if (extracted instanceof AdapterResult.Failure<ItemStack> failure) {
                return fail(failure.code(), failure.detail());
            }
            offered = ((AdapterResult.Success<ItemStack>) extracted).value();
        }
        depot.setHeldItem(offered.copy());
        if (!matches(depot.getHeldItem(), spec.inputItem(), spec.inputCount())) {
            depot.setHeldItem(ItemStack.EMPTY);
            if (resourceBuffer != null) resourceBuffer.insertExact(offered);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 depot rejected exact typed input staging");
        }
        inputObserved = true;
        feedTick = context.gameTick();
        worldChanges.recordInjectedResource(
                context,
                position(plan.placement(FanProcessingRole.INPUT_DEPOT).position()),
                new ProcessResource(
                        spec.inputItem(), GenericResourceType.ITEM, spec.inputCount()));
        return success(evidence(
                id("create:c06/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                FanProcessingGenericExecutionPlan.FEED_EVIDENCE,
                context,
                FanProcessingGenericExecutionPlan.INPUT_NODE_ID,
                ITEM_STACK_VALUE,
                spec.inputItem() + " x" + spec.inputCount(),
                ITEM_STACK_VALUE,
                spec.inputItem() + " x" + spec.inputCount()));
    }

    private ActionHandlerResult observeProcessing(StepRunnerContext context) {
        AdapterResult<AirflowSnapshot> airflowResult = captureAirflow();
        if (airflowResult instanceof AdapterResult.Failure<AirflowSnapshot> failure) {
            return fail(failure.code(), failure.detail());
        }
        AirflowSnapshot airflow =
                ((AdapterResult.Success<AirflowSnapshot>) airflowResult).value();
        DepotBlockEntity depot = depot();
        ChestBlockEntity chest = outputChest();
        if (depot == null || chest == null || feedTick < 0
                || !inputObserved || !liveRecipeVerified) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 processing state lacks the real feed, depot, chest or live recipe");
        }
        ItemStack held = depot.getHeldItem();
        if (matches(held, plan.process().inputItem(), plan.process().inputCount())) {
            return ActionHandlerResult.inProgress(false);
        }
        if (held.isEmpty()) {
            return ActionHandlerResult.inProgress(false);
        }
        ResourceId observedItem = itemId(held);
        if (!plan.process().expectedOutputItem().equals(observedItem)
                || held.getCount() < plan.process().minimumOutputCount()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 depot changed to unexpected runtime output "
                            + observedItem + " x" + held.getCount());
        }
        if (!chest.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 output chest changed before the real Depot output transfer");
        }
        ItemStack realOutput = held.copy();
        ItemStack remainder = ItemHandlerHelper.insertItem(
                new InvWrapper(chest), realOutput, false);
        int inserted = realOutput.getCount() - remainder.getCount();
        if (inserted < plan.process().minimumOutputCount()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 output chest lacks capacity for the real processed output");
        }
        depot.setHeldItem(remainder);
        int chestCount = countChest(chest, plan.process().expectedOutputItem());
        int dwell = Math.toIntExact(context.gameTick() - feedTick);
        if (dwell < 1 || chestCount < plan.process().minimumOutputCount()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 real output lacks positive dwell or chest readback");
        }
        boolean botsExcluded = dangerousAreaClear();
        if (plan.process().mode().dangerousToBots() && !botsExcluded) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-06 dangerous medium or hot-air lane contains a living entity");
        }
        completedEvidence = new FanProcessingEvidence(
                feedTick,
                context.gameTick(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                plan.origin(),
                plan.process().mode(),
                plan.placements(),
                airflow.speeds(),
                airflow.direction().getName(),
                airflow.reach(),
                plan.process().mode().mediumBlock(),
                plan.process().recipeId(),
                plan.process().recipeType(),
                plan.process().inputItem(),
                plan.process().inputCount(),
                plan.process().expectedOutputItem(),
                chestCount,
                dwell,
                true,
                true,
                botsExcluded);
        worldChanges.recordIrreversibleProcessing(
                context,
                plan.process().recipeId(),
                plan.process().genericSpec().inputs(),
                List.of(new ProcessResource(
                        plan.process().expectedOutputItem(),
                        GenericResourceType.ITEM,
                        chestCount)),
                plan.placements().stream().map(FanProcessingPlacement::position).toList());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private AdapterResult<AirflowSnapshot> captureAirflow() {
        BlockEntity source = level.getBlockEntity(position(
                plan.placement(FanProcessingRole.WATER_WHEEL).position()));
        if (source instanceof WaterWheelBlockEntity waterWheel && waterWheel.flowScore == 0) {
            waterWheel.determineAndApplyFlowScore();
        }
        EnumMap<FanProcessingRole, Double> speeds =
                new EnumMap<>(FanProcessingRole.class);
        Long networkId = null;
        for (FanProcessingRole role : List.of(
                FanProcessingRole.WATER_WHEEL,
                FanProcessingRole.BOTTOM_GEARBOX,
                FanProcessingRole.VERTICAL_SHAFT,
                FanProcessingRole.TOP_GEARBOX,
                FanProcessingRole.FAN_DRIVE_SHAFT,
                FanProcessingRole.ENCASED_FAN)) {
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
            if (snapshot.networkId().isEmpty()) {
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " has no live Create network identity");
            }
            long observedNetwork = snapshot.networkId().orElseThrow();
            if (networkId == null) {
                networkId = observedNetwork;
            } else if (networkId.longValue() != observedNetwork) {
                return adapterFailure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "C-06 survival power roles do not share one live Create network");
            }
            speeds.put(
                    role,
                    snapshot.rotationDirection() == KineticRotationDirection.POSITIVE
                            ? snapshot.speedRpm()
                            : -snapshot.speedRpm());
        }
        EncasedFanBlockEntity fan = fan();
        if (fan == null) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 live EncasedFanBlockEntity is unavailable");
        }
        AirCurrent current = fan.getAirCurrent();
        Direction direction = fan.getAirFlowDirection();
        Direction expected = direction(plan.airflowFacing());
        if (current == null || direction == null || current.bounds == null
                || current.segments == null || current.segments.isEmpty()
                || current.maxDistance < plan.process().minimumAirflowReachBlocks()) {
            return adapterFailure(
                    AdapterFailureCode.LIFECYCLE_NOT_READY,
                    "C-06 live airflow has not reached the bounded depot processing cell");
        }
        if (direction != expected || current.direction != expected) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 live airflow direction differs from the typed fan-to-depot route");
        }
        Vec3 processing = Vec3.atCenterOf(position(plan.inputEntityPosition()));
        if (!current.bounds.inflate(0.05D).contains(processing)) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 airflow bounds do not contain the typed depot processing position");
        }
        FanProcessingType expectedType = fanType(plan.process().mode());
        BlockPos medium =
                position(plan.placement(FanProcessingRole.PROCESSING_MEDIUM).position());
        if (!expectedType.isValidAt(level, medium)
                || !plan.process().mode().mediumBlock().equals(blockId(medium))) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 exact live fan-processing medium is missing");
        }
        boolean typeObserved = false;
        for (float distance = 0.25F;
                distance <= current.maxDistance + 0.001F;
                distance += 0.25F) {
            if (current.getTypeAt(distance) == expectedType) {
                typeObserved = true;
                break;
            }
        }
        if (!typeObserved) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-06 AirCurrent did not classify the exact typed medium");
        }
        return new AdapterResult.Success<>(
                new AirflowSnapshot(Map.copyOf(speeds), direction, current.maxDistance));
    }

    private String validateLiveRecipe(ItemStack offered) {
        Recipe<?> recipe = level.getRecipeManager()
                .byKey(key(plan.process().recipeId()))
                .orElse(null);
        if (recipe == null) {
            return "Live C-06 recipe is absent: " + plan.process().recipeId();
        }
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (!key(plan.process().recipeType()).equals(typeId)) {
            return "Live C-06 recipe type differs: expected "
                    + plan.process().recipeType() + " found " + typeId;
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() != 1 || !ingredients.get(0).test(offered)) {
            return "Live C-06 recipe does not accept the exact typed input";
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (!plan.process().expectedOutputItem().equals(itemId(result))
                || result.getCount() < plan.process().minimumOutputCount()) {
            return "Live C-06 recipe result differs from the typed guaranteed output";
        }
        if (recipe instanceof ProcessingRecipe<?> processing) {
            List<ProcessingOutput> outputs = processing.getRollableResults();
            if (outputs.size() != 1
                    || outputs.get(0).getChance() != 1.0F
                    || !plan.process().expectedOutputItem().equals(
                            itemId(outputs.get(0).getStack()))
                    || outputs.get(0).getStack().getCount()
                            < plan.process().minimumOutputCount()) {
                return "C-06 Phase I requires exactly one guaranteed Create output";
            }
        } else if (!(recipe instanceof AbstractCookingRecipe)
                || !AllRecipeTypes.CAN_BE_AUTOMATED.test(recipe)) {
            return "C-06 smoking/blasting requires an automatable vanilla cooking recipe";
        }
        if (!fanType(plan.process().mode()).canProcess(offered, level)) {
            return "Live Create fan type rejected the exact C-06 input";
        }
        return null;
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            FanProcessingEvidence physical) {
        String airflow = physical.observedAirflowDirection()
                + ";reach=" + physical.observedAirflowReach();
        return List.of(
                evidence(
                        id("create:c06/observation/input_consumed"),
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        FanProcessingGenericExecutionPlan.INPUT_CONSUMED,
                        context,
                        FanProcessingGenericExecutionPlan.INPUT_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.consumedInputCount()),
                        INTEGER_VALUE,
                        Integer.toString(plan.process().inputCount())),
                evidence(
                        id("create:c06/observation/process_completed"),
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        FanProcessingGenericExecutionPlan.PROCESS_COMPLETED,
                        context,
                        FanProcessingGenericExecutionPlan.FAN_NODE_ID,
                        TICK_RANGE_VALUE,
                        physical.feedTick() + ".." + physical.completionTick(),
                        RECIPE_VALUE,
                        physical.recipeId().toString()),
                evidence(
                        id("create:c06/observation/output_produced"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        FanProcessingGenericExecutionPlan.OUTPUT_PRODUCED,
                        context,
                        FanProcessingGenericExecutionPlan.FAN_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.observedOutputCount()),
                        INTEGER_VALUE,
                        ">=" + plan.process().minimumOutputCount()),
                evidence(
                        id("create:c06/observation/airflow"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        FanProcessingGenericExecutionPlan.AIRFLOW_OBSERVED,
                        context,
                        FanProcessingGenericExecutionPlan.FAN_NODE_ID,
                        AIRFLOW_VALUE,
                        airflow,
                        AIRFLOW_VALUE,
                        direction(plan.airflowFacing())
                                .getName()
                                + ";reach>=" + plan.process().minimumAirflowReachBlocks()),
                evidence(
                        id("create:c06/observation/medium"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        FanProcessingGenericExecutionPlan.MEDIUM_OBSERVED,
                        context,
                        FanProcessingGenericExecutionPlan.MEDIUM_NODE_ID,
                        RECIPE_VALUE,
                        physical.observedMediumBlock().toString(),
                        RECIPE_VALUE,
                        plan.process().mode().mediumBlock().toString()),
                evidence(
                        id("create:c06/observation/dwell"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        FanProcessingGenericExecutionPlan.DWELL_OBSERVED,
                        context,
                        FanProcessingGenericExecutionPlan.FAN_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.observedDwellTicks()),
                        INTEGER_VALUE,
                        ">=1"),
                evidence(
                        id("create:c06/observation/chest_output"),
                        VerificationEvidenceKind.OUTPUT_STORED,
                        FanProcessingGenericExecutionPlan.CHEST_OUTPUT_OBSERVED,
                        context,
                        FanProcessingGenericExecutionPlan.CHEST_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x" + physical.observedOutputCount(),
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x>="
                                + plan.process().minimumOutputCount()));
    }

    private String physicalContractFailure() {
        for (FanProcessingPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) return mismatch;
        }
        if (!(level.getBlockEntity(position(
                plan.placement(FanProcessingRole.ENCASED_FAN).position()))
                instanceof EncasedFanBlockEntity)) {
            return "C-06 live fan block entity is missing";
        }
        if (!(level.getBlockEntity(position(
                plan.placement(FanProcessingRole.INPUT_DEPOT).position()))
                instanceof DepotBlockEntity)) {
            return "C-06 live input depot is missing";
        }
        if (!(level.getBlockEntity(position(
                plan.placement(FanProcessingRole.OUTPUT_CHEST).position()))
                instanceof ChestBlockEntity)) {
            return "C-06 live output chest is missing";
        }
        return null;
    }

    private boolean dangerousAreaClear() {
        if (!plan.process().mode().dangerousToBots()) return true;
        for (BlockPos3i value : plan.botForbiddenPositions()) {
            AABB bounds = new AABB(position(value)).inflate(0.05D);
            if (!level.getEntitiesOfClass(
                    LivingEntity.class, bounds, Entity::isAlive).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String placementMismatch(FanProcessingPlacement placement) {
        BlockState state = level.getBlockState(position(placement.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "C-06 placement mismatch at " + placement.position()
                    + ": expected " + placement.blockId() + " found " + actual;
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state) != axis(placement.rotationAxis())) {
            return "C-06 placement axis mismatch at " + placement.position();
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state) != direction(placement.facing())) {
            return "C-06 placement facing mismatch at " + placement.position();
        }
        return null;
    }

    private static String validateTypedState(
            FanProcessingPlacement placement,
            BlockState state) {
        if (placement.role() == FanProcessingRole.WATER_WHEEL
                && placement.rotationAxis() == PlanBlockAxis.Y) {
            return placement.blockId() + " requires a horizontal C-06 axis";
        }
        if (placement.facing() != PlanBlockFacing.NONE && !hasFacing(state)) {
            return placement.blockId() + " has no facing property required by C-06";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)) {
            return placement.blockId() + " has no rotation property required by C-06";
        }
        return null;
    }

    private static BlockState applyTypedState(
            FanProcessingPlacement placement,
            BlockState state) {
        String failure = validateTypedState(placement, state);
        if (failure != null) throw new IllegalArgumentException(failure);
        if (placement.role() == FanProcessingRole.WATER_WHEEL) {
            Direction facing = switch (placement.rotationAxis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "C-06 water-wheel axis must be horizontal");
            };
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(
                    BlockStateProperties.AXIS,
                    axis(placement.rotationAxis()));
        }
        if (placement.facing() != PlanBlockFacing.NONE) {
            Direction facing = direction(placement.facing());
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing);
            } else {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }
        }
        return state;
    }

    private DepotBlockEntity depot() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(FanProcessingRole.INPUT_DEPOT).position()));
        return value instanceof DepotBlockEntity depot ? depot : null;
    }

    private EncasedFanBlockEntity fan() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(FanProcessingRole.ENCASED_FAN).position()));
        return value instanceof EncasedFanBlockEntity fan ? fan : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(FanProcessingRole.OUTPUT_CHEST).position()));
        return value instanceof ChestBlockEntity chest ? chest : null;
    }

    private int countChest(ChestBlockEntity chest, ResourceId expected) {
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceId actual = itemId(stack);
            if (!expected.equals(actual)) return -1;
            count = Math.addExact(count, stack.getCount());
        }
        return count;
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
                FanProcessingGenericExecutionPlan.ACTION_HANDLER_ID,
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

    private ActionHandlerResult fail(AdapterFailureCode code, String failureDetail) {
        String normalized = stripPrefix(failureDetail);
        lastFailure = detail(code, normalized);
        return ActionHandlerResult.failed(
                failureId(code),
                normalized,
                worldChanges.drainInvocationReferences());
    }

    private static boolean matches(
            StepActionDescriptor action,
            StepRunnerContext context,
            ResourceId operationId,
            ResourceId stepId) {
        return action.operationId().equals(operationId)
                && context.stepId().equals(stepId);
    }

    private static boolean matches(ItemStack stack, ResourceId item, int count) {
        return !stack.isEmpty()
                && item.equals(itemId(stack))
                && stack.getCount() == count;
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

    private static FanProcessingType fanType(FanProcessingMode mode) {
        return switch (mode) {
            case WASHING -> AllFanProcessingTypes.SPLASHING;
            case SMOKING -> AllFanProcessingTypes.SMOKING;
            case HAUNTING -> AllFanProcessingTypes.HAUNTING;
            case BLASTING -> AllFanProcessingTypes.BLASTING;
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

    private ResourceId blockId(BlockPos position) {
        ResourceLocation actual =
                ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock());
        return actual == null
                ? id("minecraft:air")
                : new ResourceId(actual.getNamespace(), actual.getPath());
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
                "Create 6.0.6 fan-processing handler: "
                        + stripPrefix(failureDetail));
    }

    private static String stripPrefix(String value) {
        String prefix = "Create 6.0.6 fan-processing handler: ";
        return value.startsWith(prefix)
                ? value.substring(prefix.length())
                : value;
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

    private record AirflowSnapshot(
            Map<FanProcessingRole, Double> speeds,
            Direction direction,
            float reach) {
        private AirflowSnapshot {
            speeds = Map.copyOf(speeds);
            Objects.requireNonNull(direction, "direction");
            if (!Float.isFinite(reach) || reach < 1.0F) {
                throw new IllegalArgumentException("Invalid C-06 airflow reach");
            }
        }
    }
}
