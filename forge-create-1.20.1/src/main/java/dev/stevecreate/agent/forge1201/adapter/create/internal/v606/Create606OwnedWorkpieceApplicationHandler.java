package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.ManualApplicationRecipe;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPolicy;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Real Create 6.0.6 C-10 application to one verified, session-owned world workpiece.
 *
 * <p>This handler intentionally does not expose a general right-click primitive. It installs a
 * fixed downward Deployer, stages one allowlisted held item through the Deployer capability and
 * observes Create's normal server tick transform the exact workpiece. Preflight rejects block
 * entities, entities, NBT, by-products, arbitrary coordinates and every non-allowlisted recipe.
 */
final class Create606OwnedWorkpieceApplicationHandler {
    private static final ResourceId BUILD_DRIVE_STEP =
            id("create:c10_owned_workpiece/build_drive");
    private static final ResourceId BUILD_DEPLOYER_STEP =
            id("create:c10_owned_workpiece/build_deployer");
    private static final ResourceId APPLY_STEP =
            id("create:c10_owned_workpiece/apply");
    private static final ResourceId CLEANUP_STEP =
            id("create:c10_owned_workpiece/cleanup");
    private static final int PROCESS_TIMEOUT_TICKS = 400;

    enum Phase {
        BUILD_DRIVE,
        BUILD_DEPLOYER,
        AWAIT_POWER,
        STAGE_HELD_ITEM,
        OBSERVE_APPLICATION,
        COMPLETED,
        CANCELLED
    }

    record Update(
            Phase phase,
            long startTick,
            long observationTick,
            ResourceId recipeId,
            ResourceId initialBlock,
            ResourceId resultBlock,
            int heldBefore,
            int heldAfter,
            boolean realDeployerCycleObserved,
            boolean exactWorkpieceTransitionObserved,
            boolean unrelatedWorldStateUnchanged) {}

    /** Exact pre-resource checkpoint; resource-bearing phases are intentionally unrepresentable. */
    record RecoveryCheckpoint(
            ResourceId verifiedPhysicalPlanId,
            ResourceId sessionId,
            String runtimeIdentity,
            String worldIdentity,
            String dimensionId,
            BlockPos3i resourceBufferPosition,
            Phase phase,
            long savedTick,
            WorldChangeJournal journal,
            Map<BlockPos3i, WorldBlockSnapshot> unrelatedBoundary) {
        RecoveryCheckpoint {
            Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
            Objects.requireNonNull(worldIdentity, "worldIdentity");
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(resourceBufferPosition, "resourceBufferPosition");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(journal, "journal");
            unrelatedBoundary = Map.copyOf(Objects.requireNonNull(
                    unrelatedBoundary, "unrelatedBoundary"));
            if (runtimeIdentity.isBlank()
                    || worldIdentity.isBlank()
                    || dimensionId.isBlank()
                    || phase != Phase.AWAIT_POWER
                    || savedTick < 0) {
                throw new IllegalArgumentException(
                        "owned-workpiece recovery admits only an AWAIT_POWER checkpoint");
            }
            if (!journal.sessionId().equals(sessionId)) {
                throw new IllegalArgumentException(
                        "owned-workpiece recovery journal belongs to another session");
            }
        }
    }

    private final ServerLevel level;
    private final OwnedWorkpieceApplicationPlan plan;
    private final RuntimeFingerprint runtime;
    private final Create606WorldResourceBuffer resourceBuffer;
    private final Create606WorldChangeJournal worldChanges;
    private final WorldBlockSnapshot driveBefore;
    private final WorldBlockSnapshot deployerBefore;
    private final WorldBlockSnapshot workpieceBefore;
    private final Map<BlockPos3i, WorldBlockSnapshot> unrelatedBefore;
    private final ManualApplicationRecipe recipe;
    private Phase phase = Phase.BUILD_DRIVE;
    private long startTick = -1;
    private int heldBefore;

    private Create606OwnedWorkpieceApplicationHandler(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            RuntimeFingerprint runtime,
            Create606WorldResourceBuffer resourceBuffer,
            ManualApplicationRecipe recipe) {
        this.level = level;
        this.plan = plan;
        this.runtime = runtime;
        this.resourceBuffer = resourceBuffer;
        this.worldChanges = new Create606WorldChangeJournal(
                level, plan.policy().sessionId());
        this.driveBefore = worldChanges.capture(pos(plan.drivePosition()));
        this.deployerBefore = worldChanges.capture(pos(plan.deployerPosition()));
        this.workpieceBefore = worldChanges.capture(pos(
                plan.policy().workpiecePosition()));
        this.unrelatedBefore = captureUnrelatedBoundary(level, plan, worldChanges);
        this.recipe = recipe;
    }

