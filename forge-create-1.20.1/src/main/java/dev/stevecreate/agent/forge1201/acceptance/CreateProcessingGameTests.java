package dev.stevecreate.agent.forge1201.acceptance;

import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionSession;
import dev.stevecreate.agent.adapter.api.CreatePlanExecutionUpdate;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.adapter.api.WaterWheelMillstoneEvidence;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.placement.PlacementTarget;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.ResolvedPlanPlacement;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneRole;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreatePlanAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** C-03 isolated Forge GameTest using a deterministic empty vanilla structure template. */
@PrefixGameTestTemplate(false)
public final class CreateProcessingGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateProcessingGameTests() {
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void waterWheelMillstoneProcessesCobblestone(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void waterWheelMillstoneProcessesCobblestoneClockwise90(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.CLOCKWISE_90);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void waterWheelMillstoneProcessesCobblestoneClockwise270(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.CLOCKWISE_270);
    }

    private static void runPlan(GameTestHelper helper, QuarterTurn rotation) {
        ServerLevel level = helper.getLevel();
        check(level.getServer().isSameThread(), "C-03 GameTest setup is not on the authoritative server thread");

        BlockPos absoluteOrigin = helper.absolutePos(new BlockPos(2, 2, 2));
        WaterWheelMillstonePlan plan = WaterWheelMillstonePlan.at(position(absoluteOrigin), rotation);
        ForgeCreatePlanAdapter adapter = new ForgeCreatePlanAdapter(level);

        AtomicReference<AdapterResult<CreatePlanExecutionSession>> wrongThread = new AtomicReference<>();
        String wrongThreadName = "steve-industrial-c03-wrong-thread-probe-" + rotation.name();
        Thread worker = new Thread(
                () -> wrongThread.set(adapter.begin(plan)),
                wrongThreadName);
        worker.setDaemon(true);
        worker.start();

        ChunkPos remoteChunk = findUnloadedChunk(level, new ChunkPos(absoluteOrigin));
        BlockPos remoteOrigin = new BlockPos(
                remoteChunk.getMinBlockX() + 8,
                absoluteOrigin.getY(),
                remoteChunk.getMinBlockZ() + 8);
        check(!level.hasChunk(remoteChunk.x, remoteChunk.z), "C-03 unloaded-chunk precondition failed");
        AdapterResult<CreatePlanExecutionSession> remote = adapter.begin(
                WaterWheelMillstonePlan.at(position(remoteOrigin), rotation));
        checkFailure(remote, AdapterFailureCode.CHUNK_NOT_LOADED, "unloaded plan preflight");
        String remoteDetail = ((AdapterResult.Failure<CreatePlanExecutionSession>) remote).detail();
        check(remoteDetail.contains("conflicts=17 positions=17"),
                "C-03 unloaded feasibility did not report every target: " + remoteDetail);
        check(!level.hasChunk(remoteChunk.x, remoteChunk.z), "C-03 plan preflight loaded a remote chunk");
        LOGGER.info(
                "CREATE_PROCESSING_UNLOADED_CHUNK chunk={},{} before=false code=CHUNK_NOT_LOADED conflicts=17 positions=17 after=false",
                remoteChunk.x,
                remoteChunk.z);

        verifyFeasibilityRejection(level, plan);
        verifyCancellationProbe(level, adapter, plan);

        AdapterResult<CreatePlanExecutionSession> begin = adapter.begin(plan);
        check(begin instanceof AdapterResult.Success<CreatePlanExecutionSession>, "C-03 begin failed: " + begin);
        CreatePlanExecutionSession session =
                ((AdapterResult.Success<CreatePlanExecutionSession>) begin).value();
        LOGGER.info(
                "CREATE_PROCESSING_PLAN origin={},{},{} rotation={} placements={} preflightPositions={} sequence=\"{}\"",
                plan.origin().x(),
                plan.origin().y(),
                plan.origin().z(),
                rotation,
                plan.placements().size(),
                plan.preflightPositions().size(),
                sequence(plan));

        helper.succeedWhen(() -> {
            AdapterResult<CreatePlanExecutionSession> wrongThreadResult = wrongThread.get();
            if (wrongThreadResult == null) {
                throw new GameTestAssertException("Waiting for non-blocking wrong-thread probe");
            }
            checkFailure(wrongThreadResult, AdapterFailureCode.WRONG_THREAD, "wrong-thread begin");

            AdapterResult<CreatePlanExecutionUpdate> update = session.tick();
            if (update instanceof AdapterResult.Failure<CreatePlanExecutionUpdate> failure) {
                helper.fail("C-03 executor failed: " + failure.code() + " - " + failure.detail());
                return;
            }
            CreatePlanExecutionUpdate value =
                    ((AdapterResult.Success<CreatePlanExecutionUpdate>) update).value();
            if (value instanceof CreatePlanExecutionUpdate.InProgress progress) {
                throw new GameTestAssertException(
                        "C-03 pending phase=" + progress.phase()
                                + " placements=" + progress.completedPlacements() + "/" + progress.totalPlacements());
            }

            WaterWheelMillstoneEvidence evidence = ((CreatePlanExecutionUpdate.Completed) value).evidence();
            verifyEvidence(plan, evidence);
            verifyRotatedBlockStates(level, plan);
            verifyWaterContained(level, plan);
            verifyJournal(session.worldChangeJournal());
            LOGGER.info(
                    "CREATE_PROCESSING_PLACEMENTS PASS count={} order=\"{}\"",
                    evidence.verifiedPlacements().size(),
                    evidence.verifiedPlacements().stream()
                            .map(placement -> placement.order() + ":" + placement.role() + "@"
                                    + placement.position().x() + "," + placement.position().y() + ","
                                    + placement.position().z() + "=" + placement.blockId())
                            .collect(Collectors.joining(";")));
            LOGGER.info(
                    "CREATE_PROCESSING_POWER PASS waterWheelRpm={} gearboxRpm={} shaftRpm={} millstoneRpm={}",
                    evidence.observedSpeedRpm().get(WaterWheelMillstoneRole.WATER_WHEEL),
                    evidence.observedSpeedRpm().get(WaterWheelMillstoneRole.GEARBOX),
                    evidence.observedSpeedRpm().get(WaterWheelMillstoneRole.VERTICAL_SHAFT),
                    evidence.observedSpeedRpm().get(WaterWheelMillstoneRole.MILLSTONE));
            LOGGER.info(
                    "CREATE_PROCESSING_RECIPE PASS id={} type={} duration={} input={} consumed={} output={} observed={}",
                    evidence.recipeId(),
                    evidence.recipeType(),
                    evidence.recipeProcessingDuration(),
                    evidence.inputItem(),
                    evidence.consumedInputCount(),
                    evidence.outputItem(),
                    evidence.observedOutputCount());
            LOGGER.info("CREATE_PROCESSING_WRONG_THREAD code=WRONG_THREAD worker={}", wrongThreadName);
            LOGGER.info(
                    "CREATE_PROCESSING_GAMETEST PASS minecraft={} forge={} create={} template=minecraft:bastion/mobs/empty placements={} preflightPositions={} inputConsumed=true outputVerified=true rotation={}",
                    evidence.runtime().minecraftVersion(),
                    evidence.runtime().loaderVersion(),
                    evidence.runtime().industrialModVersions().get("create"),
                    evidence.verifiedPlacements().size(),
                    plan.preflightPositions().size(),
                    rotation);
        });
    }

