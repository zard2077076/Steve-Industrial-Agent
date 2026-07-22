package dev.stevecreate.agent.forge1201.acceptance;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.BeltPressEvidence;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionSession;
import dev.stevecreate.agent.adapter.api.BeltPressExecutionUpdate;
import dev.stevecreate.agent.adapter.api.ExecutionCancellationResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.placement.PlacementTarget;
import dev.stevecreate.agent.core.plan.BeltPressBuildStep;
import dev.stevecreate.agent.core.plan.BeltPressPlacement;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.BeltPressRole;
import dev.stevecreate.agent.core.plan.PlanBlockAxis;
import dev.stevecreate.agent.core.plan.PlanBlockFacing;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateBeltPressPlanAdapter;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** C-04 isolated Forge GameTest, separate from the C-03 millstone acceptance. */
@PrefixGameTestTemplate(false)
public final class CreateBeltPressGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CreateBeltPressGameTests() {
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void beltPressProducesIronSheet(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.ZERO);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void beltPressProducesIronSheetClockwise90(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.CLOCKWISE_90);
    }

    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 1_800)
    public static void beltPressProducesIronSheetClockwise270(GameTestHelper helper) {
        runPlan(helper, QuarterTurn.CLOCKWISE_270);
    }

    private static void runPlan(GameTestHelper helper, QuarterTurn rotation) {
        ServerLevel level = helper.getLevel();
        check(level.getServer().isSameThread(), "C-04 GameTest setup is not on the authoritative server thread");

        BlockPos absoluteOrigin = helper.absolutePos(fixtureAnchor(rotation));
        BeltPressPlan plan = BeltPressPlan.at(position(absoluteOrigin), rotation);
        ForgeCreateBeltPressPlanAdapter adapter = new ForgeCreateBeltPressPlanAdapter(level);

        AtomicReference<AdapterResult<BeltPressExecutionSession>> wrongThread = new AtomicReference<>();
        String wrongThreadName = "steve-industrial-c04-wrong-thread-probe-" + rotation.name();
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
        check(!level.hasChunk(remoteChunk.x, remoteChunk.z), "C-04 unloaded-chunk precondition failed");
        AdapterResult<BeltPressExecutionSession> remote = adapter.begin(
                BeltPressPlan.at(position(remoteOrigin), rotation));
        checkFailure(remote, AdapterFailureCode.CHUNK_NOT_LOADED, "unloaded belt/press preflight");
        String remoteDetail = ((AdapterResult.Failure<BeltPressExecutionSession>) remote).detail();
        check(remoteDetail.contains("conflicts=8 positions=8"),
                "C-04 unloaded feasibility did not report every target: " + remoteDetail);
        check(!level.hasChunk(remoteChunk.x, remoteChunk.z), "C-04 belt/press preflight loaded a remote chunk");
        LOGGER.info(
                "CREATE_BELT_PRESS_UNLOADED_CHUNK chunk={},{} before=false code=CHUNK_NOT_LOADED conflicts=8 positions=8 after=false",
                remoteChunk.x,
                remoteChunk.z);

        verifyFeasibilityRejection(level, plan);
        verifyCancellationProbe(level, adapter, plan);

        AdapterResult<BeltPressExecutionSession> begin = adapter.begin(plan);
        check(begin instanceof AdapterResult.Success<BeltPressExecutionSession>, "C-04 begin failed: " + begin);
        BeltPressExecutionSession session =
                ((AdapterResult.Success<BeltPressExecutionSession>) begin).value();
        LOGGER.info(
                "CREATE_BELT_PRESS_PLAN origin={},{},{} rotation={} buildSteps={} finalPlacements={} preflightPositions={} sequence=\"{}\"",
                plan.origin().x(),
                plan.origin().y(),
                plan.origin().z(),
                rotation,
                plan.buildSteps().size(),
                plan.finalPlacements().size(),
                plan.preflightPositions().size(),
                sequence(plan));

        helper.succeedWhen(() -> {
            AdapterResult<BeltPressExecutionSession> wrongThreadResult = wrongThread.get();
            if (wrongThreadResult == null) {
                throw new GameTestAssertException("Waiting for non-blocking C-04 wrong-thread probe");
            }
            checkFailure(wrongThreadResult, AdapterFailureCode.WRONG_THREAD, "C-04 wrong-thread begin");

            AdapterResult<BeltPressExecutionUpdate> update = session.tick();
            if (update instanceof AdapterResult.Failure<BeltPressExecutionUpdate> failure) {
                helper.fail("C-04 executor failed: " + failure.code() + " - " + failure.detail());
                return;
            }
            BeltPressExecutionUpdate value =
                    ((AdapterResult.Success<BeltPressExecutionUpdate>) update).value();
            if (value instanceof BeltPressExecutionUpdate.InProgress progress) {
                throw new GameTestAssertException(
                        "C-04 pending phase=" + progress.phase()
                                + " steps=" + progress.completedBuildSteps() + "/" + progress.totalBuildSteps());
            }

            BeltPressEvidence evidence = ((BeltPressExecutionUpdate.Completed) value).evidence();
            verifyEvidence(plan, evidence);
            verifyRotatedBlockStates(level, plan);
            verifyJournal(session.worldChangeJournal());
            LOGGER.info(
                    "CREATE_BELT_PRESS_PLACEMENTS PASS count={} order=\"{}\"",
                    evidence.verifiedPlacements().size(),
                    evidence.verifiedPlacements().stream()
                            .map(placement -> placement.order() + ":" + placement.role() + "@"
                                    + placement.position().x() + "," + placement.position().y() + ","
                                    + placement.position().z() + "=" + placement.blockId())
                            .collect(Collectors.joining(";")));
            LOGGER.info(
                    "CREATE_BELT_PRESS_POWER PASS beltDriveRpm={} beltStartRpm={} beltPressingRpm={} beltEndRpm={} pressDriveRpm={} mechanicalPressRpm={}",
                    evidence.observedSpeedRpm().get(BeltPressRole.BELT_DRIVE),
                    evidence.observedSpeedRpm().get(BeltPressRole.BELT_START),
                    evidence.observedSpeedRpm().get(BeltPressRole.BELT_PRESSING),
                    evidence.observedSpeedRpm().get(BeltPressRole.BELT_END),
                    evidence.observedSpeedRpm().get(BeltPressRole.PRESS_DRIVE),
                    evidence.observedSpeedRpm().get(BeltPressRole.MECHANICAL_PRESS));
            LOGGER.info(
                    "CREATE_BELT_PRESS_RECIPE PASS id={} type={} input={} consumed={} output={} observed={} recipeDuration={} pressCycleTicks={} inputObservedOnBelt={} pressCycleObserved={} outputObservedInChest={}",
                    evidence.recipeId(),
                    evidence.recipeType(),
                    evidence.inputItem(),
                    evidence.consumedInputCount(),
                    evidence.outputItem(),
                    evidence.observedOutputCount(),
                    evidence.recipeProcessingDuration(),
                    evidence.pressCycleTicks(),
                    evidence.inputObservedOnBelt(),
                    evidence.pressCycleObserved(),
                    evidence.outputObservedInChest());
            LOGGER.info("CREATE_BELT_PRESS_WRONG_THREAD code=WRONG_THREAD worker={}", wrongThreadName);
            LOGGER.info(
                    "CREATE_BELT_PRESS_GAMETEST PASS minecraft={} forge={} create={} template=minecraft:bastion/mobs/empty buildSteps={} finalPlacements={} preflightPositions={} inputObservedOnBelt=true pressCycleObserved=true outputObservedInChest=true rotation={}",
                    evidence.runtime().minecraftVersion(),
                    evidence.runtime().loaderVersion(),
                    evidence.runtime().industrialModVersions().get("create"),
                    plan.buildSteps().size(),
                    evidence.verifiedPlacements().size(),
                    plan.preflightPositions().size(),
                    rotation);
        });
    }

    private static void verifyCancellationProbe(
            ServerLevel level,
            ForgeCreateBeltPressPlanAdapter adapter,
            BeltPressPlan plan) {
        BeltPressBuildStep.PlaceBlock firstStep =
                (BeltPressBuildStep.PlaceBlock) plan.buildSteps().get(0);
        BeltPressBuildStep.PlaceBlock secondStep =
                (BeltPressBuildStep.PlaceBlock) plan.buildSteps().get(1);
        BlockPos firstPosition = blockPosition(firstStep.position());
        BlockPos secondPosition = blockPosition(secondStep.position());
        BlockState firstBefore = level.getBlockState(firstPosition);
        BlockState secondBefore = level.getBlockState(secondPosition);

        AdapterResult<BeltPressExecutionSession> begin = adapter.begin(plan);
        check(begin instanceof AdapterResult.Success<BeltPressExecutionSession>,
                "C-04 cancellation probe begin failed: " + begin);
        BeltPressExecutionSession session =
                ((AdapterResult.Success<BeltPressExecutionSession>) begin).value();
        AdapterResult<BeltPressExecutionUpdate> firstTick = session.tick();
        check(firstTick instanceof AdapterResult.Success<BeltPressExecutionUpdate>,
                "C-04 cancellation probe first tick failed: " + firstTick);
        BeltPressExecutionUpdate firstValue =
                ((AdapterResult.Success<BeltPressExecutionUpdate>) firstTick).value();
        check(firstValue instanceof BeltPressExecutionUpdate.InProgress,
                "C-04 cancellation probe unexpectedly completed");
        check(((BeltPressExecutionUpdate.InProgress) firstValue).completedBuildSteps() == 1,
                "C-04 cancellation probe did not perform exactly one build step");
        check(session.worldChangeJournal().entries().size() == 1,
                "C-04 cancellation probe did not record one block change");
        check(session.worldChangeJournal().modifiedPositions().equals(List.of(firstStep.position())),
                "C-04 cancellation probe modified-position journal changed");

        AdapterResult<ExecutionCancellationResult> cancelled =
                session.cancel(ResourceId.parse("steve_industrial:acceptance/c04_cancel"));
        check(cancelled instanceof AdapterResult.Success<ExecutionCancellationResult>,
                "C-04 cancellation failed: " + cancelled);
        ExecutionCancellationResult result =
                ((AdapterResult.Success<ExecutionCancellationResult>) cancelled).value();
        check(result.rollbackReport().fullyRestored(),
                "C-04 conservative rollback was not complete: " + result.rollbackReport());
        check(result.rollbackReport().restoredPositions().equals(List.of(firstStep.position())),
                "C-04 rollback restored unexpected positions");
        check(result.rollbackReport().warnings().isEmpty(),
                "C-04 pre-input cancellation produced rollback warnings");
        check(level.getBlockState(firstPosition).equals(firstBefore),
                "C-04 cancellation did not restore the first position");
        check(level.getBlockState(secondPosition).equals(secondBefore),
                "C-04 cancellation allowed a later build step");
        checkFailure(session.tick(), AdapterFailureCode.PROCESSING_FAILED, "post-cancel C-04 tick");
        check(level.getBlockState(secondPosition).equals(secondBefore),
                "C-04 post-cancel tick resumed construction");
        LOGGER.info(
                "CREATE_BELT_PRESS_CANCELLATION PASS changes=1 modifiedPositions=1 restoredPositions=1 warnings=0 stopped=true noResourceCompensation=true");
    }

    private static void verifyFeasibilityRejection(
            ServerLevel level,
            BeltPressPlan plan) {
        List<PlacementTarget> targets = plan.placementTargets();
        PlacementTarget occupiedOnly = targets.get(0);
        PlacementTarget protectedOnly = targets.get(1);
        PlacementTarget occupiedAndProtected = targets.get(2);
        Map<BlockPos, BlockState> original = targetStates(level, targets);

        BlockPos occupiedOnlyPosition = blockPosition(occupiedOnly.position());
        BlockPos occupiedAndProtectedPosition = blockPosition(occupiedAndProtected.position());
        check(level.setBlockAndUpdate(occupiedOnlyPosition, Blocks.OBSIDIAN.defaultBlockState()),
                "C-04 feasibility fixture could not place the first obstacle");
        check(level.setBlockAndUpdate(occupiedAndProtectedPosition, Blocks.OBSIDIAN.defaultBlockState()),
                "C-04 feasibility fixture could not place the second obstacle");
        Map<BlockPos, BlockState> beforeRejectedBegin = targetStates(level, targets);

        ForgeCreateBeltPressPlanAdapter rejectingAdapter = new ForgeCreateBeltPressPlanAdapter(
                level,
                Set.of(protectedOnly.position(), occupiedAndProtected.position()));
        AdapterResult<BeltPressExecutionSession> rejected = rejectingAdapter.begin(plan);
        checkFailure(rejected, AdapterFailureCode.PLAN_REJECTED, "C-04 basic placement feasibility");
        String detail = ((AdapterResult.Failure<BeltPressExecutionSession>) rejected).detail();
        check(detail.contains("conflicts=4 positions=3"),
                "C-04 feasibility did not aggregate all four conflicts: " + detail);
        check(detail.contains(conflict("NOT_REPLACEABLE", occupiedOnly.position())),
                "C-04 feasibility omitted the occupied-only target");
        check(detail.contains(conflict("PROTECTED", protectedOnly.position())),
                "C-04 feasibility omitted the protected-only target");
        check(detail.contains(conflict("NOT_REPLACEABLE", occupiedAndProtected.position()))
                        && detail.contains(conflict("PROTECTED", occupiedAndProtected.position())),
                "C-04 feasibility omitted a combined conflict");
        check(targetStates(level, targets).equals(beforeRejectedBegin),
                "C-04 rejected feasibility begin mutated a target position");
        LOGGER.info(
                "CREATE_BELT_PRESS_FEASIBILITY PASS conflicts=4 positions=3 kinds=NOT_REPLACEABLE,PROTECTED noMutation=true rotation={}",
                plan.rotation());

        check(level.setBlockAndUpdate(occupiedOnlyPosition, original.get(occupiedOnlyPosition)),
                "C-04 feasibility fixture could not restore the first obstacle");
        check(level.setBlockAndUpdate(
                        occupiedAndProtectedPosition,
                        original.get(occupiedAndProtectedPosition)),
                "C-04 feasibility fixture could not restore the second obstacle");
        check(targetStates(level, targets).equals(original),
                "C-04 feasibility fixture did not restore its setup");
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
        check(journal.entries().size() == 12, "C-04 journal entry count changed");
        check(blockChanges == 10, "C-04 journal block-change count changed");
        check(injectedInputs == 1, "C-04 journal input count changed");
        check(irreversible == 1, "C-04 journal irreversible-process count changed");
        check(journal.modifiedPositions().size() == 8, "C-04 journal position count changed");
        LOGGER.info(
                "CREATE_BELT_PRESS_JOURNAL PASS entries={} blockChanges={} injectedInputs={} irreversibleProcessing={} modifiedPositions={}",
                journal.entries().size(),
                blockChanges,
                injectedInputs,
                irreversible,
                journal.modifiedPositions().size());
    }

    private static void verifyEvidence(BeltPressPlan plan, BeltPressEvidence evidence) {
        check(evidence.planOrigin().equals(plan.origin()), "C-04 evidence origin changed");
        check(evidence.verifiedPlacements().equals(plan.finalPlacements()), "C-04 final placement evidence changed");
        check(evidence.recipeId().equals(plan.process().recipeId()), "C-04 live recipe ID changed");
        check(evidence.recipeType().equals(plan.process().recipeType()), "C-04 live recipe type changed");
        check(evidence.inputItem().equals(plan.process().inputItem()), "C-04 input evidence changed");
        check(evidence.consumedInputCount() == plan.process().inputCount(), "C-04 iron ingot was not consumed");
        check(evidence.outputItem().equals(plan.process().expectedOutputItem()), "C-04 output item changed");
        check(evidence.observedOutputCount() >= plan.process().minimumOutputCount(), "C-04 iron sheet was not observed");
        check(evidence.inputObservedOnBelt(), "C-04 input never appeared in the real belt inventory");
        check(evidence.pressCycleObserved(), "C-04 mechanical press cycle was not observed");
        check(evidence.outputObservedInChest(), "C-04 output never reached the real chest inventory");
    }

    private static void verifyRotatedBlockStates(
            ServerLevel level,
            BeltPressPlan plan) {
        BeltPressPlacement beltStart = plan.placement(BeltPressRole.BELT_START);
        BeltPressPlacement beltDrive = plan.placement(BeltPressRole.BELT_DRIVE);
        BeltPressPlacement pressDrive = plan.placement(BeltPressRole.PRESS_DRIVE);
        BeltPressPlacement mechanicalPress = plan.placement(BeltPressRole.MECHANICAL_PRESS);
        BeltPressPlacement outputFunnel = plan.placement(BeltPressRole.OUTPUT_FUNNEL);

        BlockState beltState = level.getBlockState(blockPosition(beltStart.position()));
        check(beltState.getBlock() instanceof BeltBlock,
                "C-04 transformed belt start is not a physical Create belt");
        BeltBlock beltBlock = (BeltBlock) beltState.getBlock();
        Direction.Axis beltAxis = beltBlock.getRotationAxis(beltState);
        Direction beltFacing = beltState.getValue(BeltBlock.HORIZONTAL_FACING);
        Direction beltDriveFacing = stateFacing(level.getBlockState(blockPosition(beltDrive.position())));
        Direction pressDriveFacing = stateFacing(level.getBlockState(blockPosition(pressDrive.position())));
        Direction mechanicalPressFacing = stateFacing(
                level.getBlockState(blockPosition(mechanicalPress.position())));
        Direction funnelFacing = stateFacing(level.getBlockState(blockPosition(outputFunnel.position())));

        check(beltAxis == axis(beltStart.rotationAxis()), "C-04 transformed belt axis mismatch");
        check(beltFacing == facing(beltStart.facing()), "C-04 transformed belt facing mismatch");
        check(beltDriveFacing == facing(beltDrive.facing()), "C-04 transformed belt-drive facing mismatch");
        check(pressDriveFacing == facing(pressDrive.facing()), "C-04 transformed press-drive facing mismatch");
        check(mechanicalPressFacing == facing(mechanicalPress.facing()),
                "C-04 transformed mechanical-press facing mismatch");
        check(funnelFacing == facing(outputFunnel.facing()), "C-04 transformed funnel facing mismatch");
        LOGGER.info(
                "CREATE_BELT_PRESS_PLAN_TRANSFORM PASS rotation={} beltAxis={} beltFacing={} beltDriveFacing={} pressDriveFacing={} mechanicalPressFacing={} funnelFacing={}",
                plan.rotation(),
                beltAxis.name(),
                beltFacing.name(),
                beltDriveFacing.name(),
                pressDriveFacing.name(),
                mechanicalPressFacing.name(),
                funnelFacing.name());
    }

    private static Direction.Axis axis(PlanBlockAxis axis) {
        return switch (axis) {
            case X -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.Z;
            case NONE -> throw new IllegalArgumentException("NONE has no physical axis");
        };
    }

    private static Direction facing(PlanBlockFacing facing) {
        return switch (facing) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case NONE -> throw new IllegalArgumentException("NONE has no physical facing");
        };
    }

    private static Direction stateFacing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : null;
    }

    private static String sequence(BeltPressPlan plan) {
        return plan.buildSteps().stream()
                .map(CreateBeltPressGameTests::stepDescription)
                .collect(Collectors.joining(";"));
    }

    private static BlockPos blockPosition(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static BlockPos fixtureAnchor(QuarterTurn rotation) {
        return switch (rotation) {
            case ZERO -> new BlockPos(0, 2, 1);
            case CLOCKWISE_90 -> new BlockPos(1, 2, 0);
            case CLOCKWISE_270 -> new BlockPos(0, 2, 3);
            case CLOCKWISE_180 -> throw new IllegalArgumentException(
                    "C-04 GameTest does not register the 180-degree physical fixture");
        };
    }

    private static String stepDescription(BeltPressBuildStep step) {
        if (step instanceof BeltPressBuildStep.PlaceBlock place) {
            return place.order() + ":" + place.role() + "=" + place.materialId() + "@"
                    + place.position().x() + "," + place.position().y() + "," + place.position().z();
        }
        BeltPressBuildStep.ConnectBelt connection = (BeltPressBuildStep.ConnectBelt) step;
        return connection.order() + ":CONNECT_BELT=" + connection.materialId() + "@"
                + connection.startPosition().x() + "," + connection.startPosition().y() + ","
                + connection.startPosition().z() + "->"
                + connection.endPosition().x() + "," + connection.endPosition().y() + ","
                + connection.endPosition().z() + " segments=" + connection.expectedSegments();
    }

    private static ChunkPos findUnloadedChunk(ServerLevel level, ChunkPos origin) {
        for (int distance = 128; distance <= 2_048; distance += 128) {
            int chunkX = origin.x + distance;
            int chunkZ = origin.z + distance;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new ChunkPos(chunkX, chunkZ);
            }
        }
        throw new IllegalStateException("Could not find an unloaded chunk for the C-04 safety probe");
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

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
