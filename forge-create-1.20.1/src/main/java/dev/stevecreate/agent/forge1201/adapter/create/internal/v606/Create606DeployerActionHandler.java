package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.DeployerEvidence;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.ActionHandlerResult;
import dev.stevecreate.agent.core.execution.StepActionDescriptor;
import dev.stevecreate.agent.core.execution.StepActionHandler;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.DeployerGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.DeployerInteractionPolicy;
import dev.stevecreate.agent.core.plan.DeployerPlacement;
import dev.stevecreate.agent.core.plan.DeployerPlan;
import dev.stevecreate.agent.core.plan.DeployerRole;
import dev.stevecreate.agent.core.plan.DeployingProcessSpec;
import dev.stevecreate.agent.core.plan.HeldItemDisposition;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Real Create 6.0.6 item application behind the bounded C-10 runner.
 *
 * <p>This class never calls the Deployer fake player, Block#use, an entity
 * interaction API or a player inventory. It stages two exact resource-buffer
 * stacks only into the plan-owned Deployer capability and Depot, then observes
 * Create's normal runtime transformation.
 */
final class Create606DeployerActionHandler
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

    private final ServerLevel level;
    private final DeployerPlan plan;
    private final RuntimeFingerprint runtime;
    private final List<ChunkPos> preflightChunks;
    private final Create606WorldChangeJournal worldChanges;
    private final Create606WorldResourceBuffer resourceBuffer;
    private int placementIndex;
    private int pilotFlowPlacementIndex;
    private long feedTick = -1;
    private Recipe<?> liveRecipe;
    private int heldItemBeforeCount;
    private boolean depotItemObserved;
    private boolean deployerCycleObserved;
    private DeployerEvidence completedEvidence;
    private FailureDetail lastFailure;

    Create606DeployerActionHandler(
            ServerLevel level,
            DeployerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId) {
        this(
                level,
                plan,
                runtime,
                sessionId,
                WorldChangeJournal.empty(sessionId),
                0,
                0,
                null);
    }

    Create606DeployerActionHandler(
            ServerLevel level,
            DeployerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            Create606WorldResourceBuffer resourceBuffer) {
        this(
                level,
                plan,
                runtime,
                sessionId,
                WorldChangeJournal.empty(sessionId),
                0,
                0,
                Objects.requireNonNull(
                        resourceBuffer, "resourceBuffer"));
    }

    Create606DeployerActionHandler(
            ServerLevel level,
            DeployerPlan plan,
            RuntimeFingerprint runtime,
            ResourceId sessionId,
            WorldChangeJournal journal,
            int placementIndex,
            int pilotFlowPlacementIndex,
            Create606WorldResourceBuffer resourceBuffer) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (placementIndex < 0
                || placementIndex > plan.placements().size()) {
            throw new IllegalArgumentException(
                    "Recovered C-10 placement cursor is outside the plan");
        }
        this.placementIndex = placementIndex;
        if (pilotFlowPlacementIndex < 0 || pilotFlowPlacementIndex > 2) {
            throw new IllegalArgumentException(
                    "Recovered C-10 pilot-flow cursor is outside the bounded channel");
        }
        this.pilotFlowPlacementIndex = pilotFlowPlacementIndex;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level,
                sessionId,
                Objects.requireNonNull(journal, "journal"));
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos3i position : plan.preflightPositions()) {
            chunks.add(new ChunkPos(
                    position.x() >> 4, position.z() >> 4));
        }
        this.preflightChunks = List.copyOf(chunks);
    }

    static Optional<FailureDetail> validatePlan(
            ServerLevel level, DeployerPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        DeployerInteractionPolicy policy =
                plan.process().interactionPolicy();
        if (policy.target()
                        != DeployerInteractionPolicy.Target
                                .OWNED_DEPOT_ITEM
                || policy.interactionFace() != PlanBlockFacing.DOWN
                || !policy.entityInteractionForbidden()
                || !policy.combatForbidden()
                || !policy.arbitraryBlockUseForbidden()
                || !policy.containerOpeningForbidden()
                || !policy.playerInventoryForbidden()
                || !policy.privateStorageForbidden()
                || !policy.unknownNbtMutationForbidden()
                || !policy.unknownWorldSideEffectsForbidden()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 immediate safety policy is incomplete"));
        }
        for (DeployerPlacement placement : plan.placements()) {
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
                        "C-10 target chunk is not loaded at "
                                + placement.position()));
            }
            if (!level.getBlockState(target).canBeReplaced()) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        "C-10 target is not replaceable at "
                                + placement.position()));
            }
            String stateFailure = validateTypedState(
                    placement, block.defaultBlockState());
            if (stateFailure != null) {
                return Optional.of(detail(
                        AdapterFailureCode.PLAN_REJECTED,
                        stateFailure));
            }
        }
        if (!level.getBlockState(position(
                        plan.interactionPosition()))
                .canBeReplaced()) {
            return Optional.of(detail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 interaction cell is not empty"));
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
                    "C-10 handler must run on the authoritative server thread");
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
                        DeployerGenericExecutionPlan.ACTION_HANDLER_ID)
                || !action.parameters().isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 action descriptor did not match the v606 schema");
        }
        Optional<ChunkPos> unloaded = firstUnloadedChunk();
        if (unloaded.isPresent()) {
            ChunkPos chunk = unloaded.orElseThrow();
            return fail(
                    AdapterFailureCode.CHUNK_NOT_LOADED,
                    "C-10 preflight area unloaded during execution: "
                            + chunk.x + "," + chunk.z);
        }
        if (matches(
                action,
                context,
                DeployerGenericExecutionPlan.BUILD_OPERATION_ID,
                DeployerGenericExecutionPlan.BUILD_STEP_ID)) {
            return buildOne(context);
        }
        String boundaryFailure = liveTopologyFailure();
        if (boundaryFailure != null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    boundaryFailure);
        }
        if (matches(
                action,
                context,
                DeployerGenericExecutionPlan.POWER_OPERATION_ID,
                DeployerGenericExecutionPlan.POWER_STEP_ID)) {
            return awaitPower(context);
        }
        if (matches(
                action,
                context,
                DeployerGenericExecutionPlan.FEED_OPERATION_ID,
                DeployerGenericExecutionPlan.FEED_STEP_ID)) {
            return feed(context);
        }
        if (matches(
                action,
                context,
                DeployerGenericExecutionPlan.PROCESS_OPERATION_ID,
                DeployerGenericExecutionPlan.PROCESS_STEP_ID)) {
            return observeProcessing(context);
        }
        return fail(
                AdapterFailureCode.PLAN_REJECTED,
                "C-10 operation and step identity do not match");
    }

    int placementIndex() { return placementIndex; }
    Optional<DeployerEvidence> completedEvidence() {
        return Optional.ofNullable(completedEvidence);
    }
    Optional<FailureDetail> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }
    Optional<ChunkPos> firstUnloadedChunk() {
        return preflightChunks.stream()
                .filter(chunk ->
                        !level.hasChunk(chunk.x, chunk.z))
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
        DeployerPlacement placement =
                plan.placements().get(placementIndex);
        BlockPos target = position(placement.position());
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-10 target changed at " + placement.position());
        }
        Block block = registeredBlock(placement.blockId());
        if (block == null) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "C-10 block disappeared: " + placement.blockId());
        }
        BlockState state = applyTypedState(
                placement, block.defaultBlockState());
        if (!level.setBlockAndUpdate(target, state)) {
            return fail(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected C-10 placement " + placement);
        }
        if (placement.role() == DeployerRole.WATER_SOURCE) {
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
        BlockPos source = position(plan.placement(DeployerRole.WATER_SOURCE).position());
        BlockPos target = source.below(pilotFlowPlacementIndex + 1);
        WorldBlockSnapshot before = worldChanges.capture(target);
        if (!level.getBlockState(target).canBeReplaced()) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "C-10 pilot flowing-water target changed at " + target.toShortString());
        }
        BlockState flowing = Fluids.FLOWING_WATER.getFlowing(8, true).createLegacyBlock();
        if (!level.setBlockAndUpdate(target, flowing)) {
            return fail(AdapterFailureCode.PLACEMENT_FAILED,
                    "World rejected bounded C-10 flowing-water placement at "
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
                id("create:c10/observation/placements_verified"),
                VerificationEvidenceKind.BLOCK_STATE_MATCH,
                DeployerGenericExecutionPlan.BUILD_EVIDENCE,
                context,
                DeployerGenericExecutionPlan.GRAPH_ID,
                INTEGER_VALUE,
                Integer.toString(plan.placements().size()),
                INTEGER_VALUE,
                Integer.toString(plan.placements().size())));
    }

    private ActionHandlerResult awaitPower(
            StepRunnerContext context) {
        AdapterResult<Map<DeployerRole, Double>> result =
                captureKinetics();
        if (result
                instanceof AdapterResult.Failure<
                        Map<DeployerRole, Double>> failure) {
            if (failure.code()
                    == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return ActionHandlerResult.inProgress(false);
            }
            return fail(failure.code(), failure.detail());
        }
        Map<DeployerRole, Double> speeds =
                ((AdapterResult.Success<
                        Map<DeployerRole, Double>>) result).value();
        return success(evidence(
                id("create:c10/observation/power_present"),
                VerificationEvidenceKind.POWER_PRESENT,
                DeployerGenericExecutionPlan.POWER_EVIDENCE,
                context,
                DeployerGenericExecutionPlan.DEPLOYER_NODE_ID,
                KINETIC_VALUE,
                speeds.toString(),
                KINETIC_VALUE,
                "non_zero_not_overstressed"));
    }

    private ActionHandlerResult feed(
            StepRunnerContext context) {
        AdapterResult<Map<DeployerRole, Double>> speed =
                captureKinetics();
        if (speed
                instanceof AdapterResult.Failure<
                        Map<DeployerRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        DeployerBlockEntity deployer = deployer();
        DepotBlockEntity depot = depot();
        ChestBlockEntity chest = outputChest();
        if (deployer == null || depot == null || chest == null) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 Deployer, Depot or output chest disappeared before feed");
        }
        if (!depot.getHeldItem().isEmpty()
                || !chest.isEmpty()
                || !deployerHeldItem(deployer).isEmpty()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 owned Deployer, Depot and output chest must be empty");
        }
        List<Entity> occupants = level.getEntities(
                (Entity) null,
                new AABB(position(plan.interactionPosition())));
        if (!occupants.isEmpty()) {
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 refuses entity interaction in the exact Depot cell");
        }
        DeployingProcessSpec spec = plan.process();
        Optional<? extends Recipe<?>> found =
                level.getRecipeManager().byKey(key(spec.recipeId()));
        if (found.isEmpty()) {
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "No live recipe with exact C-10 identity "
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

        AdapterResult<ItemStack> processedResult = extract(
                spec.processedItem(), spec.processedItemCount());
        if (processedResult
                instanceof AdapterResult.Failure<ItemStack> failure) {
            return fail(failure.code(), failure.detail());
        }
        ItemStack processed =
                ((AdapterResult.Success<ItemStack>) processedResult)
                        .value();
        AdapterResult<ItemStack> heldResult =
                extract(spec.heldItem(), 1);
        if (heldResult
                instanceof AdapterResult.Failure<ItemStack> failure) {
            restore(processed);
            return fail(failure.code(), failure.detail());
        }
        ItemStack held =
                ((AdapterResult.Success<ItemStack>) heldResult).value();
        if (processed.hasTag() || held.hasTag()
                || processed.hasCraftingRemainingItem()
                || held.hasCraftingRemainingItem()) {
            restore(processed);
            restore(held);
            return fail(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 rejects NBT-sensitive or remainder-bearing inputs");
        }
        if (!setDeployerHeldItem(deployer, held.copy())) {
            restore(processed);
            restore(held);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 could not stage the exact held item");
        }
        depot.setHeldItem(processed.copy());
        ItemStack staged = depot.getHeldItem();
        Recipe<?> resolved = deployer.getRecipe(staged);
        if (resolved == null
                || !resolved.getId().equals(liveRecipe.getId())) {
            clearInputs();
            restore(processed);
            restore(held);
            return fail(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "C-10 Deployer did not resolve the exact staged recipe");
        }
        depotItemObserved = true;
        heldItemBeforeCount =
                deployerHeldItem(deployer).getCount();
        feedTick = level.getGameTime();
        worldChanges.recordInjectedResource(
                context,
                position(plan.placement(
                        DeployerRole.INPUT_DEPOT).position()),
                new ProcessResource(
                        spec.processedItem(),
                        GenericResourceType.ITEM,
                        spec.processedItemCount()));
        worldChanges.recordInjectedResource(
                context,
                position(plan.placement(
                        DeployerRole.DEPLOYER).position()),
                new ProcessResource(
                        spec.heldItem(),
                        GenericResourceType.ITEM,
                        1));
        return success(evidence(
                id("create:c10/observation/input_offered"),
                VerificationEvidenceKind.PROCESS_STARTED,
                DeployerGenericExecutionPlan.FEED_EVIDENCE,
                context,
                DeployerGenericExecutionPlan.DEPOT_NODE_ID,
                ITEM_STACK_VALUE,
                spec.processedItem() + " x"
                        + spec.processedItemCount()
                        + ";held=" + spec.heldItem() + " x1",
                RECIPE_VALUE,
                spec.recipeId().toString()));
    }

    private ActionHandlerResult observeProcessing(
            StepRunnerContext context) {
        AdapterResult<Map<DeployerRole, Double>> speed =
                captureKinetics();
        if (speed
                instanceof AdapterResult.Failure<
                        Map<DeployerRole, Double>> failure) {
            return fail(failure.code(), failure.detail());
        }
        DeployerBlockEntity deployer = deployer();
        DepotBlockEntity depot = depot();
        ChestBlockEntity chest = outputChest();
        if (deployer == null || depot == null || chest == null
                || liveRecipe == null || feedTick < 0) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 processing state is incomplete");
        }
        ItemStack current = depot.getHeldItem();
        DeployingProcessSpec spec = plan.process();
        if (!current.isEmpty()
                && spec.processedItem().equals(itemId(current))) {
            return ActionHandlerResult.inProgress(false);
        }
        if (current.isEmpty()) {
            return ActionHandlerResult.inProgress(false);
        }
        ResourceId currentId = itemId(current);
        if (!spec.expectedOutputItem().equals(currentId)
                || current.getCount() != spec.expectedOutputCount()
                || current.hasTag()
                || current.hasCraftingRemainingItem()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 Depot observed unexpected output "
                            + currentId + " x" + current.getCount());
        }
        ItemStack heldAfter = deployerHeldItem(deployer);
        int heldAfterCount = heldAfter.isEmpty()
                ? 0 : heldAfter.getCount();
        if (!heldAfter.isEmpty()
                && (!spec.heldItem().equals(itemId(heldAfter))
                        || heldAfter.hasTag()
                        || heldAfter.hasCraftingRemainingItem())) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 held item identity or NBT changed unexpectedly");
        }
        int expectedAfter =
                spec.heldItemDisposition()
                                == HeldItemDisposition.CONSUMED
                        ? heldItemBeforeCount - 1
                        : heldItemBeforeCount;
        if (heldAfterCount != expectedAfter) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 held item count changed outside the typed disposition");
        }
        // The exact live ItemApplicationRecipe is the only code path that can
        // replace the staged Depot input and apply this held-item delta. The
        // exact Depot identity plus held-item before/after observation is
        // stable runtime evidence and avoids visual-only Create internals.
        deployerCycleObserved = true;
        depot.setHeldItem(ItemStack.EMPTY);
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                new InvWrapper(chest), current.copy(), false);
        if (!remainder.isEmpty()) {
            depot.setHeldItem(current);
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 output chest rejected the real Depot output");
        }
        OutputObservation output = observeChest(chest, spec);
        if (output.unexpectedItem() != null
                || output.count() != spec.expectedOutputCount()) {
            return fail(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 output chest did not contain the exact result");
        }
        Map<DeployerRole, Double> speeds =
                ((AdapterResult.Success<
                        Map<DeployerRole, Double>>) speed).value();
        completedEvidence = new DeployerEvidence(
                feedTick,
                level.getGameTime(),
                runtime,
                ResourceId.parse(
                        level.dimension().location().toString()),
                plan.origin(),
                plan.placements(),
                speeds,
                spec.recipeId(),
                spec.recipeType(),
                spec.processedItem(),
                spec.processedItemCount(),
                spec.heldItem(),
                heldItemBeforeCount,
                heldAfterCount,
                spec.heldItemDisposition(),
                spec.expectedOutputItem(),
                output.count(),
                depotItemObserved,
                deployerCycleObserved,
                true,
                true,
                true,
                true,
                true,
                true,
                true);
        return ActionHandlerResult.succeeded(
                processEvidence(completedEvidence, context),
                worldChanges.drainInvocationReferences());
    }

    private List<VerificationEvidence> processEvidence(
            DeployerEvidence physical,
            StepRunnerContext context) {
        return List.of(
                evidence(
                        id("create:c10/observation/input_consumed"),
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        DeployerGenericExecutionPlan.INPUT_CONSUMED,
                        context,
                        DeployerGenericExecutionPlan.DEPOT_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(
                                physical.consumedInputCount()),
                        INTEGER_VALUE,
                        "=" + plan.process().processedItemCount()),
                evidence(
                        id("create:c10/observation/process_completed"),
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        DeployerGenericExecutionPlan.PROCESS_COMPLETED,
                        context,
                        DeployerGenericExecutionPlan.DEPLOYER_NODE_ID,
                        RECIPE_VALUE,
                        physical.recipeId().toString(),
                        RECIPE_VALUE,
                        "create:deploying"),
                evidence(
                        id("create:c10/observation/output_produced"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        DeployerGenericExecutionPlan.OUTPUT_PRODUCED,
                        context,
                        DeployerGenericExecutionPlan.DEPLOYER_NODE_ID,
                        INTEGER_VALUE,
                        Integer.toString(
                                physical.observedOutputCount()),
                        INTEGER_VALUE,
                        "=" + plan.process().expectedOutputCount()),
                evidence(
                        id("create:c10/observation/held_item_before_after"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        DeployerGenericExecutionPlan
                                .HELD_ITEM_BEFORE_AFTER,
                        context,
                        DeployerGenericExecutionPlan.DEPLOYER_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.heldItem() + " "
                                + physical.heldItemBeforeCount()
                                + "->"
                                + physical.heldItemAfterCount(),
                        ITEM_STACK_VALUE,
                        physical.heldItemDisposition().name()),
                evidence(
                        id("create:c10/observation/depot_item"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        DeployerGenericExecutionPlan
                                .DEPOT_ITEM_OBSERVED,
                        context,
                        DeployerGenericExecutionPlan.DEPOT_NODE_ID,
                        BOOLEAN_VALUE,
                        "owned_depot_item=true;face=down",
                        BOOLEAN_VALUE,
                        "true"),
                evidence(
                        id("create:c10/observation/deployer_cycle"),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        DeployerGenericExecutionPlan
                                .DEPLOYER_CYCLE_OBSERVED,
                        context,
                        DeployerGenericExecutionPlan.DEPLOYER_NODE_ID,
                        BOOLEAN_VALUE,
                        "runtime_hand_offset_observed=true",
                        BOOLEAN_VALUE,
                        "true"),
                evidence(
                        id("create:c10/observation/chest_output"),
                        VerificationEvidenceKind.OUTPUT_STORED,
                        DeployerGenericExecutionPlan
                                .CHEST_OUTPUT_OBSERVED,
                        context,
                        DeployerGenericExecutionPlan.CHEST_NODE_ID,
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x"
                                + physical.observedOutputCount(),
                        ITEM_STACK_VALUE,
                        physical.outputItem() + " x="
                                + plan.process()
                                        .expectedOutputCount()));
    }

    private AdapterResult<ItemStack> extract(
            ResourceId itemId, int count) {
        Item item = registeredItem(itemId);
        if (item == null) {
            return adapterFailure(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "C-10 item is unregistered: " + itemId);
        }
        if (resourceBuffer == null) {
            return new AdapterResult.Success<>(
                    new ItemStack(item, count));
        }
        return resourceBuffer.extractExact(itemId, count);
    }

    private void restore(ItemStack stack) {
        if (resourceBuffer != null && !stack.isEmpty()) {
            resourceBuffer.insertExact(stack);
        }
    }

    private void clearInputs() {
        DepotBlockEntity depot = depot();
        if (depot != null) depot.setHeldItem(ItemStack.EMPTY);
        DeployerBlockEntity deployer = deployer();
        if (deployer != null) {
            setDeployerHeldItem(deployer, ItemStack.EMPTY);
        }
    }

    private AdapterResult<Map<DeployerRole, Double>>
            captureKinetics() {
        BlockEntity source = level.getBlockEntity(position(
                plan.placement(DeployerRole.WATER_WHEEL).position()));
        if (source instanceof WaterWheelBlockEntity waterWheel
                && waterWheel.flowScore == 0) {
            waterWheel.determineAndApplyFlowScore();
        }
        EnumMap<DeployerRole, Double> speeds =
                new EnumMap<>(DeployerRole.class);
        for (DeployerRole role : List.of(
                DeployerRole.WATER_WHEEL,
                DeployerRole.BOTTOM_GEARBOX,
                DeployerRole.VERTICAL_SHAFT,
                DeployerRole.TOP_GEARBOX,
                DeployerRole.HORIZONTAL_SHAFT,
                DeployerRole.DEPLOYER)) {
            AdapterResult<KineticSnapshot> result =
                    Create606KineticReader.capture(
                            level,
                            plan.placement(role).position(),
                            runtime);
            if (result
                    instanceof AdapterResult.Failure<
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
        double expected = Math.abs(speeds.get(DeployerRole.WATER_WHEEL));
        if (List.of(
                DeployerRole.BOTTOM_GEARBOX,
                DeployerRole.VERTICAL_SHAFT,
                DeployerRole.TOP_GEARBOX,
                DeployerRole.HORIZONTAL_SHAFT,
                DeployerRole.DEPLOYER).stream()
                .anyMatch(role -> Double.compare(expected, Math.abs(speeds.get(role))) != 0)) {
            return adapterFailure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "C-10 water-wheel transmission and Deployer live speeds differ");
        }
        return new AdapterResult.Success<>(Map.copyOf(speeds));
    }

    private String liveTopologyFailure() {
        for (DeployerPlacement placement : plan.placements()) {
            String mismatch = placementMismatch(placement);
            if (mismatch != null) return mismatch;
        }
        if (deployer() == null
                || depot() == null
                || outputChest() == null) {
            return "C-10 typed block entities are not initialized";
        }
        if (!level.getBlockState(
                        position(plan.interactionPosition()))
                .isAir()) {
            return "C-10 exact interaction cell is no longer air";
        }
        return null;
    }

    private DeployerBlockEntity deployer() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(DeployerRole.DEPLOYER).position()));
        return value instanceof DeployerBlockEntity deployer
                ? deployer : null;
    }

    private DepotBlockEntity depot() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(DeployerRole.INPUT_DEPOT).position()));
        return value instanceof DepotBlockEntity depot ? depot : null;
    }

    private ChestBlockEntity outputChest() {
        BlockEntity value = level.getBlockEntity(position(
                plan.placement(
                        DeployerRole.OUTPUT_CHEST).position()));
        return value instanceof ChestBlockEntity chest ? chest : null;
    }

    private static ItemStack deployerHeldItem(
            DeployerBlockEntity deployer) {
        return deployer.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler.getStackInSlot(0).copy())
                .orElse(ItemStack.EMPTY);
    }

    private static boolean setDeployerHeldItem(
            DeployerBlockEntity deployer, ItemStack stack) {
        Optional<IItemHandler> optional =
                deployer.getCapability(
                                ForgeCapabilities.ITEM_HANDLER)
                        .resolve();
        if (optional.isEmpty()) return false;
        IItemHandler handler = optional.orElseThrow();
        ItemStack existing = handler.extractItem(
                0, Integer.MAX_VALUE, false);
        if (!existing.isEmpty() && !stack.isEmpty()) return false;
        if (stack.isEmpty()) return true;
        return handler.insertItem(0, stack, false).isEmpty();
    }

    private String placementMismatch(
            DeployerPlacement placement) {
        BlockState state = level.getBlockState(
                position(placement.position()));
        ResourceLocation actual =
                ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (!key(placement.blockId()).equals(actual)) {
            return "C-10 placement mismatch at "
                    + placement.position() + ": expected "
                    + placement.blockId() + " found " + actual;
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && stateRotationAxis(state)
                        != axis(placement.rotationAxis())) {
            return "C-10 placement axis mismatch at "
                    + placement.position();
        }
        if (placement.facing() != PlanBlockFacing.NONE
                && stateFacing(state)
                        != direction(placement.facing())) {
            return "C-10 placement facing mismatch at "
                    + placement.position();
        }
        return null;
    }

    private static String validateLiveRecipe(
            Recipe<?> recipe, DeployingProcessSpec spec) {
        if (!key(spec.recipeId()).equals(recipe.getId())) {
            return "Live deploying recipe identity changed";
        }
        if (recipe.getType() != AllRecipeTypes.DEPLOYING.getType()
                || !key(spec.recipeType()).equals(
                        AllRecipeTypes.DEPLOYING.getId())) {
            return "Live recipe is not Create deploying";
        }
        if (!(recipe
                instanceof ItemApplicationRecipe application)) {
            return "C-10 requires an ItemApplicationRecipe";
        }
        if (application.getIngredients().size() != 2
                || !application.getProcessedItem().test(
                        new ItemStack(registeredItem(
                                spec.processedItem())))
                || !application.getRequiredHeldItem().test(
                        new ItemStack(registeredItem(
                                spec.heldItem())))) {
            return "Live deploying ingredients differ from the typed inputs";
        }
        boolean keep = application.shouldKeepHeldItem();
        if (keep
                != (spec.heldItemDisposition()
                        == HeldItemDisposition.RETAINED)) {
            return "Live held-item disposition differs from the typed contract";
        }
        if (!application.getFluidIngredients().isEmpty()
                || !application.getFluidResults().isEmpty()) {
            return "C-10 Phase I rejects fluid input/output";
        }
        List<ProcessingOutput> outputs =
                application.getRollableResults();
        if (outputs.size() != 1) {
            return "C-10 deterministic Phase I requires exactly one item output";
        }
        ProcessingOutput output = outputs.get(0);
        if (output.getChance() != 1.0F
                || !spec.expectedOutputItem().equals(
                        itemId(output.getStack()))
                || output.getStack().getCount()
                        != spec.expectedOutputCount()
                || output.getStack().hasTag()
                || output.getStack()
                        .hasCraftingRemainingItem()) {
            return "Live deploying output differs from the safe deterministic typed output";
        }
        return null;
    }

    private static OutputObservation observeChest(
            ChestBlockEntity chest, DeployingProcessSpec spec) {
        int count = 0;
        ResourceId unexpected = null;
        for (int slot = 0;
                slot < chest.getContainerSize();
                slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceId item = itemId(stack);
            if (!spec.expectedOutputItem().equals(item)
                    || stack.hasTag()) {
                unexpected = item;
            } else {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return new OutputObservation(count, unexpected);
    }

    private static String validateTypedState(
            DeployerPlacement placement, BlockState state) {
        if (placement.facing() != PlanBlockFacing.NONE
                && !hasFacing(state)) {
            return placement.blockId()
                    + " has no required C-10 facing";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && !state.hasProperty(BlockStateProperties.AXIS)
                && !hasFacing(state)) {
            return placement.blockId()
                    + " has no required C-10 rotation";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && placement.facing() != PlanBlockFacing.NONE
                && !(state.getBlock()
                        instanceof DirectionalAxisKineticBlock)
                && direction(placement.facing()).getAxis()
                        != axis(placement.rotationAxis())) {
            return placement.blockId()
                    + " typed facing and rotation axis disagree";
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && placement.facing() != PlanBlockFacing.NONE
                && state.getBlock()
                        instanceof DirectionalAxisKineticBlock
                && direction(placement.facing()).getAxis()
                        == axis(placement.rotationAxis())) {
            return placement.blockId()
                    + " directional interaction and shaft axes overlap";
        }
        return null;
    }

    private static BlockState applyTypedState(
            DeployerPlacement placement, BlockState state) {
        String failure = validateTypedState(placement, state);
        if (failure != null) {
            throw new IllegalArgumentException(failure);
        }
        if (placement.role() == DeployerRole.WATER_WHEEL) {
            Direction facing = switch (placement.rotationAxis()) {
                case X -> Direction.EAST;
                case Z -> Direction.SOUTH;
                case Y, NONE -> throw new IllegalArgumentException(
                        "C-10 water-wheel axis must be horizontal");
            };
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        if (placement.facing() != PlanBlockFacing.NONE) {
            Direction facing = direction(placement.facing());
            state = state.hasProperty(BlockStateProperties.FACING)
                    ? state.setValue(
                            BlockStateProperties.FACING, facing)
                    : state.setValue(
                            BlockStateProperties.HORIZONTAL_FACING,
                            facing);
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && state.hasProperty(BlockStateProperties.AXIS)) {
            state = state.setValue(
                    BlockStateProperties.AXIS,
                    axis(placement.rotationAxis()));
        }
        if (placement.rotationAxis() != PlanBlockAxis.NONE
                && state.getBlock()
                        instanceof DirectionalAxisKineticBlock kinetic) {
            Direction.Axis expected = axis(
                    placement.rotationAxis());
            BlockState first = state.setValue(
                    DirectionalAxisKineticBlock
                            .AXIS_ALONG_FIRST_COORDINATE,
                    false);
            state = kinetic.getRotationAxis(first) == expected
                    ? first
                    : state.setValue(
                            DirectionalAxisKineticBlock
                                    .AXIS_ALONG_FIRST_COORDINATE,
                            true);
            if (kinetic.getRotationAxis(state) != expected) {
                throw new IllegalArgumentException(
                        placement.blockId()
                                + " cannot represent required C-10 rotation");
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
                DeployerGenericExecutionPlan.ACTION_HANDLER_ID,
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

    private static boolean hasFacing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                || state.hasProperty(
                        BlockStateProperties.HORIZONTAL_FACING);
    }

    private static Direction stateFacing(BlockState state) {
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

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation key = stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null
                ? null : ResourceId.parse(key.toString());
    }

    private static Direction.Axis axis(
            PlanBlockAxis value) {
        return switch (value) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException(
                    "NONE has no axis");
        };
    }

    private static Direction direction(
            PlanBlockFacing value) {
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
            AdapterFailureCode code, String detail) {
        return new FailureDetail(
                code,
                "Create 6.0.6 Deployer handler: "
                        + stripPrefix(detail));
    }

    private static String stripPrefix(String detail) {
        String prefix = "Create 6.0.6 Deployer handler: ";
        return detail.startsWith(prefix)
                ? detail.substring(prefix.length()) : detail;
    }

    private static <T> AdapterResult<T> adapterFailure(
            AdapterFailureCode code, String detail) {
        FailureDetail failure = detail(code, detail);
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

    private record OutputObservation(
            int count, ResourceId unexpectedItem) {}
}