    private static void verifyCancellationProbe(
            ServerLevel level,
            ForgeCreatePlanAdapter adapter,
            WaterWheelMillstonePlan plan) {
        ResolvedPlanPlacement firstPlacement = plan.placements().get(0);
        ResolvedPlanPlacement secondPlacement = plan.placements().get(1);
        BlockPos firstPosition = blockPosition(firstPlacement.position());
        BlockPos secondPosition = blockPosition(secondPlacement.position());
        BlockState firstBefore = level.getBlockState(firstPosition);
        BlockState secondBefore = level.getBlockState(secondPosition);

        AdapterResult<CreatePlanExecutionSession> begin = adapter.begin(plan);
        check(begin instanceof AdapterResult.Success<CreatePlanExecutionSession>,
                "C-03 cancellation probe begin failed: " + begin);
        CreatePlanExecutionSession session =
                ((AdapterResult.Success<CreatePlanExecutionSession>) begin).value();
        AdapterResult<CreatePlanExecutionUpdate> firstTick = session.tick();
        check(firstTick instanceof AdapterResult.Success<CreatePlanExecutionUpdate>,
                "C-03 cancellation probe first tick failed: " + firstTick);
        CreatePlanExecutionUpdate firstValue =
                ((AdapterResult.Success<CreatePlanExecutionUpdate>) firstTick).value();
        check(firstValue instanceof CreatePlanExecutionUpdate.InProgress,
                "C-03 cancellation probe unexpectedly completed");
        check(((CreatePlanExecutionUpdate.InProgress) firstValue).completedPlacements() == 1,
                "C-03 cancellation probe did not perform exactly one placement");
        check(session.worldChangeJournal().entries().size() == 1,
                "C-03 cancellation probe did not record one block change");
        check(session.worldChangeJournal().modifiedPositions().equals(List.of(firstPlacement.position())),
                "C-03 cancellation probe modified-position journal changed");

        AdapterResult<ExecutionCancellationResult> cancelled =
                session.cancel(ResourceId.parse("steve_industrial:acceptance/c03_cancel"));
        check(cancelled instanceof AdapterResult.Success<ExecutionCancellationResult>,
                "C-03 cancellation failed: " + cancelled);
        ExecutionCancellationResult result =
                ((AdapterResult.Success<ExecutionCancellationResult>) cancelled).value();
        check(result.rollbackReport().fullyRestored(),
                "C-03 conservative rollback was not complete: " + result.rollbackReport());
        check(result.rollbackReport().restoredPositions().equals(List.of(firstPlacement.position())),
                "C-03 rollback restored unexpected positions");
        check(result.rollbackReport().warnings().isEmpty(),
                "C-03 pre-input cancellation produced rollback warnings");
        check(level.getBlockState(firstPosition).equals(firstBefore),
                "C-03 cancellation did not restore the first position");
        check(level.getBlockState(secondPosition).equals(secondBefore),
                "C-03 cancellation allowed a later placement");
        checkFailure(session.tick(), AdapterFailureCode.PROCESSING_FAILED, "post-cancel C-03 tick");
        check(level.getBlockState(secondPosition).equals(secondBefore),
                "C-03 post-cancel tick resumed construction");
        LOGGER.info(
                "CREATE_PROCESSING_CANCELLATION PASS changes=1 modifiedPositions=1 restoredPositions=1 warnings=0 stopped=true noResourceCompensation=true");
    }