    private Create606OwnedWorkpieceApplicationHandler(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            RuntimeFingerprint runtime,
            Create606WorldResourceBuffer resourceBuffer,
            ManualApplicationRecipe recipe,
            WorldChangeJournal recoveredJournal,
            WorldBlockSnapshot driveBefore,
            WorldBlockSnapshot deployerBefore,
            WorldBlockSnapshot workpieceBefore,
            Map<BlockPos3i, WorldBlockSnapshot> unrelatedBefore) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.resourceBuffer = Objects.requireNonNull(resourceBuffer, "resourceBuffer");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.worldChanges = new Create606WorldChangeJournal(
                level, plan.policy().sessionId(), recoveredJournal);
        this.driveBefore = Objects.requireNonNull(driveBefore, "driveBefore");
        this.deployerBefore = Objects.requireNonNull(deployerBefore, "deployerBefore");
        this.workpieceBefore = Objects.requireNonNull(workpieceBefore, "workpieceBefore");
        this.unrelatedBefore = Map.copyOf(Objects.requireNonNull(
                unrelatedBefore, "unrelatedBefore"));
        this.phase = Phase.AWAIT_POWER;
    }

    static AdapterResult<Create606OwnedWorkpieceApplicationHandler> begin(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            RuntimeFingerprint runtime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        BlockPos3i resourceBufferPosition = plan.resourceBufferPosition();
        AdapterResult<ManualApplicationRecipe> validation = validate(level, plan);
        if (validation instanceof AdapterResult.Failure<ManualApplicationRecipe> failure) {
            return failure(failure.code(), failure.detail());
        }
        if (plan.ownedMutationPositions().contains(resourceBufferPosition)) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "owned resource buffer overlaps the C-10 mutation boundary");
        }
        Create606WorldResourceBuffer buffer =
                new Create606WorldResourceBuffer(level, resourceBufferPosition);
        AdapterResult<Integer> availableResult =
                buffer.availableExactNbtFree(plan.policy().heldItem());
        if (availableResult instanceof AdapterResult.Failure<Integer> failure) {
            return failure(failure.code(), failure.detail());
        }
        int available = ((AdapterResult.Success<Integer>) availableResult).value();
        AdapterResult<Map<ResourceId, Long>> snapshotResult = buffer.snapshot();
        if (snapshotResult instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
            return failure(failure.code(), failure.detail());
        }
        Map<ResourceId, Long> snapshot =
                ((AdapterResult.Success<Map<ResourceId, Long>>) snapshotResult).value();
        if (available != 1
                || !snapshot.equals(Map.of(plan.policy().heldItem(), 1L))) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "owned resource buffer must contain only the one exact allowlisted held item");
        }
        return new AdapterResult.Success<>(
                new Create606OwnedWorkpieceApplicationHandler(
                        level,
                        plan,
                        runtime,
                        buffer,
                        ((AdapterResult.Success<ManualApplicationRecipe>) validation).value()));
    }

    static AdapterResult<Create606OwnedWorkpieceApplicationHandler> recover(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            RuntimeFingerprint runtime,
            RecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(checkpoint, "checkpoint");
        BlockPos3i resourceBufferPosition = plan.resourceBufferPosition();
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece recovery must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return failure(
                    AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        OwnedWorkpieceApplicationPolicy policy = plan.policy();
        if (!checkpoint.verifiedPhysicalPlanId().equals(policy.verifiedPhysicalPlanId())
                || !checkpoint.sessionId().equals(policy.sessionId())
                || !checkpoint.runtimeIdentity().equals(runtime.canonicalIdentity())
                || !checkpoint.worldIdentity().equals(worldIdentity(level))
                || !checkpoint.dimensionId().equals(level.dimension().location().toString())
                || !checkpoint.resourceBufferPosition().equals(resourceBufferPosition)
                || checkpoint.savedTick() > level.getGameTime()
                || plan.ownedMutationPositions().contains(resourceBufferPosition)) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "owned-workpiece recovery identity, tick or resource boundary changed");
        }
        if (!policy.initialBlock().equals(
                        blockId(level.getBlockState(pos(policy.workpiecePosition()))))
                || level.getBlockEntity(pos(policy.workpiecePosition())) != null) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery found a changed workpiece");
        }
        String boundaryFailure = liveSafetyBoundaryFailure(level, plan, true);
        if (boundaryFailure != null) {
            return failure(AdapterFailureCode.PLAN_REJECTED, boundaryFailure);
        }
        AdapterResult<ManualApplicationRecipe> recipeResult = validateLiveRecipe(
                level, plan, level.getBlockState(pos(policy.workpiecePosition())));
        if (recipeResult instanceof AdapterResult.Failure<ManualApplicationRecipe> failure) {
            return failure(failure.code(), failure.detail());
        }
        List<WorldChangeJournal.Entry> entries = checkpoint.journal().entries();
        if (entries.size() != 2
                || !(entries.get(0) instanceof BlockChange driveChange)
                || !(entries.get(1) instanceof BlockChange deployerChange)
                || !driveChange.sourceStepId().equals(BUILD_DRIVE_STEP)
                || !driveChange.position().equals(plan.drivePosition())
                || !deployerChange.sourceStepId().equals(BUILD_DEPLOYER_STEP)
                || !deployerChange.position().equals(plan.deployerPosition())
                || !id("minecraft:air").equals(driveChange.before().blockId())
                || !id("minecraft:air").equals(deployerChange.before().blockId())) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery journal is not the exact two-step build prefix");
        }
        Create606WorldChangeJournal observed = new Create606WorldChangeJournal(
                level, policy.sessionId(), checkpoint.journal());
        if (!driveChange.after().equals(observed.capture(pos(plan.drivePosition())))
                || !deployerChange.after().equals(observed.capture(pos(plan.deployerPosition())))) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery machine state drifted after checkpoint");
        }
        DeployerBlockEntity deployer = level.getBlockEntity(pos(plan.deployerPosition()))
                instanceof DeployerBlockEntity value ? value : null;
        if (deployer == null || !deployerHeldItem(deployer).isEmpty()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery refuses a missing or resource-bearing Deployer");
        }
        Map<BlockPos3i, WorldBlockSnapshot> liveBoundary = captureUnrelatedBoundary(
                level, plan, observed);
        if (!liveBoundary.equals(checkpoint.unrelatedBoundary())) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery unrelated boundary changed after checkpoint");
        }
        Create606WorldResourceBuffer buffer = new Create606WorldResourceBuffer(
                level, resourceBufferPosition);
        AdapterResult<Integer> available = buffer.availableExactNbtFree(policy.heldItem());
        AdapterResult<Map<ResourceId, Long>> bufferSnapshot = buffer.snapshot();
        if (available instanceof AdapterResult.Failure<Integer> failure
                || bufferSnapshot instanceof AdapterResult.Failure<Map<ResourceId, Long>>
                || ((AdapterResult.Success<Integer>) available).value() != 1
                || !((AdapterResult.Success<Map<ResourceId, Long>>) bufferSnapshot).value()
                        .equals(Map.of(policy.heldItem(), 1L))) {
            return failure(
                    available instanceof AdapterResult.Failure<Integer> failure
                            ? failure.code() : AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery requires the one exact unconsumed held item");
        }
        return new AdapterResult.Success<>(new Create606OwnedWorkpieceApplicationHandler(
                level,
                plan,
                runtime,
                buffer,
                ((AdapterResult.Success<ManualApplicationRecipe>) recipeResult).value(),
                checkpoint.journal(),
                driveChange.before(),
                deployerChange.before(),
                observed.capture(pos(policy.workpiecePosition())),
                checkpoint.unrelatedBoundary()));
    }

    static AdapterResult<ManualApplicationRecipe> validate(
            ServerLevel level, OwnedWorkpieceApplicationPlan plan) {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece preflight must run on the authoritative server thread");
        }
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            return failure(
                    AdapterFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow());
        }
        OwnedWorkpieceApplicationPolicy policy = plan.policy();
        for (BlockPos3i position : plan.ownedMutationPositions()) {
            if (!level.hasChunk(position.x() >> 4, position.z() >> 4)) {
                return failure(
                        AdapterFailureCode.CHUNK_NOT_LOADED,
                        "owned mutation chunk is not loaded at " + position);
            }
        }
        if (!level.getBlockState(pos(plan.drivePosition())).isAir()
                || !level.getBlockState(pos(plan.deployerPosition())).isAir()) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "owned Deployer machine cells must be exact air before placement");
        }
        BlockPos workpiece = pos(policy.workpiecePosition());
        BlockState initial = level.getBlockState(workpiece);
        if (!policy.initialBlock().equals(blockId(initial))) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "workpiece does not match the exact reviewed initial block");
        }
        if (level.getBlockEntity(workpiece) != null) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "C-10 refuses a block-entity or container workpiece");
        }
        String boundaryFailure = liveSafetyBoundaryFailure(level, plan, false);
        if (boundaryFailure != null) {
            return failure(AdapterFailureCode.PLAN_REJECTED, boundaryFailure);
        }
        return validateLiveRecipe(level, plan, initial);
    }

    private static AdapterResult<ManualApplicationRecipe> validateLiveRecipe(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            BlockState initial) {
        OwnedWorkpieceApplicationPolicy policy = plan.policy();
        Optional<? extends Recipe<?>> found = level.getRecipeManager().byKey(
                key(policy.recipeId()));
        if (found.isEmpty()) {
            return failure(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "exact allowlisted item_application recipe is absent");
        }
        Recipe<?> live = found.orElseThrow();
        if (live.getType() != AllRecipeTypes.ITEM_APPLICATION.getType()
                || !(live instanceof ManualApplicationRecipe application)
                || !application.getId().equals(key(policy.recipeId()))) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "live recipe is not the exact Create item_application recipe");
        }
        Item heldItem = registeredItem(policy.heldItem());
        if (heldItem == null
                || !application.getRequiredHeldItem().test(new ItemStack(heldItem))
                || !application.testBlock(initial)
                || application.shouldKeepHeldItem()
                || !application.getRollableResultsExceptBlock().isEmpty()) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "reviewed recipe inputs, consumption or no-by-product boundary changed");
        }
        BlockState predicted = application.transformBlock(initial);
        if (!policy.resultBlock().equals(blockId(predicted))
                || predicted.hasBlockEntity()) {
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "reviewed recipe no longer predicts the exact non-container result block");
        }
        return new AdapterResult.Success<>(application);
    }

    AdapterResult<RecoveryCheckpoint> recoveryCheckpoint() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece recovery checkpoint must run on the server thread");
        }
        if (phase != Phase.AWAIT_POWER) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery checkpoint refuses every resource-bearing phase");
        }
        DeployerBlockEntity deployer = deployer();
        AdapterResult<Integer> available = resourceBuffer.availableExactNbtFree(
                plan.policy().heldItem());
        AdapterResult<Map<ResourceId, Long>> bufferSnapshot = resourceBuffer.snapshot();
        if (deployer == null
                || !deployerHeldItem(deployer).isEmpty()
                || available instanceof AdapterResult.Failure<Integer>
                || bufferSnapshot instanceof AdapterResult.Failure<Map<ResourceId, Long>>
                || ((AdapterResult.Success<Integer>) available).value() != 1
                || !((AdapterResult.Success<Map<ResourceId, Long>>) bufferSnapshot).value()
                        .equals(Map.of(plan.policy().heldItem(), 1L))
                || !plan.policy().initialBlock().equals(blockId(level.getBlockState(
                        pos(plan.policy().workpiecePosition()))))
                || !unrelatedBoundaryUnchanged()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery checkpoint boundary is not exact and pre-resource");
        }
        try {
            worldChanges.stabilizeBlockEntityAfterStates();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery checkpoint could not stabilize exact machine state: "
                            + exception.getMessage());
        }
        WorldChangeJournal journal = worldChanges.snapshot();
        if (journal.entries().size() != 2
                || !journal.modifiedPositions().equals(
                        List.of(plan.drivePosition(), plan.deployerPosition()))) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece recovery checkpoint is not the exact build prefix");
        }
        return new AdapterResult.Success<>(new RecoveryCheckpoint(
                plan.policy().verifiedPhysicalPlanId(),
                plan.policy().sessionId(),
                runtime.canonicalIdentity(),
                worldIdentity(level),
                level.dimension().location().toString(),
                resourceBuffer.position(),
                phase,
                level.getGameTime(),
                journal,
                unrelatedBefore));
    }

    AdapterResult<Update> tick() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece tick must run on the authoritative server thread");
        }
        if (phase == Phase.COMPLETED || phase == Phase.CANCELLED) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece session is already terminal: " + phase);
        }
        String safetyFailure = liveSafetyBoundaryFailure(level, plan, true);
        if (safetyFailure != null) {
            return failure(AdapterFailureCode.PLAN_REJECTED, safetyFailure);
        }
        if (!unrelatedBoundaryUnchanged()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "unrelated block state changed inside the bounded activation volume");
        }
        return switch (phase) {
            case BUILD_DRIVE -> buildDrive();
            case BUILD_DEPLOYER -> buildDeployer();
            case AWAIT_POWER -> awaitPower();
            case STAGE_HELD_ITEM -> stageHeldItem();
            case OBSERVE_APPLICATION -> observeApplication();
            case COMPLETED, CANCELLED -> throw new IllegalStateException("terminal phase");
        };
    }

    AdapterResult<RollbackReport> cancel() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece cancellation must run on the server thread");
        }
        if (phase == Phase.COMPLETED || phase == Phase.CANCELLED) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece session is already terminal: " + phase);
        }
        if (!plan.policy().initialBlock().equals(
                        blockId(level.getBlockState(pos(
                                plan.policy().workpiecePosition()))))
                || level.getBlockEntity(pos(plan.policy().workpiecePosition())) != null
                || !unrelatedBoundaryUnchanged()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "cancellation refused a changed workpiece or unrelated boundary");
        }
        DeployerBlockEntity deployer = deployer();
        if (deployer != null) {
            ItemStack held = deployerHeldItem(deployer);
            if (!held.isEmpty()) {
                if (!matchesExactHeldItem(held) || !setDeployerHeldItem(deployer, ItemStack.EMPTY)) {
                    return failure(
                            AdapterFailureCode.PROCESSING_FAILED,
                            "owned-workpiece cancellation refused an unexpected Deployer item");
                }
                AdapterResult<Integer> restored = resourceBuffer.insertExact(held.copy());
                if (restored instanceof AdapterResult.Failure<Integer> failure) {
                    setDeployerHeldItem(deployer, held);
                    return failure(failure.code(), failure.detail());
                }
            }
        }
        try {
            // Kinetic network attachment legitimately changes the owned motor/Deployer BE NBT
            // after placement. Exact block identities and every unrelated boundary were checked
            // above, so stabilize only those journal-owned after-states before conservative undo.
            worldChanges.stabilizeBlockEntityAfterStates();
        } catch (IllegalStateException exception) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "cancellation could not stabilize owned kinetic state: "
                            + exception.getMessage());
        }
        phase = Phase.CANCELLED;
        return new AdapterResult.Success<>(worldChanges.rollback());
    }

    AdapterResult<Boolean> cleanupMachine() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece cleanup must run on the server thread");
        }
        if (phase != Phase.COMPLETED) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece cleanup requires completed processing");
        }
        DeployerBlockEntity deployer = deployer();
        if (deployer == null || !deployerHeldItem(deployer).isEmpty()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "cleanup refused missing Deployer or residual held item");
        }
        BlockPos deployerPosition = pos(plan.deployerPosition());
        BlockPos drivePosition = pos(plan.drivePosition());
        if (!id("create:deployer").equals(blockId(level.getBlockState(deployerPosition)))
                || !id("create:creative_motor").equals(
                        blockId(level.getBlockState(drivePosition)))) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "cleanup refused machine coordinates changed outside session ownership");
        }
        WorldBlockSnapshot currentDeployer = worldChanges.capture(deployerPosition);
        WorldBlockSnapshot currentDrive = worldChanges.capture(drivePosition);
        level.removeBlock(deployerPosition, false);
        level.removeBlock(drivePosition, false);
        if (!level.getBlockState(deployerPosition).isAir()
                || !level.getBlockState(drivePosition).isAir()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "cleanup did not restore the exact empty machine cells");
        }
        worldChanges.recordBlockChange(
                CLEANUP_STEP, level.getGameTime(), deployerPosition, currentDeployer);
        worldChanges.recordBlockChange(
                CLEANUP_STEP, level.getGameTime(), drivePosition, currentDrive);
        return new AdapterResult.Success<>(true);
    }

    /** Fresh post-cleanup readback used by the shared executor verification task. */
    AdapterResult<Boolean> verifyTerminalBoundary() {
        if (!level.getServer().isSameThread()) {
            return failure(
                    AdapterFailureCode.WRONG_THREAD,
                    "owned-workpiece terminal verification must run on the server thread");
        }
        if (phase != Phase.COMPLETED
                || !plan.policy().resultBlock().equals(blockId(level.getBlockState(
                        pos(plan.policy().workpiecePosition()))))
                || level.getBlockEntity(pos(plan.policy().workpiecePosition())) != null
                || !level.getBlockState(pos(plan.drivePosition())).isAir()
                || !level.getBlockState(pos(plan.deployerPosition())).isAir()
                || !unrelatedBoundaryUnchanged()
                || liveSafetyBoundaryFailure(level, plan, false) != null) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece terminal world boundary changed after cleanup");
        }
        AdapterResult<Map<ResourceId, Long>> snapshot = resourceBuffer.snapshot();
        if (snapshot instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
            return failure(failure.code(), failure.detail());
        }
        if (!((AdapterResult.Success<Map<ResourceId, Long>>) snapshot).value().isEmpty()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned-workpiece terminal resource buffer is not exactly empty");
        }
        return new AdapterResult.Success<>(true);
    }

    WorldChangeJournal worldChangeJournal() {
        return worldChanges.snapshot();
    }

    private AdapterResult<Update> buildDrive() {
        Block motor = registeredBlock(id("create:creative_motor"));
        if (motor == null) {
            return failure(AdapterFailureCode.PLACEMENT_FAILED, "creative motor is absent");
        }
        BlockState state = motor.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        }
        BlockPos target = pos(plan.drivePosition());
        if (!level.setBlockAndUpdate(target, state)) {
            return failure(AdapterFailureCode.PLACEMENT_FAILED, "drive placement was rejected");
        }
        worldChanges.recordBlockChange(BUILD_DRIVE_STEP, level.getGameTime(), target, driveBefore);
        phase = Phase.BUILD_DEPLOYER;
        return progress();
    }

    private AdapterResult<Update> buildDeployer() {
        Block block = registeredBlock(id("create:deployer"));
        if (block == null) {
            return failure(AdapterFailureCode.PLACEMENT_FAILED, "Deployer is absent");
        }
        BlockState state = block.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN);
        if (!(block instanceof DirectionalAxisKineticBlock kinetic)) {
            return failure(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Create Deployer lost its directional kinetic contract");
        }
        BlockState first = state.setValue(
                DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false);
        state = kinetic.getRotationAxis(first) == Direction.Axis.Z
                ? first
                : state.setValue(
                        DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true);
        if (kinetic.getRotationAxis(state) != Direction.Axis.Z) {
            return failure(
                    AdapterFailureCode.PLACEMENT_FAILED,
                    "Deployer cannot represent the reviewed horizontal shaft axis");
        }
        BlockPos target = pos(plan.deployerPosition());
        if (!level.setBlockAndUpdate(target, state)) {
            return failure(AdapterFailureCode.PLACEMENT_FAILED, "Deployer placement was rejected");
        }
        worldChanges.recordBlockChange(
                BUILD_DEPLOYER_STEP, level.getGameTime(), target, deployerBefore);
        phase = Phase.AWAIT_POWER;
        return progress();
    }

    private AdapterResult<Update> awaitPower() {
        DeployerBlockEntity deployer = deployer();
        if (deployer == null) {
            return progress();
        }
        AdapterResult<KineticSnapshot> result = Create606KineticReader.capture(
                level, plan.deployerPosition(), runtime);
        if (result instanceof AdapterResult.Failure<KineticSnapshot> failure) {
            if (failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY
                    || failure.code() == AdapterFailureCode.KINETIC_COMPONENT_NOT_FOUND) {
                return progress();
            }
            return failure(failure.code(), failure.detail());
        }
        KineticSnapshot snapshot =
                ((AdapterResult.Success<KineticSnapshot>) result).value();
        if (snapshot.speedRpm() == 0 || snapshot.overstressed()) {
            return progress();
        }
        phase = Phase.STAGE_HELD_ITEM;
        return progress();
    }

    private AdapterResult<Update> stageHeldItem() {
        AdapterResult<ManualApplicationRecipe> current = validateLiveRecipeOnly();
        if (current instanceof AdapterResult.Failure<ManualApplicationRecipe> failure) {
            return failure(failure.code(), failure.detail());
        }
        DeployerBlockEntity deployer = deployer();
        if (deployer == null || !deployerHeldItem(deployer).isEmpty()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "owned Deployer is missing or not empty before staging");
        }
        AdapterResult<ItemStack> extracted =
                resourceBuffer.extractExactNbtFree(plan.policy().heldItem(), 1);
        if (extracted instanceof AdapterResult.Failure<ItemStack> failure) {
            return failure(failure.code(), failure.detail());
        }
        ItemStack held = ((AdapterResult.Success<ItemStack>) extracted).value();
        if (!matchesExactHeldItem(held)
                || held.hasTag()
                || held.hasCraftingRemainingItem()
                || !setDeployerHeldItem(deployer, held.copy())) {
            resourceBuffer.insertExact(held);
            return failure(
                    AdapterFailureCode.PLAN_REJECTED,
                    "held item has NBT, remainder semantics or cannot be staged exactly");
        }
        heldBefore = deployerHeldItem(deployer).getCount();
        startTick = level.getGameTime();
        phase = Phase.OBSERVE_APPLICATION;
        return progress();
    }

    private AdapterResult<Update> observeApplication() {
        if (level.getGameTime() - startTick > PROCESS_TIMEOUT_TICKS) {
            return failure(
                    AdapterFailureCode.EXECUTION_TIMEOUT,
                    "real Deployer did not apply the allowlisted recipe before timeout");
        }
        BlockPos workpiece = pos(plan.policy().workpiecePosition());
        BlockState current = level.getBlockState(workpiece);
        ResourceId currentId = blockId(current);
        ItemStack held = deployerHeldItem(deployer());
        if (plan.policy().initialBlock().equals(currentId)) {
            if (!matchesExactHeldItem(held) || held.getCount() != heldBefore || held.hasTag()) {
                return failure(
                        AdapterFailureCode.PROCESSING_FAILED,
                        "held item changed before the reviewed workpiece transition");
            }
            return progress();
        }
        if (!plan.policy().resultBlock().equals(currentId)
                || current.hasBlockEntity()
                || !held.isEmpty()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "Deployer produced an unexpected block, block entity or held-item state");
        }
        if (!unrelatedBoundaryUnchanged()) {
            return failure(
                    AdapterFailureCode.PROCESSING_FAILED,
                    "item application changed unrelated bounded world state");
        }
        worldChanges.recordBlockChange(
                APPLY_STEP, level.getGameTime(), workpiece, workpieceBefore);
        phase = Phase.COMPLETED;
        return new AdapterResult.Success<>(new Update(
                phase,
                startTick,
                level.getGameTime(),
                recipeId(recipe),
                plan.policy().initialBlock(),
                plan.policy().resultBlock(),
                heldBefore,
                0,
                true,
                true,
                true));
    }

    private AdapterResult<ManualApplicationRecipe> validateLiveRecipeOnly() {
        Optional<? extends Recipe<?>> found =
                level.getRecipeManager().byKey(key(plan.policy().recipeId()));
        if (found.isEmpty()
                || found.orElseThrow() != recipe
                || recipe.getType() != AllRecipeTypes.ITEM_APPLICATION.getType()) {
            return failure(
                    AdapterFailureCode.RECIPE_NOT_FOUND,
                    "allowlisted live item_application recipe changed after preflight");
        }
        return new AdapterResult.Success<>(recipe);
    }

    private AdapterResult<Update> progress() {
        return new AdapterResult.Success<>(new Update(
                phase,
                startTick,
                level.getGameTime(),
                plan.policy().recipeId(),
                plan.policy().initialBlock(),
                plan.policy().resultBlock(),
                heldBefore,
                heldBefore,
                false,
                false,
                unrelatedBoundaryUnchanged()));
    }

    private boolean unrelatedBoundaryUnchanged() {
        for (Map.Entry<BlockPos3i, WorldBlockSnapshot> entry : unrelatedBefore.entrySet()) {
            if (!entry.getValue().equals(worldChanges.capture(pos(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesExactHeldItem(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && plan.policy().heldItem().equals(itemId(stack));
    }

    private DeployerBlockEntity deployer() {
        BlockEntity value = level.getBlockEntity(pos(plan.deployerPosition()));
        return value instanceof DeployerBlockEntity deployer ? deployer : null;
    }

    private static String liveSafetyBoundaryFailure(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            boolean machineMayExist) {
        BlockPos workpiece = pos(plan.policy().workpiecePosition());
        BlockPos deployer = pos(plan.deployerPosition());
        BlockPos drive = pos(plan.drivePosition());
        AABB activation = new AABB(workpiece).expandTowards(0, 3, 0).inflate(0.01);
        List<Entity> entities = level.getEntities((Entity) null, activation);
        if (!entities.isEmpty()) {
            return "C-10 refuses every entity inside the bounded activation volume";
        }
        for (BlockPos cursor : BlockPos.betweenClosed(
                workpiece.offset(-1, 0, -1), workpiece.offset(1, 2, 1))) {
            BlockEntity blockEntity = level.getBlockEntity(cursor);
            if (blockEntity != null
                    && !(machineMayExist
                            && cursor.equals(deployer)
                            && blockEntity instanceof DeployerBlockEntity)
                    && !(machineMayExist
                            && cursor.equals(drive)
                            && id("create:creative_motor").equals(
                                    blockId(level.getBlockState(cursor))))) {
                return "C-10 refuses containers or unknown block entities near the workpiece";
            }
        }
        return null;
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> captureUnrelatedBoundary(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            Create606WorldChangeJournal journal) {
        Map<BlockPos3i, WorldBlockSnapshot> snapshots = new LinkedHashMap<>();
        BlockPos workpiece = pos(plan.policy().workpiecePosition());
        BlockPos drive = pos(plan.drivePosition());
        BlockPos deployer = pos(plan.deployerPosition());
        for (BlockPos cursor : BlockPos.betweenClosed(
                workpiece.offset(-1, -1, -1), workpiece.offset(1, 3, 1))) {
            BlockPos immutable = cursor.immutable();
            if (immutable.equals(workpiece)
                    || immutable.equals(drive)
                    || immutable.equals(deployer)) {
                continue;
            }
            snapshots.put(new BlockPos3i(
                    immutable.getX(), immutable.getY(), immutable.getZ()),
                    journal.capture(immutable));
        }
        return Map.copyOf(snapshots);
    }

    private static ItemStack deployerHeldItem(DeployerBlockEntity deployer) {
        if (deployer == null) return ItemStack.EMPTY;
        return deployer.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler.getStackInSlot(0).copy())
                .orElse(ItemStack.EMPTY);
    }

    private static boolean setDeployerHeldItem(
            DeployerBlockEntity deployer, ItemStack stack) {
        Optional<IItemHandler> optional = deployer
                .getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve();
        if (optional.isEmpty()) return false;
        IItemHandler handler = optional.orElseThrow();
        ItemStack existing = handler.extractItem(0, Integer.MAX_VALUE, false);
        if (!existing.isEmpty() && !stack.isEmpty()) {
            handler.insertItem(0, existing, false);
            return false;
        }
        if (stack.isEmpty()) return true;
        ItemStack remainder = handler.insertItem(0, stack, false);
        if (!remainder.isEmpty()) {
            handler.insertItem(0, existing, false);
            return false;
        }
        return true;
    }

    private static Block registeredBlock(ResourceId id) {
        ResourceLocation key = key(id);
        Block value = ForgeRegistries.BLOCKS.getValue(key);
        return value != null && key.equals(ForgeRegistries.BLOCKS.getKey(value))
                ? value
                : null;
    }

    private static Item registeredItem(ResourceId id) {
        ResourceLocation key = key(id);
        Item value = ForgeRegistries.ITEMS.getValue(key);
        return value != null && key.equals(ForgeRegistries.ITEMS.getKey(value))
                ? value
                : null;
    }

    private static ResourceId blockId(BlockState state) {
        ResourceLocation value = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return value == null ? null : ResourceId.parse(value.toString());
    }

    private static ResourceId itemId(ItemStack stack) {
        ResourceLocation value = stack == null || stack.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(stack.getItem());
        return value == null ? null : ResourceId.parse(value.toString());
    }

    private static ResourceId recipeId(Recipe<?> recipe) {
        return ResourceId.parse(recipe.getId().toString());
    }

    private static BlockPos pos(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceLocation key(ResourceId value) {
        return ResourceLocation.fromNamespaceAndPath(value.namespace(), value.path());
    }

    private static String worldIdentity(ServerLevel level) {
        return level.getServer().getWorldData().getLevelName();
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private static <T> AdapterResult<T> failure(
            AdapterFailureCode code, String detail) {
        return new AdapterResult.Failure<>(
                code, "Create 6.0.6 owned-workpiece handler: " + detail);
    }
}
