package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.saw.CuttingRecipe;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.MechanicalSawEvidence;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.CuttingProcessSpec;
import dev.stevecreate.agent.core.plan.MechanicalSawGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawPlacement;
import dev.stevecreate.agent.core.plan.MechanicalSawPlan;
import dev.stevecreate.agent.core.plan.MechanicalSawRole;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 item-only mechanical-saw actions behind the bounded C-07 runner. */
final class Create606MechanicalSawActionHandler implements StepActionHandler {
    private static final ResourceId INTEGER_VALUE = id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE = id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE = id("steve_industrial:value/recipe");
    private static final ResourceId KINETIC_VALUE =
            id("create:value/signed_rpm_capacity_load");
    private static final ResourceId TICK_RANGE_VALUE =
            id("steve_industrial:value/tick_range");

    private final ServerLevel level;
    private final MechanicalSawPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;

    private int placementIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private UUID inputEntityId;
    private CuttingRecipe cuttingRecipe;
    private boolean depotInputObserved;
    private boolean sawCycleObserved;
    private MechanicalSawEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606MechanicalSawActionHandler(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, 0, null);
    }

    Create606MechanicalSawActionHandler(
            ServerLevel level,
            MechanicalSawPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(level, plan, runtime, sessionId, WorldChangeJournal.empty(sessionId), 0, 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"));
    }

    Create606MechanicalSawActionHandler(
            ServerLevel level,
            MechanicalSawPlan plan,
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
            throw new IllegalArgumentException("Recovered C-07 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        if (pilotFlowPlacementIndex < 0 || pilotFlowPlacementIndex > 2) {
            throw new IllegalArgumentException(
                    "Recovered C-07 pilot-flow cursor is outside the bounded channel");
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

    static Optional<FailureDetail> validatePlan(ServerLevel level, MechanicalSawPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (MechanicalSawPlacement placement : plan.placements()) {
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
        if (!level.getBlockState(position(plan.inputEntityPosition())).canBeReplaced()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-07 item lane is obstructed at " + plan.inputEntityPosition()));
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
                    "C-07 action handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(
                    AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(MechanicalSawGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-07 action descriptor did not match the registered v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "C-07 preflight area unloaded during execution: " + chunk.x + "," + chunk.z);
        }

        if (matches(action, context,
                MechanicalSawGenericExecutionPlan.BUILD_OPERATION_ID,
                MechanicalSawGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        String safetyFailure = upwardItemOnlyFailure();
        if (safetyFailure != null) {
            return fail(AdapterFailureCode.PLAN_REJECTED, safetyFailure);
        }
        if (matches(action, context,
                MechanicalSawGenericExecutionPlan.POWER_OPERATION_ID,
                MechanicalSawGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(action, context,
                MechanicalSawGenericExecutionPlan.FEED_OPERATION_ID,
                MechanicalSawGenericExecutionPlan.FEED_STEP_ID)) {
            return feedSaw(context);
        }
        if (matches(action, context,
                MechanicalSawGenericExecutionPlan.PROCESS_OPERATION_ID,
                MechanicalSawGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-07 operation and step identity did not match the fixed generic plan");
    }

    int placementIndex() {
        return placementIndex;
    }

    Optional<MechanicalSawEvidence> completedEvidence() {
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
        MechanicalSawPlacement placement = plan.placements().get(placementIndex);
        BlockPos target = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-07 placement target changed at " + placement.position());
        }
        Block block = registeredBlock(placement.blockId());
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Runtime C-07 block disappeared: " + placement.blockId());
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
                    "World rejected C-07 placement " + placement);
        }
        if (placement.role() == MechanicalSawRole.WATER_SOURCE) {
            level.scheduleTick(target, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        worldChanges.recordBlockChange(context, target, before);
        String mismatch = placementMismatch(placement);
        if (mismatch != null) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
        }
        if (placement.role() == MechanicalSawRole.MECHANICAL_SAW) {
            String safetyFailure = upwardItemOnlyFailure();
            if (safetyFailure != null) {
                return fail(AdapterFailureCode.PLAN_REJECTED, safetyFailure);
            }
        }
        placementIndex++;
        if (placementIndex < plan.placements().size() || pilotFlowRequired()) {
            return ActionHandlerResult.inProgress(
                    true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private ActionHandlerResult placePilotFlowCell(StepRunnerContext context) {
        if (!pilotFlowRequired()) return buildSucceeded(context);
        BlockPos source = position(plan.placement(MechanicalSawRole.WATER_SOURCE).position());
        BlockPos target = source.below(pilotFlowPlacementIndex + 1);
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "C-07 pilot flowing-water target changed at " + target.toShortString());
        }
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        if (!level.setBlockAndUpdate(target, flowing)) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected bounded C-07 flowing-water placement at "
                            + target.toShortString());
        }
        worldChanges.recordBlockChange(context, target, before);
        pilotFlowPlacementIndex++;
        if (pilotFlowRequired()) {
            return ActionHandlerResult.inProgress(true, worldChanges.drainInvocationReferences());
        }
        return buildSucceeded(context);
    }

    private boolean pilotFlowRequired() {
        return resourceBuffer != null
                && Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                && pilotFlowPlacementIndex < 2;
    }

    private ActionHandlerResult buildSucceeded(StepRunnerContext context) {
        for (MechanicalSawPlacement expected : plan.placements()) {
            String mismatch = placementMismatch(expected);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PLACEMENT_FAILED, mismatch);
            }
        }
        String safetyFailure = upwardItemOnlyFailure();
        if (safetyFailure != null) {
            return fail(AdapterFailureCode.PLAN_REJECTED, safetyFailure);
        }
        return success(evidence(
                id("create:c07/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                MechanicalSawGenericExecutionPlan.BUILD_EVIDENCE,
                context,
                MechanicalSawGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.placements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(StepRunnerContext context) {
        AdapterResult<Map<MechanicalSawRole, Double>> result = captureKinetics();
        if (result instanceof AdapterResult.Failure<Map<MechanicalSawRole, Double>> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        Map<MechanicalSawRole, Double> speeds =
                ((AdapterResult.Success<Map<MechanicalSawRole, Double>>) result).value();
        return success(evidence(
                id("create:c07/observation/power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                MechanicalSawGenericExecutionPlan.POWER_EVIDENCE,
                context,
                MechanicalSawGenericExecutionPlan.SAW_NODE_ID,
                KINETIC_VALUE,
                speeds.toString(),
                KINETIC_VALUE,
                "non_zero_not_overstressed"));
    }

    private ActionHandlerResult feedSaw(StepRunnerContext context) {
        AdapterResult<Map<MechanicalSawRole, Double>> speedResult = captureKinetics();
        if (speedResult instanceof AdapterResult.Failure<Map<MechanicalSawRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        DepotBlockEntity depot = depot();
        SawBlockEntity saw = saw();
        ChestBlockEntity chest = outputChest();
        if (depot == null || saw == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 depot, saw or output chest disappeared before feed");
        }
        if (!depot.getHeldItem().isEmpty()
                || !saw.inventory.isEmpty()
                || !chest.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 depot, saw and output chest must be empty before feed");
        }

        CuttingProcessSpec spec = plan.process();
        Item inputItem = registeredItem(spec.inputItem());
        if (inputItem == null) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Missing runtime C-07 input item " + spec.inputItem());
        }
        ItemStack offered = new ItemStack(inputItem, spec.inputCount());
        var exactRecipe = level.getRecipeManager().byKey(key(spec.recipeId()));
        if (exactRecipe.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live recipe with exact C-07 identity " + spec.recipeId());
        }
        if (!(exactRecipe.orElseThrow() instanceof CuttingRecipe selected)) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Exact C-07 recipe is not a Create cutting recipe " + spec.recipeId());
        }
        cuttingRecipe = selected;
        String recipeFailure = validateLiveRecipe(cuttingRecipe, spec, offered);
        if (recipeFailure != null) {
            return fail(AdapterFailureCode.RECIPE_NOT_FOUND, recipeFailure);
        }
        Item outputItem = registeredItem(spec.expectedOutputItem());
        FilteringBehaviour filtering = saw.getBehaviour(FilteringBehaviour.TYPE);
        if (outputItem == null || filtering == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 exact output filter is unavailable on the owned mechanical saw");
        }
        ItemStack expectedOutput = new ItemStack(outputItem, spec.minimumOutputCount());
        filtering.setFilter(expectedOutput.copy());
        if (!filtering.test(expectedOutput)
                || !matches(filtering.getFilter(), spec.expectedOutputItem(),
                        spec.minimumOutputCount())) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 owned mechanical saw rejected the exact typed output filter");
        }
        if (resourceBuffer != null) {
            AdapterResult<ItemStack> extracted =
                    resourceBuffer.extractExact(spec.inputItem(), spec.inputCount());
            if (extracted instanceof AdapterResult.Failure<ItemStack> failure) {
                return fail(failure.code(), failure.detail());
            }
            offered = ((AdapterResult.Success<ItemStack>) extracted).value();
        }

        depot.setHeldItem(offered.copy());
        ItemStack staged = depot.getHeldItem();
        if (!matches(staged, spec.inputItem(), spec.inputCount())) {
            depot.setHeldItem(ItemStack.EMPTY);
            if (resourceBuffer != null) resourceBuffer.insertExact(offered);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 depot rejected exact typed input staging");
        }
        depotInputObserved = true;
        depot.setHeldItem(ItemStack.EMPTY);
        BlockPos3i spawn = plan.inputEntityPosition();
        ItemEntity entity = new ItemEntity(
                level,
                spawn.x() + 0.5D,
                spawn.y() + 0.05D,
                spawn.z() + 0.5D,
                staged);
        if (!level.addFreshEntity(entity)) {
            if (resourceBuffer != null) resourceBuffer.insertExact(staged);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "World rejected the staged C-07 input entity");
        }
        inputEntityId = entity.getUUID();
        saw.insertItem(entity);
        if (entity.isAlive()
                || !matches(saw.inventory.getStackInSlot(0),
                        spec.inputItem(), spec.inputCount())) {
            if (entity.isAlive()) entity.discard();
            if (resourceBuffer != null && saw.inventory.isEmpty()) {
                resourceBuffer.insertExact(staged);
            }
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Create 6.0.6 saw did not consume the real staged item entity");
        }
        feedTick = level.getGameTime();
        worldChanges.recordInjectedResource(
                context, position(spawn), spec.genericSpec().inputs().get(0));
        String stack = spec.inputItem() + " x" + spec.inputCount();
        return success(evidence(
                id("create:c07/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                MechanicalSawGenericExecutionPlan.FEED_EVIDENCE,
                context,
                MechanicalSawGenericExecutionPlan.DEPOT_NODE_ID,
                ITEM_STACK_VALUE,
                stack + " depot=true entity=" + inputEntityId,
                ITEM_STACK_VALUE,
                stack));
    }

    private ActionHandlerResult observeProcessing(StepRunnerContext context) {
        AdapterResult<Map<MechanicalSawRole, Double>> speedResult = captureKinetics();
        if (speedResult instanceof AdapterResult.Failure<Map<MechanicalSawRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        Map<MechanicalSawRole, Double> speeds =
                ((AdapterResult.Success<Map<MechanicalSawRole, Double>>) speedResult).value();
        SawBlockEntity saw = saw();
        ChestBlockEntity chest = outputChest();
        if (saw == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 saw or output chest disappeared during processing");
        }
        if (saw.inventory.recipeDuration > 0
                || saw.inventory.remainingTime >= 0
                || saw.inventory.appliedRecipe) {
            sawCycleObserved = true;
        }
        String transferFailure = transferRealOutputs(chest);
        if (transferFailure != null) {
            return fail(AdapterFailureCode.PROCESSING_FAILED, transferFailure);
        }
        OutputObservation output = observeChest(chest, plan.process());
        if (output.unexpectedItem() != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 output chest contains unexpected item " + output.unexpectedItem());
        }
        boolean inputStillPresent = inputEntityId != null && entityPresent(inputEntityId);
        if (inputStillPresent
                || !saw.inventory.isEmpty()
                || output.count() < plan.process().minimumOutputCount()) {
            return ActionHandlerResult.inProgress(false);
        }
        if (!depotInputObserved || !sawCycleObserved) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 completion lacks depot or live saw-cycle evidence");
        }
        for (MechanicalSawPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) {
                return fail(AdapterFailureCode.PROCESSING_FAILED, mismatch);
            }
        }
        completedEvidence = new MechanicalSawEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                plan.origin(),
                plan.placements(),
                speeds,
                plan.process().recipeId(),
                plan.process().recipeType(),
                cuttingRecipe.getProcessingDuration(),
                plan.process().inputItem(),
                plan.process().inputCount(),
                plan.process().expectedOutputItem(),
                output.count(),
                true,
                true,
                true,
                true);
        worldChanges.recordIrreversibleProcessing(
                context,
                plan.process().recipeId(),
                plan.process().genericSpec().inputs(),
                List.of(new ProcessResource(
                        plan.process().expectedOutputItem(),
                        GenericResourceType.ITEM,
                        output.count())),
                plan.placements().stream().map(MechanicalSawPlacement::position).toList());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private String transferRealOutputs(ChestBlockEntity chest) {
        BlockPos sawPosition = position(
                plan.placement(MechanicalSawRole.MECHANICAL_SAW).position());
        List<ItemEntity> entities = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(sawPosition).inflate(2.0D, 2.0D, 2.0D),
                Entity::isAlive);
        for (ItemEntity entity : entities) {
            ItemStack stack = entity.getItem();
            ResourceId item = itemId(stack);
            if (item == null || !item.equals(plan.process().expectedOutputItem())) {
                return "C-07 item lane contains unexpected live entity " + item;
            }
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    new InvWrapper(chest), stack.copy(), false);
            int inserted = stack.getCount() - remainder.getCount();
            if (inserted == 0) continue;
            if (remainder.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(remainder);
            }
        }
        return null;
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            MechanicalSawEvidence physical) {
        return List.of(
                evidence(
                        id("create:c07/observation/input_consumed"),
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        MechanicalSawGenericExecutionPlan.INPUT_CONSUMED,
                        context,
                        MechanicalSawGenericExecutionPlan.SAW_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.consumedInputCount()),
                        INTEGER_VALUE,
                        Integer.toString(plan.process().inputCount())),
                evidence(
                        id("create:c07/observation/process_completed"),
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        MechanicalSawGenericExecutionPlan.PROCESS_COMPLETED,
                        context,
                        MechanicalSawGenericExecutionPlan.SAW_NODE_ID,
                        TICK_RANGE_VALUE,
                        physical.feedTick() + ".." + physical.completionTick(),
                        RECIPE_VALUE,
                        physical.recipeId() + " duration=" + physical.recipeProcessingDuration()),
                evidence(
                        id("create:c07/observation/output_produced"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        MechanicalSawGenericExecutionPlan.OUTPUT_PRODUCED,
                        context,
                        MechanicalSawGenericExecutionPlan.SAW_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(physical.observedOutputCount()),
                        INTEGER_VALUE,
                        ">=" + plan.process().minimumOutputCount()),
                evidence(
                        id("create:c07/observation/depot_input"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        MechanicalSawGenericExecutionPlan.DEPOT_INPUT_OBSERVED,
                        context,
                        MechanicalSawGenericExecutionPlan.DEPOT_NODE_ID,
                        ITEM_STACK_VALUE,
                        plan.process().inputItem() + " x" + plan.process().inputCount(),
                        ITEM_STACK_VALUE,
                        "real_depot_staging"),
                evidence(
                        id("create:c07/observation/saw_cycle"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        MechanicalSawGenericExecutionPlan.SAW_CYCLE_OBSERVED,
                        context,
                        MechanicalSawGenericExecutionPlan.SAW_NODE_ID,
                        RECIPE_VALUE,
                        physical.recipeId() + ";facing=up;world_cutting=false",
                        RECIPE_VALUE,
                        "create:cutting;facing=up;world_cutting=false"),
                evidence(
                        id("create:c07/observation/chest_output"),
                        VerificationEvidenceKind.OUTPUT_STORED,
                        MechanicalSawGenericExecutionPlan.CHEST_OUTPUT_OBSERVED,
                        context,
                        MechanicalSawGenericExecutionPlan.CHEST_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x" + physical.observedOutputCount(),
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x>="
                                + plan.process().minimumOutputCount()));
    }

    private AdapterResult<Map<MechanicalSawRole, Double>> captureKinetics() {
        EnumMap<MechanicalSawRole, Double> speeds =
                new EnumMap<>(MechanicalSawRole.class);
        for (MechanicalSawRole role : List.of(
                MechanicalSawRole.WATER_WHEEL,
                MechanicalSawRole.BOTTOM_GEARBOX,
                MechanicalSawRole.VERTICAL_SHAFT,
                MechanicalSawRole.TOP_GEARBOX,
                MechanicalSawRole.HORIZONTAL_SHAFT,
                MechanicalSawRole.MECHANICAL_SAW)) {
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
            speeds.put(
                    role,
                    snapshot.rotationDirection() == KineticRotationDirection.POSITIVE
                            ? snapshot.speedRpm()
                            : -snapshot.speedRpm());
        }
        double reference = Math.abs(speeds.get(MechanicalSawRole.WATER_WHEEL));
        if (speeds.values().stream().anyMatch(value -> Double.compare(Math.abs(value), reference) != 0)) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-07 water-wheel transmission and saw live speeds differ");
        }
        return new AdapterResult.Success<>(Map.copyOf(speeds));
    }

    private String upwardItemOnlyFailure() {
        MechanicalSawPlacement placement =
                plan.placement(MechanicalSawRole.MECHANICAL_SAW);
        BlockState state = level.getBlockState(position(placement.position()));
        if (!(state.getBlock() instanceof SawBlock)
                || !state.hasProperty(SawBlock.FACING)
                || state.getValue(SawBlock.FACING) != Direction.UP) {
            return "C-07 world_cutting=forbidden requires the live mechanical saw to face UP";
        }
        if (stateRotationAxis(state) != axis(placement.rotationAxis())) {
            return "C-07 item-only saw rotation axis differs from the typed plan";
        }
        return null;
    }

    private DepotBlockEntity depot() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(MechanicalSawRole.INPUT_DEPOT).position()));
        return value instanceof DepotBlockEntity depot ? depot : null;
    }

    private SawBlockEntity saw() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(MechanicalSawRole.MECHANICAL_SAW).position()));
        return value instanceof SawBlockEntity saw ? saw : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(MechanicalSawRole.OUTPUT_CHEST).position()));
        return value instanceof ChestBlockEntity chest ? chest : null;
    }

    private boolean entityPresent(UUID id) {
        Entity entity = level.getEntity(id);
        return entity != null && entity.isAlive();
    }

    private static String validateLiveRecipe(
            CuttingRecipe recipe,
            CuttingProcessSpec spec,
            ItemStack offered) {
        if (!key(spec.recipeId()).equals(recipe.getId())) {
            return "Live cutting recipe mismatch: expected "
                    + spec.recipeId() + " found " + recipe.getId();
        }
        if (!key(spec.recipeType()).equals(AllRecipeTypes.CUTTING.getId())
                || recipe.getType() != AllRecipeTypes.CUTTING.getType()) {
            return "Live recipe is not Create cutting type " + spec.recipeType();
        }
        if (recipe.getIngredients().size() != 1
                || !recipe.getIngredients().get(0).test(offered)) {
            return "Live cutting recipe does not accept the exact typed input";
        }
        if (recipe.getProcessingDuration() < 1
                || recipe.getProcessingDuration() > spec.processingTimeoutTicks()) {
            return "Live cutting duration is outside the typed timeout: "
                    + recipe.getProcessingDuration();
        }
        List<ProcessingOutput> results = recipe.getRollableResults();
        if (results.size() != 1) {
            return "C-07 item-only cutting requires exactly one live output";
        }
        ProcessingOutput result = results.get(0);
        if (!spec.expectedOutputItem().equals(itemId(result.getStack()))
                || result.getStack().getCount() < spec.minimumOutputCount()
                || result.getChance() != 1.0F) {
            return "Live cutting output differs from the typed guaranteed output";
        }
        return null;
    }

    private static OutputObservation observeChest(
            ChestBlockEntity chest,
            CuttingProcessSpec spec) {
        int count = 0;
        ResourceId unexpected = null;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceId item = itemId(stack);
            if (!spec.expectedOutputItem().equals(item)) {
                unexpected = item == null ? id("minecraft:air") : item;
            } else {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return new OutputObservation(count, unexpected);
    }

    private String placementMismatch(MechanicalSawPlacement placement) {
        BlockState state = level.getBlockState(position(placement.position()));
        ResourceLocation actual = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "C-07 placement mismatch at " + placement.position()
                    + ": expected " + placement.blockId() + " found " + actual;
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state) != axis(placement.rotationAxis())) {
            return "C-07 placement axis mismatch at " + placement.position();
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state) != direction(placement.facing())) {
            return "C-07 placement facing mismatch at " + placement.position();
        }
        return null;
    }

    private static String validateTypedState(
            MechanicalSawPlacement placement,
            BlockState state) {
        if (placement.role() == MechanicalSawRole.MECHANICAL_SAW) {
            if (!(state.getBlock() instanceof SawBlock)
                    || placement.facing() != PlanBlockFacing.UP
                    || (placement.rotationAxis() != PlanBlockAxis.X
                            && placement.rotationAxis() != PlanBlockAxis.Z)) {
                return "C-07 mechanical saw must be typed as upward item-only with horizontal shaft";
            }
            return null;
        }
        if (placement.role() == MechanicalSawRole.WATER_WHEEL
                && placement.rotationAxis() == PlanBlockAxis.Y) {
            return placement.blockId() + " requires a horizontal C-07 axis";
        }
        if (placement.facing() != PlanBlockFacing.NONE && !hasFacing(state)) {
            return placement.blockId() + " has no facing property required by C-07";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)) {
            return placement.blockId() + " has no rotation property required by C-07";
        }
        return null;
    }

    private static BlockState applyTypedState(
            MechanicalSawPlacement placement,
            BlockState state) {
        String failure = validateTypedState(placement, state);
        if (failure != null) throw new IllegalArgumentException(failure);
        if (placement.role() == MechanicalSawRole.MECHANICAL_SAW) {
            return state
                    .setValue(SawBlock.FACING, Direction.UP)
                    .setValue(
                            DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,
                            placement.rotationAxis() == PlanBlockAxis.X);
        }
        if (placement.role() == MechanicalSawRole.WATER_WHEEL) {
            Direction facing = switch (placement.rotationAxis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "C-07 water-wheel axis must be horizontal");
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
                MechanicalSawGenericExecutionPlan.ACTION_HANDLER_ID,
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
        return action.operationId().equals(operationId) && context.stepId().equals(stepId);
    }

    private static boolean matches(ItemStack stack, ResourceId item, int count) {
        return !stack.isEmpty() && item.equals(itemId(stack)) && stack.getCount() == count;
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
        if (state.getBlock() instanceof SawBlock saw) {
            return saw.getRotationAxis(state);
        }
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

    private static Block registeredBlock(ResourceId blockId) {
        ResourceLocation key = key(blockId);
        Block block = ForgeRegistries.BLOCKS.getValue(key);
        return block != null && key.equals(ForgeRegistries.BLOCKS.getKey(block)) ? block : null;
    }

    private static Item registeredItem(ResourceId itemId) {
        ResourceLocation key = key(itemId);
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item != null && key.equals(ForgeRegistries.ITEMS.getKey(item)) ? item : null;
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation actual = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return actual == null ? null : new ResourceId(actual.getNamespace(), actual.getPath());
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

    private static FailureDetail detail(AdapterFailureCode code, String failureDetail) {
        return new FailureDetail(
                code,
                "Create 6.0.6 mechanical-saw handler: " + stripPrefix(failureDetail));
    }

    private static String stripPrefix(String value) {
        String prefix = "Create 6.0.6 mechanical-saw handler: ";
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

    private record OutputObservation(int count, ResourceId unexpectedItem) {}
}