    private static void verifyFeasibilityRejection(
            ServerLevel level,
            WaterWheelMillstonePlan plan) {
        List<PlacementTarget> targets = plan.placementTargets();
        PlacementTarget occupiedOnly = targets.get(0);
        PlacementTarget protectedOnly = targets.get(1);
        PlacementTarget occupiedAndProtected = targets.get(2);
        Map<BlockPos, BlockState> original = targetStates(level, targets);

        BlockPos occupiedOnlyPosition = blockPosition(occupiedOnly.position());
        BlockPos occupiedAndProtectedPosition = blockPosition(occupiedAndProtected.position());
        check(level.setBlockAndUpdate(occupiedOnlyPosition, Blocks.OBSIDIAN.defaultBlockState()),
                "C-03 feasibility fixture could not place the first obstacle");
        check(level.setBlockAndUpdate(occupiedAndProtectedPosition, Blocks.OBSIDIAN.defaultBlockState()),
                "C-03 feasibility fixture could not place the second obstacle");
        Map<BlockPos, BlockState> beforeRejectedBegin = targetStates(level, targets);

        ForgeCreatePlanAdapter rejectingAdapter = new ForgeCreatePlanAdapter(
                level,
                Set.of(protectedOnly.position(), occupiedAndProtected.position()));
        AdapterResult<CreatePlanExecutionSession> rejected = rejectingAdapter.begin(plan);
        checkFailure(rejected, AdapterFailureCode.PLAN_REJECTED, "C-03 basic placement feasibility");
        String detail = ((AdapterResult.Failure<CreatePlanExecutionSession>) rejected).detail();
        check(detail.contains("conflicts=4 positions=3"),
                "C-03 feasibility did not aggregate all four conflicts: " + detail);
        check(detail.contains(conflict("NOT_REPLACEABLE", occupiedOnly.position())),
                "C-03 feasibility omitted the occupied-only target");
        check(detail.contains(conflict("PROTECTED", protectedOnly.position())),
                "C-03 feasibility omitted the protected-only target");
        check(detail.contains(conflict("NOT_REPLACEABLE", occupiedAndProtected.position()))
                        && detail.contains(conflict("PROTECTED", occupiedAndProtected.position())),
                "C-03 feasibility omitted a combined conflict");
        check(targetStates(level, targets).equals(beforeRejectedBegin),
                "C-03 rejected feasibility begin mutated a target position");
        LOGGER.info(
                "CREATE_PROCESSING_FEASIBILITY PASS conflicts=4 positions=3 kinds=NOT_REPLACEABLE,PROTECTED noMutation=true rotation={}",
                plan.rotation());

        check(level.setBlockAndUpdate(occupiedOnlyPosition, original.get(occupiedOnlyPosition)),
                "C-03 feasibility fixture could not restore the first obstacle");
        check(level.setBlockAndUpdate(
                        occupiedAndProtectedPosition,
                        original.get(occupiedAndProtectedPosition)),
                "C-03 feasibility fixture could not restore the second obstacle");
        check(targetStates(level, targets).equals(original),
                "C-03 feasibility fixture did not restore its setup");
    }

