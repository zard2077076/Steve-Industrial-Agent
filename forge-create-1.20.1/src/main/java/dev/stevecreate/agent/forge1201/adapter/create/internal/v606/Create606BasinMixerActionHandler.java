package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BasinMixerEvidence;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BasinHeatMode;
import dev.stevecreate.agent.core.plan.BasinMixerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.BasinMixerPlacement;
import dev.stevecreate.agent.core.plan.BasinMixerPlan;
import dev.stevecreate.agent.core.plan.BasinMixerRole;
import dev.stevecreate.agent.core.plan.MixingProcessSpec;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Real Create 6.0.6 counted ITEM plus reviewed bucket-poured FLUID mixing for C-08. */
final class Create606BasinMixerActionHandler
        implements StepActionHandler {
    private static final ResourceId INTEGER_VALUE =
            id("steve_industrial:value/integer");
    private static final ResourceId ITEM_STACK_VALUE =
            id("steve_industrial:value/item_stack");
    private static final ResourceId RECIPE_VALUE =
            id("steve_industrial:value/recipe");
    private static final ResourceId KINETIC_VALUE =
            id("create:value/signed_rpm_capacity_load");
    private static final ResourceId BOOLEAN_VALUE =
            id("steve_industrial:value/boolean");
    private static final ResourceId HEAT_VALUE =
            id("create:value/blaze_burner_heat");

    private final ServerLevel level;
    private final BasinMixerPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;
    private final CreateV606ReservedFuelAccess reservedFuel;
    private int placementIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private Recipe<?> liveRecipe;
    private boolean liveIngredientsMatched;
    private boolean basinContentsObserved;
    private boolean mixerCycleObserved;
    private BasinMixerEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606BasinMixerActionHandler(
            ServerLevel level,
            BasinMixerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(level, plan, runtime, sessionId,
                WorldChangeJournal.empty(sessionId), 0, 0, null, null);
    }

    Create606BasinMixerActionHandler(
            ServerLevel level,
            BasinMixerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(level, plan, runtime, sessionId,
                WorldChangeJournal.empty(sessionId), 0, 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"), null);
    }

    Create606BasinMixerActionHandler(
            ServerLevel level,
            BasinMixerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer,
            CreateV606ReservedFuelAccess reservedFuel) {
        this(level, plan, runtime, sessionId,
                WorldChangeJournal.empty(sessionId), 0, 0,
                Objects.requireNonNull(resourceBuffer, "resourceBuffer"),
                reservedFuel);
    }

    Create606BasinMixerActionHandler(
            ServerLevel level,
            BasinMixerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal journal,
            int placementIndex,
            int pilotFlowPlacementIndex,
            Create606WorldResourceBuffer resourceBuffer,
            CreateV606ReservedFuelAccess reservedFuel) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (placementIndex < 0
                || placementIndex > plan.placements().size()) {
            throw new IllegalArgumentException(
                    "Recovered C-08 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        if (pilotFlowPlacementIndex < 0 || pilotFlowPlacementIndex > 2) {
            throw new IllegalArgumentException(
                    "Recovered C-08 pilot-flow cursor is outside the bounded channel");
        }
        this.pilotFlowPlacementIndex = pilotFlowPlacementIndex;
        this.resourceBuffer = resourceBuffer;
        this.reservedFuel = reservedFuel;
        this.worldChanges = new Create606WorldChangeJournal(
                level, sessionId,
                Objects.requireNonNull(journal, "journal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(
                    position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(
            ServerLevel level, BasinMixerPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        for (BasinMixerPlacement placement : plan.placements()) {
            Block block = registeredBlock(placement.blockId());
            if (block == null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "Missing runtime block for " + placement));
            }
            BlockPos target = position(placement.position());
            if (!level.hasChunk(
                    target.getX() >> 4, target.getZ() >> 4)) {
                return Optional.of(detail(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "C-08 target chunk is not loaded at "
                                + placement.position()));
            }
            if (!level.getBlockState(target).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "C-08 target is not replaceable at "
                                + placement.position()));
            }
            String stateFailure =
                    validateTypedState(
                            placement, block.defaultBlockState());
            if (stateFailure != null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        stateFailure));
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
                    "C-08 handler must run on the authoritative server thread");
        }
        Optional<String> guardFailure =
                CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return fail(
                    AdapterFailureCode
                            .FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: "
                            + guardFailure.orElseThrow());
        }
        worldChanges.beginInvocation();
        if (!action.handlerId().equals(
                BasinMixerGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-08 action descriptor did not match the v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "C-08 preflight area unloaded during execution: "
                            + chunk.x + "," + chunk.z);
        }
        if (matches(
                action, context,
                BasinMixerGenericExecutionPlan.BUILD_OPERATION_ID,
                BasinMixerGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        String boundaryFailure = liveTopologyFailure();
        if (boundaryFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    boundaryFailure);
        }
        if (matches(
                action, context,
                BasinMixerGenericExecutionPlan.POWER_OPERATION_ID,
                BasinMixerGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(
                action, context,
                BasinMixerGenericExecutionPlan.FEED_OPERATION_ID,
                BasinMixerGenericExecutionPlan.FEED_STEP_ID)) {
            return feedBasin(context);
        }
        if (matches(
                action, context,
                BasinMixerGenericExecutionPlan.PROCESS_OPERATION_ID,
                BasinMixerGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-08 operation and step identity do not match");
    }

    int placementIndex() { return placementIndex; }
    Optional<BasinMixerEvidence> completedEvidence() {
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
    RollbackReport rollback() { return worldChanges.rollback(); }

    private ActionHandlerResult buildOne(
            StepRunnerContext context) {
        if (placementIndex >= plan.placements().size()) {
            return placePilotFlowCell(context);
        }
        BasinMixerPlacement placement =
                plan.placements().get(placementIndex);
        BlockPos target = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-08 target changed at " + placement.position());
        }
        Block block = registeredBlock(placement.blockId());
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-08 block disappeared: " + placement.blockId());
        }
        BlockState state;
        try {
            state = applyTypedState(placement, block.defaultBlockState());
        } catch (IllegalArgumentException exception) {
            return fail(AdapterFailureCode.PLAN_REJECTED, exception.getMessage());
        }
        if (placement.role() == BasinMixerRole.HEAT_SOURCE
                && plan.process().requiresFuel()) {
            if (!(block instanceof BlazeBurnerBlock)
                    || !state.hasProperty(BlazeBurnerBlock.HEAT_LEVEL)) {
                return fail(
                        AdapterFailureCode.PLACEMENT_FAILED,
                        "C-08 HEATED plan lacks a typed Blaze Burner heat source");
            }
            // This is the physical state produced by placing a captured-blaze burner item. It is
            // a real block entity but contributes no recipe heat until reserved ordinary fuel is
            // inserted through BlazeBurnerBlock.tryInsert.
            state = state.setValue(
                    BlazeBurnerBlock.HEAT_LEVEL,
                    BlazeBurnerBlock.HeatLevel.SMOULDERING);
        }
        if (!level.setBlockAndUpdate(target, state)) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected C-08 placement " + placement);
        }
        if (placement.role() == BasinMixerRole.WATER_SOURCE) {
            level.scheduleTick(target, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        worldChanges.recordBlockChange(context, target, before);
        String mismatch = placementMismatch(placement);
        if (mismatch != null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED, mismatch);
        }
        placementIndex++;
        return placementIndex < plan.placements().size() || pilotFlowRequired()
                ? ActionHandlerResult.inProgress(
                        true,
                        worldChanges.drainInvocationReferences())
                : buildSucceeded(context);
    }

    private ActionHandlerResult placePilotFlowCell(StepRunnerContext context) {
        if (!pilotFlowRequired()) return buildSucceeded(context);
        BlockPos source = position(plan.placement(BasinMixerRole.WATER_SOURCE).position());
        BlockPos target = source.below(pilotFlowPlacementIndex + 1);
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "C-08 pilot flowing-water target changed at " + target.toShortString());
        }
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        if (!level.setBlockAndUpdate(target, flowing)) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected bounded C-08 flowing-water placement at "
                            + target.toShortString());
        }
        worldChanges.recordBlockChange(context, target, before);
        pilotFlowPlacementIndex++;
        return pilotFlowRequired()
                ? ActionHandlerResult.inProgress(true, worldChanges.drainInvocationReferences())
                : buildSucceeded(context);
    }

    private boolean pilotFlowRequired() {
        return resourceBuffer != null
                && Boolean.getBoolean(DeceasedCraftExecutionPilotFixture.ENABLE_PROPERTY)
                && pilotFlowPlacementIndex < 2;
    }

    private ActionHandlerResult buildSucceeded(
            StepRunnerContext context) {
        String failure = liveTopologyFailure();
        if (failure != null) {
            return ActionHandlerResult.inProgress(
                    false,
                    worldChanges.drainInvocationReferences());
        }
        return success(evidence(
                id("create:c08/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                BasinMixerGenericExecutionPlan.BUILD_EVIDENCE,
                context,
                BasinMixerGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.placements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(
            StepRunnerContext context) {
        AdapterResult<Map<BasinMixerRole, Double>> result =
                captureKinetics();
        if (result instanceof AdapterResult.Failure<
                Map<BasinMixerRole, Double>> failure) {
            if (failure.code()
                    == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        Map<BasinMixerRole, Double> speeds =
                ((AdapterResult.Success<
                        Map<BasinMixerRole, Double>>) result).value();
        return success(evidence(
                id("create:c08/observation/power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                BasinMixerGenericExecutionPlan.POWER_EVIDENCE,
                context,
                BasinMixerGenericExecutionPlan.MIXER_NODE_ID,
                KINETIC_VALUE,
                speeds.toString(),
                KINETIC_VALUE,
                "non_zero_not_overstressed"));
    }

    private ActionHandlerResult feedBasin(
            StepRunnerContext context) {
        AdapterResult<Map<BasinMixerRole, Double>> speed =
                captureKinetics();
        if (speed instanceof AdapterResult.Failure<
                Map<BasinMixerRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        BasinBlockEntity basin = basin();
        MechanicalMixerBlockEntity mixer = mixer();
        ChestBlockEntity chest = outputChest();
        if (basin == null || mixer == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 mixer, basin or output chest disappeared before feed");
        }
        if (!basin.getInputInventory().isEmpty()
                || !basin.getOutputInventory().isEmpty()
                || !basin.inputTank.isEmpty()
                || !chest.isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 basin item/fluid inventories and output chest must be empty");
        }
        MixingProcessSpec spec = plan.process();
        Optional<? extends Recipe<?>> found =
                level.getRecipeManager().byKey(key(spec.recipeId()));
        if (found.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live recipe with exact C-08 identity "
                            + spec.recipeId());
        }
        liveRecipe = found.orElseThrow();
        String recipeFailure =
                validateLiveRecipe(liveRecipe, spec);
        if (recipeFailure != null) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    recipeFailure);
        }
        List<ItemStack> extracted = new ArrayList<>();
        for (ProcessResource input : spec.itemInputs()) {
            Item item = registeredItem(input.resourceId());
            if (item == null) {
                return failAfterRestore(
                        AdapterFailureCode.RECIPE_NOT_FOUND,
                        "C-08 input item is unregistered: "
                                + input.resourceId(),
                        extracted);
            }
            ItemStack selected = new ItemStack(
                    item, Math.toIntExact(input.amount()));
            if (selected.hasTag()
                    || selected.hasCraftingRemainingItem()) {
                return failAfterRestore(
                        AdapterFailureCode.PLAN_REJECTED,
                        "C-08 rejects NBT-sensitive or residue-bearing input "
                                + input.resourceId(),
                        extracted);
            }
            ItemStack stack;
            if (resourceBuffer == null) {
                stack = selected;
            } else {
                AdapterResult<ItemStack> result =
                        resourceBuffer.extractExact(
                                input.resourceId(),
                                Math.toIntExact(input.amount()));
                if (result instanceof AdapterResult.Failure<
                        ItemStack> failure) {
                    return failAfterRestore(
                            failure.code(), failure.detail(), extracted);
                }
                stack = ((AdapterResult.Success<ItemStack>) result).value();
            }
            if (stack.hasTag()
                    || stack.hasCraftingRemainingItem()) {
                extracted.add(stack);
                return failAfterRestore(
                        AdapterFailureCode.PLAN_REJECTED,
                        "C-08 resource buffer returned NBT-sensitive or residue-bearing input",
                        extracted);
            }
            extracted.add(stack);
        }
        for (int slot = 0; slot < extracted.size(); slot++) {
            basin.getInputInventory().setStackInSlot(
                    slot, extracted.get(slot).copy());
        }
        basin.notifyChangeOfContents();
        if (spec.requiresFuel()) {
            ActionHandlerResult heatFailure =
                    igniteHeatedBurner(context, extracted);
            if (heatFailure != null) return heatFailure;
        } else if (heatLevel()
                != BlazeBurnerBlock.HeatLevel.NONE) {
            clearBasinInput();
            return failAfterRestore(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-08 NONE recipe observed a non-NONE burner",
                    extracted);
        }
        FluidPourResult poured = pourDeclaredFluids(basin, spec);
        if (poured.failure() != null) {
            clearBasinInput();
            if (!poured.materialRestorationSafe()) {
                return failAfterRestore(
                        AdapterFailureCode.PROCESSING_FAILED,
                        poured.failure()
                                + "; bucket restoration refused because injected fluid "
                                + "could not be removed exactly",
                        extracted);
            }
            return failAfterRestore(
                    AdapterFailureCode.PROCESSING_FAILED,
                    poured.failure(),
                    combine(extracted, poured.bucketMaterials()));
        }
        // Create's authoritative BasinRecipe matcher includes the live burner tier. The exact
        // recipe identity, typed ingredients, staged stacks and exact fluid have already been
        // checked above, so HEATED must establish real KINDLED heat before invoking that matcher.
        if (!BasinRecipe.match(basin, liveRecipe)) {
            String fluidRestore = removePouredFluids(basin, poured.fluids());
            clearBasinInput();
            if (fluidRestore != null) {
                return failAfterRestore(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "Staged C-08 inputs failed recipe matching and fluid restoration failed: "
                                + fluidRestore,
                        extracted);
            }
            return failAfterRestore(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "Staged C-08 inputs and live heat do not match the exact mixing recipe",
                    combine(extracted, poured.bucketMaterials()));
        }
        liveIngredientsMatched = true;
        basinContentsObserved = true;
        feedTick = level.getGameTime();
        BlockPos basinPosition = position(
                plan.placement(BasinMixerRole.BASIN).position());
        for (ProcessResource input : spec.itemInputs()) {
            worldChanges.recordInjectedResource(
                    context, basinPosition, input);
        }
        for (ProcessResource input : spec.fluidInputs()) {
            worldChanges.recordInjectedResource(
                    context, basinPosition, input);
        }
        return success(evidence(
                id("create:c08/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                BasinMixerGenericExecutionPlan.FEED_EVIDENCE,
                context,
                BasinMixerGenericExecutionPlan.BASIN_NODE_ID,
                ITEM_STACK_VALUE,
                inputSummary(spec.itemInputs()),
                RECIPE_VALUE,
                spec.recipeId().toString()));
    }

    private ActionHandlerResult igniteHeatedBurner(
            StepRunnerContext context,
            List<ItemStack> extractedInputs) {
        ProcessResource fuel = plan.heatingFuel();
        Item fuelItem = registeredItem(fuel.resourceId());
        if (fuelItem == null) {
            clearBasinInput();
            return failAfterRestore(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "C-08 heating fuel is unregistered: "
                            + fuel.resourceId(),
                    extractedInputs);
        }
        ItemStack fuelStack;
        if (resourceBuffer == null) {
            fuelStack = new ItemStack(fuelItem, 1);
        } else {
            AdapterResult<ItemStack> result = reservedFuel == null
                    ? resourceBuffer.extractExact(
                            fuel.resourceId(), 1)
                    : reservedFuel.extract(fuel, level.getGameTime());
            if (result instanceof AdapterResult.Failure<
                    ItemStack> failure) {
                clearBasinInput();
                return failAfterRestore(
                        failure.code(), failure.detail(),
                        extractedInputs);
            }
            fuelStack =
                    ((AdapterResult.Success<ItemStack>) result).value();
        }
        BlockPos burnerPosition = position(
                plan.placement(BasinMixerRole.HEAT_SOURCE).position());
        InteractionResultHolder<ItemStack> inserted =
                BlazeBurnerBlock.tryInsert(
                        level.getBlockState(burnerPosition),
                        level,
                        burnerPosition,
                        fuelStack,
                        false,
                        false,
                        false);
        if (inserted.getResult() != InteractionResult.SUCCESS) {
            if (resourceBuffer != null) {
                AdapterResult<Integer> restored = reservedFuel == null
                        ? resourceBuffer.insertExact(fuelStack)
                        : reservedFuel.restore(
                                fuelStack, level.getGameTime());
                if (restored instanceof AdapterResult.Failure<
                        Integer> failure) {
                    clearBasinInput();
                    return fail(
                            AdapterFailureCode.PROCESSING_FAILED,
                            "C-08 burner rejected fuel and restoration failed: "
                                    + failure.detail());
                }
            }
            clearBasinInput();
            return failAfterRestore(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 Blaze Burner rejected ordinary heating fuel",
                    extractedInputs);
        }
        if (reservedFuel != null) {
            reservedFuel.markConsumed(fuel, level.getGameTime());
        }
        worldChanges.recordInjectedResource(
                context, burnerPosition, fuel);
        if (heatLevel() != BlazeBurnerBlock.HeatLevel.KINDLED) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 ordinary fuel did not produce exact KINDLED heat");
        }
        return null;
    }

    private ActionHandlerResult observeProcessing(
            StepRunnerContext context) {
        AdapterResult<Map<BasinMixerRole, Double>> speedResult =
                captureKinetics();
        if (speedResult instanceof AdapterResult.Failure<
                Map<BasinMixerRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        Map<BasinMixerRole, Double> speeds =
                ((AdapterResult.Success<
                        Map<BasinMixerRole, Double>>) speedResult).value();
        BasinBlockEntity basin = basin();
        MechanicalMixerBlockEntity mixer = mixer();
        ChestBlockEntity chest = outputChest();
        if (basin == null || mixer == null
                || chest == null || liveRecipe == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 live process state disappeared");
        }
        String fluidFailure = fluidTankFailure(basin, plan.process(), false);
        if (fluidFailure != null) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    fluidFailure);
        }
        String heatFailure = heatFailure();
        if (heatFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    heatFailure);
        }
        if (mixer.running
                || mixer.runningTicks > 0
                || mixer.processingTicks > 0) {
            mixerCycleObserved = true;
        }
        String transferFailure =
                transferRealOutputs(basin, chest);
        if (transferFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    transferFailure);
        }
        OutputObservation output =
                observeChest(chest, plan.process());
        if (output.unexpectedItem() != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 output chest contains unexpected item "
                            + output.unexpectedItem());
        }
        if (!basin.getInputInventory().isEmpty()
                || !basin.getOutputInventory().isEmpty()
                || output.count()
                        != plan.process().expectedOutputCount()) {
            return ActionHandlerResult.inProgress(false);
        }
        if (!liveIngredientsMatched
                || !basinContentsObserved
                || !mixerCycleObserved) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 completion lacks live ingredient, basin or mixer-cycle evidence");
        }
        String topologyFailure = liveTopologyFailure();
        if (topologyFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    topologyFailure);
        }
        String observedHeat =
                heatLevel().name().toLowerCase(Locale.ROOT);
        completedEvidence = new BasinMixerEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(
                        level.dimension().location().toString()),
                plan.origin(),
                plan.placements(),
                speeds,
                plan.process().recipeId(),
                plan.process().recipeType(),
                plan.process().genericSpec().inputs(),
                plan.process().expectedOutputItem(),
                output.count(),
                plan.process().heatMode(),
                observedHeat,
                true,
                true,
                true,
                true,
                basin.inputTank.isEmpty());
        worldChanges.recordIrreversibleProcessing(
                context,
                plan.process().recipeId(),
                plan.process().genericSpec().inputs(),
                List.of(new ProcessResource(
                        plan.process().expectedOutputItem(),
                        GenericResourceType.ITEM,
                        output.count())),
                plan.placements().stream()
                        .map(BasinMixerPlacement::position)
                        .toList());
        return ActionHandlerResult.succeeded(
                processEvidence(context, completedEvidence),
                worldChanges.drainInvocationReferences());
    }

    private String transferRealOutputs(
            BasinBlockEntity basin,
            ChestBlockEntity chest) {
        for (int slot = 0;
                slot < basin.getOutputInventory().getSlots();
                slot++) {
            ItemStack stack =
                    basin.getOutputInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            ResourceId item = itemId(stack);
            if (!plan.process().expectedOutputItem().equals(item)
                    || stack.hasTag()
                    || stack.hasCraftingRemainingItem()) {
                return "C-08 basin produced unexpected or NBT/residue output "
                        + item;
            }
            ItemStack remainder = ItemHandlerHelper.insertItem(
                    new InvWrapper(chest), stack.copy(), false);
            int moved =
                    stack.getCount() - remainder.getCount();
            if (moved > 0) {
                basin.getOutputInventory().extractItem(
                        slot, moved, false);
            }
        }
        basin.notifyChangeOfContents();
        chest.setChanged();
        return null;
    }

    private List<VerificationEvidence> processEvidence(
            StepRunnerContext context,
            BasinMixerEvidence physical) {
        return List.of(
                evidence(
                        id("create:c08/observation/input_consumed"),
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        BasinMixerGenericExecutionPlan.INPUT_CONSUMED,
                        context,
                        BasinMixerGenericExecutionPlan.BASIN_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(
                                plan.process().totalInputCount()),
                        ITEM_STACK_VALUE,
                        inputSummary(physical.consumedInputs())),
                evidence(
                        id("create:c08/observation/process_completed"),
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        BasinMixerGenericExecutionPlan.PROCESS_COMPLETED,
                        context,
                        BasinMixerGenericExecutionPlan.MIXER_NODE_ID,
                        RECIPE_VALUE,
                        physical.recipeId().toString(),
                        RECIPE_VALUE,
                        "create:mixing"),
                evidence(
                        id("create:c08/observation/output_produced"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        BasinMixerGenericExecutionPlan.OUTPUT_PRODUCED,
                        context,
                        BasinMixerGenericExecutionPlan.BASIN_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(
                                physical.observedOutputCount()),
                        INTEGER_VALUE,
                        "=" + plan.process().expectedOutputCount()),
                evidence(
                        id("create:c08/observation/basin_contents"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        BasinMixerGenericExecutionPlan
                                .BASIN_CONTENTS_OBSERVED,
                        context,
                        BasinMixerGenericExecutionPlan.BASIN_NODE_ID,
                        ITEM_STACK_VALUE,
                        inputSummary(physical.consumedInputs()),
                        BOOLEAN_VALUE,
                        "live_tag_or_any_of=true;declared_fluid_mb="
                                + plan.process().totalFluidInputMillibuckets()
                                + ";fluid_consumed=true"),
                evidence(
                        id("create:c08/observation/mixer_cycle"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        BasinMixerGenericExecutionPlan
                                .MIXER_CYCLE_OBSERVED,
                        context,
                        BasinMixerGenericExecutionPlan.MIXER_NODE_ID,
                        RECIPE_VALUE,
                        "basin_mode;recipe="
                                + physical.recipeId(),
                        BOOLEAN_VALUE,
                        "mechanical_mixer_cycle=true"),
                evidence(
                        id("create:c08/observation/heat"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        BasinMixerGenericExecutionPlan.HEAT_OBSERVED,
                        context,
                        BasinMixerGenericExecutionPlan.HEAT_NODE_ID,
                        HEAT_VALUE,
                        physical.observedHeatLevel(),
                        HEAT_VALUE,
                        plan.process().heatMode().name()
                                .toLowerCase(Locale.ROOT)),
                evidence(
                        id("create:c08/observation/chest_output"),
                        VerificationEvidenceKind.OUTPUT_STORED,
                        BasinMixerGenericExecutionPlan
                                .CHEST_OUTPUT_OBSERVED,
                        context,
                        BasinMixerGenericExecutionPlan.CHEST_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x"
                                + physical.observedOutputCount(),
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x="
                                + plan.process().expectedOutputCount()));
    }

    private AdapterResult<Map<BasinMixerRole, Double>>
            captureKinetics() {
        BlockEntity source = level.getBlockEntity(position(
                plan.placement(BasinMixerRole.WATER_WHEEL).position()));
        if (source instanceof WaterWheelBlockEntity waterWheel
                && waterWheel.flowScore == 0) {
            waterWheel.determineAndApplyFlowScore();
        }
        EnumMap<BasinMixerRole, Double> speeds =
                new EnumMap<>(BasinMixerRole.class);
        for (BasinMixerRole role : List.of(
                BasinMixerRole.WATER_WHEEL,
                BasinMixerRole.BOTTOM_GEARBOX,
                BasinMixerRole.VERTICAL_SHAFT,
                BasinMixerRole.LARGE_COGWHEEL_INPUT,
                BasinMixerRole.SMALL_COGWHEEL,
                BasinMixerRole.LARGE_COGWHEEL_OUTPUT,
                BasinMixerRole.MECHANICAL_MIXER)) {
            AdapterResult<KineticSnapshot> result =
                    Create606KineticReader.capture(
                            level,
                            plan.placement(role).position(),
                            runtime);
            if (result instanceof AdapterResult.Failure<
                    KineticSnapshot> failure) {
                return adapterFailure(
                        failure.code(),
                        role + ": " + failure.detail());
            }
            KineticSnapshot snapshot =
                    ((AdapterResult.Success<KineticSnapshot>) result)
                            .value();
            if (snapshot.speedRpm() == 0
                    || snapshot.rotationDirection()
                            == KineticRotationDirection.STATIONARY) {
                return adapterFailure(
                        AdapterFailureCode.LIFECYCLE_NOT_READY,
                        role + " has no live power");
            }
            if (snapshot.overstressed()) {
                return adapterFailure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        role + " is overstressed");
            }
            speeds.put(
                    role,
                    snapshot.rotationDirection()
                            == KineticRotationDirection.POSITIVE
                            ? snapshot.speedRpm()
                            : -snapshot.speedRpm());
        }
        double wheel = Math.abs(speeds.get(BasinMixerRole.WATER_WHEEL));
        double bottom = Math.abs(speeds.get(BasinMixerRole.BOTTOM_GEARBOX));
        double shaft = Math.abs(speeds.get(BasinMixerRole.VERTICAL_SHAFT));
        double largeInput = Math.abs(speeds.get(BasinMixerRole.LARGE_COGWHEEL_INPUT));
        double small = Math.abs(speeds.get(BasinMixerRole.SMALL_COGWHEEL));
        double largeOutput = Math.abs(speeds.get(BasinMixerRole.LARGE_COGWHEEL_OUTPUT));
        double mixer = Math.abs(speeds.get(BasinMixerRole.MECHANICAL_MIXER));
        if (Double.compare(wheel, bottom) != 0
                || Double.compare(bottom, shaft) != 0
                || Double.compare(shaft, largeInput) != 0
                || Double.compare(small, largeInput * 2.0D) != 0
                || Double.compare(largeOutput, small) != 0
                || Double.compare(mixer, largeOutput * 2.0D) != 0
                || mixer < 32.0D) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-08 geared water-wheel chain does not provide the reviewed 8/16/32 RPM ratio");
        }
        return new AdapterResult.Success<>(Map.copyOf(speeds));
    }

    private String liveTopologyFailure() {
        for (BasinMixerPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) return mismatch;
        }
        if (mixer() == null
                || basin() == null
                || outputChest() == null) {
            return "C-08 typed block entities are not initialized";
        }
        String fluidFailure = fluidTankFailure(basin(), plan.process(), false);
        if (fluidFailure != null) return fluidFailure;
        if (plan.process().requiresFuel()) {
            BlazeBurnerBlock.HeatLevel actual = heatLevel();
            if (actual == null
                    || !actual.isAtLeast(
                            BlazeBurnerBlock.HeatLevel.SMOULDERING)) {
                return "C-08 HEATED device is not a captured-blaze burner";
            }
        }
        return null;
    }

    private String heatFailure() {
        BlazeBurnerBlock.HeatLevel actual = heatLevel();
        if (actual == null) {
            return "C-08 heat source is unavailable";
        }
        BlazeBurnerBlock.HeatLevel expected =
                plan.process().heatMode() == BasinHeatMode.NONE
                        ? BlazeBurnerBlock.HeatLevel.NONE
                        : BlazeBurnerBlock.HeatLevel.KINDLED;
        return actual == expected
                ? null
                : "C-08 heat changed: expected "
                        + expected + " found " + actual;
    }

    private BlazeBurnerBlock.HeatLevel heatLevel() {
        BlockState state = level.getBlockState(position(
                plan.placement(BasinMixerRole.HEAT_SOURCE)
                        .position()));
        return state.getBlock() instanceof BlazeBurnerBlock
                ? BlazeBurnerBlock.getHeatLevelOf(state)
                : null;
    }

    private BasinBlockEntity basin() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(BasinMixerRole.BASIN).position()));
        return value instanceof BasinBlockEntity basin
                ? basin : null;
    }

    private MechanicalMixerBlockEntity mixer() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(BasinMixerRole.MECHANICAL_MIXER)
                        .position()));
        return value instanceof MechanicalMixerBlockEntity mixer
                ? mixer : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(BasinMixerRole.OUTPUT_CHEST)
                        .position()));
        return value instanceof ChestBlockEntity chest
                ? chest : null;
    }

    private void clearBasinInput() {
        BasinBlockEntity basin = basin();
        if (basin == null) return;
        for (int slot = 0;
                slot < basin.getInputInventory().getSlots();
                slot++) {
            basin.getInputInventory().setStackInSlot(
                    slot, ItemStack.EMPTY);
        }
        basin.notifyChangeOfContents();
    }

    private FluidPourResult pourDeclaredFluids(
            BasinBlockEntity basin,
            MixingProcessSpec spec) {
        if (spec.fluidInputs().isEmpty()) {
            return new FluidPourResult(List.of(), List.of(), null, true);
        }
        IFluidHandler tank = basin.inputTank.getPrimaryHandler();
        List<FluidStack> poured = new ArrayList<>();
        List<ItemStack> bucketMaterials = new ArrayList<>();
        for (ProcessResource input : spec.fluidInputs()) {
            PlacementItemBinding.Bucket bucket = PlacementItemBinding
                    .bucketsFor(input.resourceId(), input.amount())
                    .orElse(null);
            if (bucket == null) {
                return fluidPourFailure(
                        basin, poured, bucketMaterials,
                        "C-08 has no reviewed bucket material for " + input.resourceId());
            }
            Item bucketItem = registeredItem(bucket.item());
            net.minecraft.world.level.material.Fluid fluid = registeredFluid(input.resourceId());
            if (bucketItem == null || fluid == null) {
                return fluidPourFailure(
                        basin, poured, bucketMaterials,
                        "C-08 fluid or bucket item is unregistered for " + input.resourceId());
            }
            ItemStack material;
            if (resourceBuffer == null) {
                material = new ItemStack(bucketItem, Math.toIntExact(bucket.count()));
            } else {
                AdapterResult<ItemStack> result = resourceBuffer.extractExact(
                        bucket.item(), Math.toIntExact(bucket.count()));
                if (result instanceof AdapterResult.Failure<ItemStack> failure) {
                    return fluidPourFailure(
                            basin, poured, bucketMaterials,
                            failure.code() + ": " + failure.detail());
                }
                material = ((AdapterResult.Success<ItemStack>) result).value();
            }
            if (material.hasTag() || itemId(material) == null
                    || !itemId(material).equals(bucket.item())) {
                bucketMaterials.add(material);
                return fluidPourFailure(
                        basin, poured, bucketMaterials,
                        "C-08 bucket material identity or components changed");
            }
            bucketMaterials.add(material);
            FluidStack offered = new FluidStack(fluid, Math.toIntExact(input.amount()));
            int simulated = tank.fill(offered, IFluidHandler.FluidAction.SIMULATE);
            if (simulated != offered.getAmount()) {
                return fluidPourFailure(
                        basin, poured, bucketMaterials,
                        "C-08 basin cannot accept exact declared fluid: expected="
                                + offered.getAmount() + " accepted=" + simulated);
            }
            int accepted = tank.fill(offered, IFluidHandler.FluidAction.EXECUTE);
            if (accepted != offered.getAmount()) {
                if (accepted > 0) {
                    FluidStack partial = offered.copy();
                    partial.setAmount(accepted);
                    poured.add(partial);
                }
                return fluidPourFailure(
                        basin, poured, bucketMaterials,
                        "C-08 basin fluid fill diverged after simulation: expected="
                                + offered.getAmount() + " accepted=" + accepted);
            }
            poured.add(offered.copy());
        }
        basin.notifyChangeOfContents();
        String mismatch = fluidTankFailure(basin, spec, true);
        if (mismatch != null) {
            return fluidPourFailure(basin, poured, bucketMaterials, mismatch);
        }
        return new FluidPourResult(
                List.copyOf(poured), List.copyOf(bucketMaterials), null, true);
    }

    private FluidPourResult fluidPourFailure(
            BasinBlockEntity basin,
            List<FluidStack> poured,
            List<ItemStack> bucketMaterials,
            String failure) {
        String removal = removePouredFluids(basin, poured);
        return new FluidPourResult(
                List.of(),
                List.copyOf(bucketMaterials),
                removal == null ? failure : failure + "; " + removal,
                removal == null);
    }

    private static String removePouredFluids(
            BasinBlockEntity basin,
            List<FluidStack> poured) {
        IFluidHandler tank = basin.inputTank.getPrimaryHandler();
        for (int index = poured.size() - 1; index >= 0; index--) {
            FluidStack expected = poured.get(index);
            FluidStack drained = tank.drain(
                    expected, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isFluidStackIdentical(expected)
                    || drained.getAmount() != expected.getAmount()) {
                return "C-08 could not remove injected fluid exactly";
            }
        }
        basin.notifyChangeOfContents();
        return null;
    }

    private static String fluidTankFailure(
            BasinBlockEntity basin,
            MixingProcessSpec spec,
            boolean requireExactAmount) {
        Map<ResourceId, Long> declared = new LinkedHashMap<>();
        spec.fluidInputs().forEach(value -> declared.merge(
                value.resourceId(), value.amount(), Math::addExact));
        Map<ResourceId, Long> actual = new LinkedHashMap<>();
        IFluidHandler tank = basin.inputTank.getPrimaryHandler();
        for (int index = 0; index < tank.getTanks(); index++) {
            FluidStack stack = tank.getFluidInTank(index);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
            if (id == null || stack.hasTag()) {
                return "C-08 basin contains unregistered or component-bearing fluid";
            }
            actual.merge(ResourceId.parse(id.toString()), (long) stack.getAmount(), Math::addExact);
        }
        for (Map.Entry<ResourceId, Long> observed : actual.entrySet()) {
            long maximum = declared.getOrDefault(observed.getKey(), -1L);
            if (maximum < 0 || observed.getValue() > maximum) {
                return "C-08 basin contains undeclared fluid " + observed.getKey()
                        + "@" + observed.getValue() + "mB";
            }
        }
        if (requireExactAmount && !actual.equals(declared)) {
            return "C-08 basin fluid differs from the declared pour: expected="
                    + declared + " actual=" + actual;
        }
        return null;
    }

    private static List<ItemStack> combine(
            List<ItemStack> first,
            List<ItemStack> second) {
        List<ItemStack> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private ActionHandlerResult failAfterRestore(
            AdapterFailureCode code,
            String failureDetail,
            List<ItemStack> extracted) {
        String restorationFailure = restoreInputs(extracted);
        if (restorationFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    failureDetail
                            + "; input restoration also failed: "
                            + restorationFailure);
        }
        return fail(code, failureDetail);
    }

    private String restoreInputs(List<ItemStack> extracted) {
        if (resourceBuffer == null) return null;
        for (ItemStack stack : extracted) {
            if (stack.isEmpty()) continue;
            AdapterResult<Integer> restored =
                    resourceBuffer.insertExact(stack);
            if (restored instanceof AdapterResult.Failure<
                    Integer> failure) {
                return failure.code() + ": " + failure.detail();
            }
        }
        return null;
    }

    private String placementMismatch(
            BasinMixerPlacement placement) {
        BlockState state = level.getBlockState(
                position(placement.position()));
        ResourceLocation actual =
                ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "C-08 placement mismatch at "
                    + placement.position() + ": expected "
                    + placement.blockId() + " found " + actual;
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)
                && stateRotationAxis(state)
                        != axis(placement.rotationAxis())) {
            return "C-08 placement axis mismatch at "
                    + placement.position();
        }
        if (placement.role() == BasinMixerRole.WATER_WHEEL
                && placement.rotationAxis() == PlanBlockAxis.Y) {
            return placement.blockId() + " requires a horizontal C-08 axis";
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state)
                        != direction(placement.facing())) {
            return "C-08 placement facing mismatch at "
                    + placement.position();
        }
        return null;
    }

    private static String validateLiveRecipe(
            Recipe<?> recipe, MixingProcessSpec spec) {
        if (!key(spec.recipeId()).equals(recipe.getId())) {
            return "Live mixing recipe identity changed";
        }
        if (recipe.getType() != AllRecipeTypes.MIXING.getType()
                || !key(spec.recipeType()).equals(
                        AllRecipeTypes.MIXING.getId())) {
            return "Live recipe is not Create mixing";
        }
        if (!(recipe instanceof ProcessingRecipe<?> processing)) {
            return "C-08 requires a Create ProcessingRecipe";
        }
        if (!processing.getFluidResults().isEmpty()) {
            return "C-08 still rejects fluid output";
        }
        if (!fluidIngredientsMatch(processing, spec)) {
            return "Live mixing fluid ingredients differ from the exact typed inputs";
        }
        HeatCondition requiredHeat = processing.getRequiredHeat();
        HeatCondition expectedHeat =
                spec.heatMode() == BasinHeatMode.NONE
                        ? HeatCondition.NONE
                        : HeatCondition.HEATED;
        if (requiredHeat != expectedHeat) {
            return requiredHeat == HeatCondition.SUPERHEATED
                    ? "C-08 SUPERHEATED remains typed unsupported"
                    : "Live mixing heat differs from the typed recipe";
        }
        if (processing.getIngredients().isEmpty()
                || processing.getIngredients().size()
                        > MixingProcessSpec.MAX_ITEM_INPUTS
                || processing.getIngredients().stream().anyMatch(
                        ingredient -> ingredient.getItems().length == 0)
                || !ingredientsMatch(
                        processing.getIngredients(),
                        spec.itemInputs())) {
            return "Live mixing ingredients are unknown-NBT, residue-bearing or differ from the typed inputs";
        }
        List<ProcessingOutput> outputs =
                processing.getRollableResults();
        if (outputs.size() != 1) {
            return "C-08 deterministic Phase I requires exactly one item output";
        }
        ProcessingOutput output = outputs.get(0);
        if (output.getChance() != 1.0F
                || output.getStack().hasTag()
                || output.getStack().hasCraftingRemainingItem()
                || !spec.expectedOutputItem().equals(
                        itemId(output.getStack()))
                || output.getStack().getCount()
                        != spec.expectedOutputCount()) {
            return "Live mixing output is probabilistic, NBT/residue-bearing or differs from the typed output";
        }
        return null;
    }

    private static boolean ingredientsMatch(
            List<Ingredient> ingredients,
            List<ProcessResource> inputs) {
        Map<ResourceId, Integer> remaining =
                new LinkedHashMap<>();
        for (ProcessResource input : inputs) {
            remaining.merge(
                    input.resourceId(),
                    Math.toIntExact(input.amount()),
                    Math::addExact);
        }
        for (Ingredient ingredient : ingredients) {
            ResourceId matched = null;
            for (Map.Entry<ResourceId, Integer> candidate
                    : remaining.entrySet()) {
                if (candidate.getValue() < 1) continue;
                Item item = registeredItem(candidate.getKey());
                if (item != null
                        && ingredient.test(new ItemStack(item))) {
                    matched = candidate.getKey();
                    break;
                }
            }
            if (matched == null) return false;
            remaining.computeIfPresent(
                    matched, (ignored, count) -> count - 1);
        }
        return remaining.values().stream()
                .allMatch(value -> value == 0);
    }

    private static boolean fluidIngredientsMatch(
            ProcessingRecipe<?> processing,
            MixingProcessSpec spec) {
        if (processing.getFluidIngredients().size() != spec.fluidInputs().size()) {
            return false;
        }
        List<ProcessResource> remaining = new ArrayList<>(spec.fluidInputs());
        for (var ingredient : processing.getFluidIngredients()) {
            int matched = -1;
            for (int index = 0; index < remaining.size(); index++) {
                ProcessResource expected = remaining.get(index);
                net.minecraft.world.level.material.Fluid fluid = registeredFluid(
                        expected.resourceId());
                if (fluid == null) continue;
                FluidStack stack = new FluidStack(
                        fluid, Math.toIntExact(expected.amount()));
                if (ingredient.getRequiredAmount() == expected.amount()
                        && ingredient.test(stack)) {
                    matched = index;
                    break;
                }
            }
            if (matched < 0) return false;
            remaining.remove(matched);
        }
        return remaining.isEmpty();
    }

    private static OutputObservation observeChest(
            ChestBlockEntity chest,
            MixingProcessSpec spec) {
        int count = 0;
        ResourceId unexpected = null;
        for (int slot = 0;
                slot < chest.getContainerSize();
                slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceId item = itemId(stack);
            if (!spec.expectedOutputItem().equals(item)
                    || stack.hasTag()
                    || stack.hasCraftingRemainingItem()) {
                unexpected = item;
            } else {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return new OutputObservation(count, unexpected);
    }

    private static String validateTypedState(
            BasinMixerPlacement placement,
            BlockState state) {
        if (placement.facing() != PlanBlockFacing.NONE
                && !hasFacing(state)) {
            return placement.blockId()
                    + " has no required C-08 facing";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)
                && stateRotationAxis(state)
                        != axis(placement.rotationAxis())) {
            return placement.blockId()
                    + " has no required C-08 rotation";
        }
        if (placement.role() == BasinMixerRole.WATER_WHEEL) {
            Direction facing = switch (placement.rotationAxis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "C-08 water-wheel axis must be horizontal");
            };
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && placement.facing() != PlanBlockFacing.NONE
                && direction(placement.facing()).getAxis()
                        != axis(placement.rotationAxis())) {
            return placement.blockId()
                    + " typed facing and rotation axis disagree";
        }
        return null;
    }

    private static BlockState applyTypedState(
            BasinMixerPlacement placement,
            BlockState state) {
        String failure = validateTypedState(placement, state);
        if (failure != null) {
            throw new IllegalArgumentException(failure);
        }
        if (placement.role() == BasinMixerRole.WATER_WHEEL) {
            Direction facing = switch (placement.rotationAxis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "C-08 water-wheel axis must be horizontal");
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
            state = state.hasProperty(
                    DirectionalKineticBlock.FACING)
                    ? state.setValue(
                            DirectionalKineticBlock.FACING,
                            facing)
                    : state.hasProperty(
                            BlockStateProperties.FACING)
                    ? state.setValue(
                            BlockStateProperties.FACING, facing)
                    : state.setValue(
                            BlockStateProperties.HORIZONTAL_FACING,
                            facing);
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
                BasinMixerGenericExecutionPlan.ACTION_HANDLER_ID,
                targetId,
                new EvidenceValue(observedSchema, observed),
                new EvidenceValue(expectedSchema, expected),
                context.gameTick(),
                true,
                Optional.empty());
    }

    private ActionHandlerResult success(
            VerificationEvidence evidence) {
        return ActionHandlerResult.succeeded(
                List.of(evidence),
                worldChanges.drainInvocationReferences());
    }

    private ActionHandlerResult fail(
            AdapterFailureCode code, String failureDetail) {
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
            ResourceId operation,
            ResourceId step) {
        return action.operationId().equals(operation)
                && context.stepId().equals(step);
    }

    private static String inputSummary(
            List<ProcessResource> inputs) {
        return inputs.stream()
                .map(value -> value.resourceId()
                        + " x" + value.amount())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private static boolean hasFacing(BlockState state) {
        return state.hasProperty(
                        DirectionalKineticBlock.FACING)
                || state.hasProperty(BlockStateProperties.FACING)
                || state.hasProperty(
                        BlockStateProperties.HORIZONTAL_FACING);
    }

    private static Direction stateFacing(BlockState state) {
        if (state.hasProperty(
                DirectionalKineticBlock.FACING)) {
            return state.getValue(
                    DirectionalKineticBlock.FACING);
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        return state.hasProperty(
                BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(
                        BlockStateProperties.HORIZONTAL_FACING)
                : null;
    }

    private static Direction.Axis stateRotationAxis(
            BlockState state) {
        if (state.getBlock() instanceof KineticBlock kinetic) {
            return kinetic.getRotationAxis(state);
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            return state.getValue(BlockStateProperties.AXIS);
        }
        Direction facing = stateFacing(state);
        return facing == null ? null : facing.getAxis();
    }

    private static Block registeredBlock(ResourceId id) {
        ResourceLocation key = key(id);
        Block value = ForgeRegistries.BLOCKS.getValue(key);
        return value != null
                && key.equals(
                        ForgeRegistries.BLOCKS.getKey(value))
                ? value : null;
    }

    private static Item registeredItem(ResourceId id) {
        ResourceLocation key = key(id);
        Item value = ForgeRegistries.ITEMS.getValue(key);
        return value != null
                && key.equals(
                        ForgeRegistries.ITEMS.getKey(value))
                ? value : null;
    }

    private static net.minecraft.world.level.material.Fluid registeredFluid(ResourceId id) {
        ResourceLocation key = key(id);
        net.minecraft.world.level.material.Fluid value = ForgeRegistries.FLUIDS.getValue(key);
        return value != null && key.equals(ForgeRegistries.FLUIDS.getKey(value))
                ? value : null;
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null
                ? null : ResourceId.parse(key.toString());
    }

    private static Direction.Axis axis(PlanBlockAxis value) {
        return switch (value) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException(
                    "NONE has no axis");
        };
    }

    private static Direction direction(PlanBlockFacing value) {
        return switch (value) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case NONE -> throw new IllegalArgumentException(
                    "NONE has no facing");
        };
    }

    private static BlockPos position(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceLocation key(ResourceId value) {
        return ResourceLocation.fromNamespaceAndPath(
                value.namespace(), value.path());
    }

    private static ResourceId failureId(
            AdapterFailureCode code) {
        return id("create:v606/failure/"
                + code.name().toLowerCase(Locale.ROOT));
    }

    private static FailureDetail detail(
            AdapterFailureCode code, String failureDetail) {
        return new FailureDetail(
                code,
                "Create 6.0.6 basin/mixer handler: "
                        + stripPrefix(failureDetail));
    }

    private static String stripPrefix(String detail) {
        String prefix =
                "Create 6.0.6 basin/mixer handler: ";
        return detail.startsWith(prefix)
                ? detail.substring(prefix.length()) : detail;
    }

    private static <T> AdapterResult<T> adapterFailure(
            AdapterFailureCode code, String failureDetail) {
        FailureDetail failure = detail(code, failureDetail);
        return new AdapterResult.Failure<>(
                failure.code(), failure.detail());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    record FailureDetail(
            AdapterFailureCode code, String detail) {
        FailureDetail {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private record FluidPourResult(
            List<FluidStack> fluids,
            List<ItemStack> bucketMaterials,
            String failure,
            boolean materialRestorationSafe) {
        private FluidPourResult {
            fluids = List.copyOf(fluids);
            bucketMaterials = List.copyOf(bucketMaterials);
        }
    }

    private record OutputObservation(
            int count, ResourceId unexpectedItem) {}
}