    private static Map<BlockPos, BlockState> targetStates(
            ServerLevel level,
            List<PlacementTarget> targets) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (PlacementTarget target : targets) {
            BlockPos position = blockPosition(target.position());
            states.put(position, level.getBlockState(position));
        }
        return Map.copyOf(states);
    }

    private static String conflict(String kind, BlockPos3i position) {
        return kind + "@" + position.x() + "," + position.y() + "," + position.z();
    }

    private static void verifyJournal(WorldChangeJournal journal) {
        long blockChanges = journal.entries().stream().filter(BlockChange.class::isInstance).count();
        long injectedInputs = journal.entries().stream()
                .filter(InjectedResourceChange.class::isInstance).count();
        long irreversible = journal.entries().stream()
                .filter(IrreversibleProcessingChange.class::isInstance).count();
        check(journal.entries().size() == 19, "C-03 journal entry count changed");
        check(blockChanges == 17, "C-03 journal block-change count changed");
        check(injectedInputs == 1, "C-03 journal input count changed");
        check(irreversible == 1, "C-03 journal irreversible-process count changed");
        check(journal.modifiedPositions().size() == 17, "C-03 journal position count changed");
        LOGGER.info(
                "CREATE_PROCESSING_JOURNAL PASS entries={} blockChanges={} injectedInputs={} irreversibleProcessing={} modifiedPositions={}",
                journal.entries().size(),
                blockChanges,
                injectedInputs,
                irreversible,
                journal.modifiedPositions().size());
    }

    private static void verifyEvidence(WaterWheelMillstonePlan plan, WaterWheelMillstoneEvidence evidence) {
        check(evidence.planOrigin().equals(plan.origin()), "C-03 evidence origin changed");
        check(evidence.verifiedPlacements().equals(plan.placements()), "C-03 placement evidence changed");
        check(evidence.recipeId().equals(plan.process().recipeId()), "C-03 live recipe ID changed");
        check(evidence.recipeType().equals(plan.process().recipeType()), "C-03 live recipe type changed");
        check(evidence.inputItem().equals(plan.process().inputItem()), "C-03 input evidence changed");
        check(evidence.consumedInputCount() == plan.process().inputCount(), "C-03 input was not consumed");
        check(evidence.outputItem().equals(plan.process().expectedOutputItem()), "C-03 output item changed");
        check(evidence.observedOutputCount() >= plan.process().minimumOutputCount(), "C-03 output was not observed");
    }

    private static void verifyWaterContained(ServerLevel level, WaterWheelMillstonePlan plan) {
        BlockPos3i source = plan.placement(WaterWheelMillstoneRole.WATER_SOURCE).position();
        Set<BlockPos3i> allowed = Set.of(
                source,
                source.translate(0, -1, 0),
                source.translate(0, -2, 0));
        Set<BlockPos3i> observed = new java.util.LinkedHashSet<>();
        int scanned = 0;
        for (int x = -2; x <= 2; x++) {
            for (int y = -3; y <= 1; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos3i position = source.translate(x, y, z);
                    scanned++;
                    if (level.getFluidState(blockPosition(position)).is(FluidTags.WATER)) {
                        observed.add(position);
                    }
                }
            }
        }
        check(observed.contains(source), "C-03 contained source water is absent");
        check(allowed.containsAll(observed), "C-03 water escaped the typed containment: " + observed);
        LOGGER.info(
                "CREATE_PROCESSING_WATER_CONTAINMENT PASS waterPositions={} scanPositions={} outside=0",
                observed.size(), scanned);
    }

    private static void verifyRotatedBlockStates(
            ServerLevel level,
            WaterWheelMillstonePlan plan) {
        ResolvedPlanPlacement waterWheel = plan.placement(WaterWheelMillstoneRole.WATER_WHEEL);
        ResolvedPlanPlacement gearbox = plan.placement(WaterWheelMillstoneRole.GEARBOX);
        ResolvedPlanPlacement shaft = plan.placement(WaterWheelMillstoneRole.VERTICAL_SHAFT);
        BlockState waterWheelState = level.getBlockState(blockPosition(waterWheel.position()));
        BlockState gearboxState = level.getBlockState(blockPosition(gearbox.position()));
        BlockState shaftState = level.getBlockState(blockPosition(shaft.position()));
        Direction.Axis waterWheelAxis = waterWheelState.getValue(BlockStateProperties.FACING).getAxis();
        Direction.Axis gearboxAxis = gearboxState.getValue(BlockStateProperties.AXIS);
        Direction.Axis shaftAxis = shaftState.getValue(BlockStateProperties.AXIS);
        check(waterWheelAxis == axis(waterWheel.axis()), "C-03 transformed water-wheel axis mismatch");
        check(gearboxAxis == axis(gearbox.axis()), "C-03 transformed gearbox axis mismatch");
        check(shaftAxis == axis(shaft.axis()), "C-03 transformed shaft axis mismatch");
        LOGGER.info(
                "CREATE_PROCESSING_PLAN_TRANSFORM PASS rotation={} waterWheelAxis={} gearboxAxis={} shaftAxis={}",
                plan.rotation(),
                waterWheelAxis.name(),
                gearboxAxis.name(),
                shaftAxis.name());
    }

    private static Direction.Axis axis(PlanBlockAxis axis) {
        return switch (axis) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException("NONE has no physical axis");
        };
    }

    private static String sequence(WaterWheelMillstonePlan plan) {
        return plan.placements().stream()
                .map(placement -> placement.order() + ":" + placement.role() + "=" + placement.blockId())
                .collect(Collectors.joining(";"));
    }

    private static ChunkPos findUnloadedChunk(ServerLevel level, ChunkPos origin) {
        for (int distance = 128; distance <= 2_048; distance += 128) {
            int chunkX = origin.x + distance;
            int chunkZ = origin.z + distance;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new ChunkPos(chunkX, chunkZ);
            }
        }
        throw new IllegalStateException("Could not find an unloaded chunk for the C-03 safety probe");
    }

    private static <T> void checkFailure(
            AdapterResult<T> result,
            AdapterFailureCode expectedCode,
            String description) {
        check(result instanceof AdapterResult.Failure<T>, description + " did not return typed failure: " + result);
        AdapterResult.Failure<T> failure = (AdapterResult.Failure<T>) result;
        check(failure.code() == expectedCode, description + " returned " + failure.code());
    }

    private static BlockPos3i position(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos blockPosition(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
