package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticCaptureRequest;
import dev.stevecreate.agent.adapter.api.KineticOperatingState;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.KineticSnapshotReplayCodec;
import dev.stevecreate.agent.core.diagnostic.FaultCode;
import dev.stevecreate.agent.core.diagnostic.KineticNetworkAnalyzer;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateKineticAdapter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Tick-driven isolated dedicated-server fixture for the C-02 kinetic adapter. */
public final class CreateKineticsAcceptanceFixture {
    private static final ResourceLocation SHAFT = key("shaft");
    private static final ResourceLocation CREATIVE_MOTOR = key("creative_motor");
    private static final ResourceLocation ENCASED_FAN = key("encased_fan");
    private static final int SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 200;

    private static ActiveFixture active;

    private CreateKineticsAcceptanceFixture() {
    }

    public static void start(MinecraftServer server, Logger logger) {
        try {
            check(active == null, "C-02 fixture was started twice");
            check(!FMLLoader.isProduction(), "C-02 acceptance fixture is disabled in production");
            check(server.isSameThread(), "C-02 fixture setup must run on the authoritative server thread");
            ServerLevel level = server.overworld();
            BlockPos origin = fixtureOrigin(level);
            BlockPos stopped = origin;
            BlockPos poweredPositive = origin.offset(0, 0, 3);
            BlockPos poweredNegative = origin.offset(0, 0, 6);
            BlockPos overloaded = origin.offset(0, 0, 9);

            place(level, stopped, SHAFT, null);
            place(level, poweredPositive, CREATIVE_MOTOR, Direction.EAST);
            place(level, poweredNegative, CREATIVE_MOTOR, Direction.WEST);
            place(level, overloaded, CREATIVE_MOTOR, Direction.EAST);
            place(level, overloaded.relative(Direction.EAST), ENCASED_FAN, Direction.EAST);

            ForgeCreateKineticAdapter adapter = new ForgeCreateKineticAdapter(level);
            AtomicReference<AdapterResult<KineticSnapshot>> wrongThreadResult = new AtomicReference<>();
            Thread worker = new Thread(
                    () -> wrongThreadResult.set(adapter.captureKinetics(requestAt(poweredPositive))),
                    "steve-industrial-c02-wrong-thread-probe");
            worker.setDaemon(true);
            worker.start();

            active = new ActiveFixture(
                    server,
                    logger,
                    adapter,
                    server.getTickCount(),
                    stopped,
                    poweredPositive,
                    poweredNegative,
                    overloaded,
                    wrongThreadResult);
        } catch (RuntimeException exception) {
            logger.error("CREATE_KINETICS_ACCEPTANCE FAIL", exception);
            server.halt(false);
            throw exception;
        }
    }

    public static void tick(MinecraftServer server) {
        ActiveFixture fixture = active;
        if (fixture == null || fixture.server() != server) {
            return;
        }
        try {
            int elapsed = server.getTickCount() - fixture.startTick();
            if (elapsed < SETTLE_TICKS) {
                return;
            }
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException("Create kinetic fixture did not settle within " + TIMEOUT_TICKS + " ticks");
            }

            List<KineticSnapshot> snapshots = captureSettledSnapshots(fixture);
            if (snapshots == null || fixture.wrongThreadResult().get() == null) {
                return;
            }
            verifyStates(fixture.logger(), snapshots);
            verifyWrongThread(fixture);
            verifyUnloadedChunk(fixture);
            verifyReplay(fixture.logger(), snapshots);

            KineticSnapshot overloaded = snapshots.get(3);
            List<FaultCode> faultCodes = new KineticNetworkAnalyzer()
                    .analyze(List.of(overloaded.toTelemetry()))
                    .findings().stream()
                    .map(finding -> finding.code())
                    .toList();
            check(faultCodes.equals(List.of(FaultCode.STRESS_OVERLOAD)),
                    "Replay analyzer did not preserve stress overload: " + faultCodes);

            fixture.logger().info(
                    "CREATE_KINETICS_ACCEPTANCE PASS minecraft={} forge={} create={} states=STOPPED,POWERED_POSITIVE,POWERED_NEGATIVE,OVERSTRESSED wrongThreadRejected=true unloadedChunkPreserved=true replayDeterministic=true",
                    overloaded.runtime().minecraftVersion(),
                    overloaded.runtime().loaderVersion(),
                    overloaded.runtime().industrialModVersions().get("create"));
            active = null;
            server.halt(false);
        } catch (RuntimeException exception) {
            fixture.logger().error("CREATE_KINETICS_ACCEPTANCE FAIL", exception);
            active = null;
            server.halt(false);
            throw exception;
        }
    }

    private static List<KineticSnapshot> captureSettledSnapshots(ActiveFixture fixture) {
        List<BlockPos> positions = List.of(
                fixture.stopped(),
                fixture.poweredPositive(),
                fixture.poweredNegative(),
                fixture.overloaded());
        java.util.ArrayList<KineticSnapshot> snapshots = new java.util.ArrayList<>();
        for (BlockPos position : positions) {
            AdapterResult<KineticSnapshot> result = fixture.adapter().captureKinetics(requestAt(position));
            if (result instanceof AdapterResult.Success<KineticSnapshot> success) {
                snapshots.add(success.value());
            } else if (result instanceof AdapterResult.Failure<KineticSnapshot> failure
                    && failure.code() == AdapterFailureCode.LIFECYCLE_NOT_READY) {
                return null;
            } else {
                throw new IllegalStateException("C-02 live capture failed at " + position + ": " + result);
            }
        }
        return List.copyOf(snapshots);
    }

    private static void verifyStates(Logger logger, List<KineticSnapshot> snapshots) {
        KineticSnapshot stopped = snapshots.get(0);
        check(stopped.operatingState() == KineticOperatingState.STOPPED,
                "Expected stopped shaft: " + stopped);
        check(stopped.speedRpm() == 0 && stopped.rotationDirection() == KineticRotationDirection.STATIONARY,
                "Stopped shaft exposed rotation: " + stopped);
        check(stopped.networkId().isEmpty() && stopped.stressCapacity() == 0 && stopped.stressLoad() == 0,
                "Stopped shaft fabricated network totals: " + stopped);

        KineticSnapshot positive = snapshots.get(1);
        check(positive.operatingState() == KineticOperatingState.POWERED,
                "Expected positive powered motor: " + positive);
        check(positive.speedRpm() == 16 && positive.rotationDirection() == KineticRotationDirection.POSITIVE,
                "Positive motor direction/speed mismatch: " + positive);
        check(positive.stressCapacity() == 16 && positive.stressLoad() == 0,
                "Positive motor stress totals mismatch: " + positive);

        KineticSnapshot negative = snapshots.get(2);
        check(negative.operatingState() == KineticOperatingState.POWERED,
                "Expected negative powered motor: " + negative);
        check(negative.speedRpm() == 16 && negative.rotationDirection() == KineticRotationDirection.NEGATIVE,
                "Negative motor direction/speed mismatch: " + negative);
        check(negative.stressCapacity() == 16 && negative.stressLoad() == 0,
                "Negative motor stress totals mismatch: " + negative);

        KineticSnapshot overloaded = snapshots.get(3);
        check(overloaded.operatingState() == KineticOperatingState.OVERSTRESSED,
                "Expected overstressed motor/fan network: " + overloaded);
        check(overloaded.speedRpm() == 0
                        && overloaded.rotationDirection() == KineticRotationDirection.STATIONARY,
                "Overstressed network reported actual rotation: " + overloaded);
        check(overloaded.stressEnabled()
                        && overloaded.stressCapacity() == 16
                        && overloaded.stressLoad() == 32
                        && overloaded.overstressed(),
                "Overstressed network totals mismatch: " + overloaded);

        logState(logger, "STOPPED", stopped);
        logState(logger, "POWERED_POSITIVE", positive);
        logState(logger, "POWERED_NEGATIVE", negative);
        logState(logger, "OVERSTRESSED", overloaded);
    }

    private static void logState(Logger logger, String state, KineticSnapshot snapshot) {
        logger.info(
                "CREATE_KINETICS_STATE state={} block={} position={},{},{} network={} size={} speedRpm={} direction={} capacity={} load={} overstressed={}",
                state,
                snapshot.blockId(),
                snapshot.position().x(),
                snapshot.position().y(),
                snapshot.position().z(),
                snapshot.networkId().isPresent() ? Long.toUnsignedString(snapshot.networkId().getAsLong()) : "none",
                snapshot.networkSize(),
                snapshot.speedRpm(),
                snapshot.rotationDirection(),
                snapshot.stressCapacity(),
                snapshot.stressLoad(),
                snapshot.overstressed());
    }

    private static void verifyWrongThread(ActiveFixture fixture) {
        AdapterResult<KineticSnapshot> result = fixture.wrongThreadResult().get();
        check(result instanceof AdapterResult.Failure<KineticSnapshot>,
                "Off-thread capture did not return a typed failure: " + result);
        AdapterResult.Failure<KineticSnapshot> failure = (AdapterResult.Failure<KineticSnapshot>) result;
        check(failure.code() == AdapterFailureCode.WRONG_THREAD,
                "Off-thread capture returned the wrong code: " + failure);
        fixture.logger().info("CREATE_KINETICS_WRONG_THREAD code={} worker=steve-industrial-c02-wrong-thread-probe",
                failure.code());
    }

    private static void verifyUnloadedChunk(ActiveFixture fixture) {
        ServerLevel level = fixture.server().overworld();
        ChunkPos unloaded = findUnloadedChunk(level, new ChunkPos(fixture.stopped()));
        BlockPos target = new BlockPos(
                unloaded.getMinBlockX() + 8,
                fixture.stopped().getY(),
                unloaded.getMinBlockZ() + 8);
        check(!level.hasChunk(unloaded.x, unloaded.z), "Unloaded chunk precondition failed");
        AdapterResult<KineticSnapshot> result = fixture.adapter().captureKinetics(requestAt(target));
        check(result instanceof AdapterResult.Failure<KineticSnapshot>,
                "Unloaded capture did not return a typed failure: " + result);
        AdapterResult.Failure<KineticSnapshot> failure = (AdapterResult.Failure<KineticSnapshot>) result;
        check(failure.code() == AdapterFailureCode.CHUNK_NOT_LOADED,
                "Unloaded capture returned the wrong code: " + failure);
        check(!level.hasChunk(unloaded.x, unloaded.z), "Kinetic capture loaded a remote chunk");
        fixture.logger().info(
                "CREATE_KINETICS_UNLOADED_CHUNK chunk={},{} before=false code={} after=false",
                unloaded.x,
                unloaded.z,
                failure.code());
    }

    private static void verifyReplay(Logger logger, List<KineticSnapshot> snapshots) {
        for (KineticSnapshot snapshot : snapshots) {
            String first = KineticSnapshotReplayCodec.encode(snapshot);
            String second = KineticSnapshotReplayCodec.encode(snapshot);
            check(first.equals(second), "Replay encoding changed for an immutable snapshot");
            KineticSnapshot replayed = KineticSnapshotReplayCodec.decode(first);
            check(replayed.equals(snapshot), "Replay round trip changed the kinetic snapshot");
            check(KineticSnapshotReplayCodec.encode(replayed).equals(first),
                    "Decoded replay did not retain canonical encoding");
        }
        logger.info("CREATE_KINETICS_REPLAY PASS snapshots={} canonicalRoundTrips={}",
                snapshots.size(), snapshots.size());
    }

    private static BlockPos fixtureOrigin(ServerLevel level) {
        ChunkPos spawnChunk = new ChunkPos(level.getSharedSpawnPos());
        check(level.hasChunk(spawnChunk.x, spawnChunk.z), "Shared spawn chunk is not loaded");
        int x = spawnChunk.getMinBlockX() + 4;
        int z = spawnChunk.getMinBlockZ() + 3;
        int y = level.getMinBuildHeight() + 1;
        for (int offset = 0; offset <= 9; offset += 3) {
            y = Math.max(y, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + offset) + 2);
        }
        check(y < level.getMaxBuildHeight(), "C-02 fixture is above the build limit");
        return new BlockPos(x, y, z);
    }

    private static void place(
            ServerLevel level,
            BlockPos position,
            ResourceLocation blockKey,
            Direction facing) {
        Block block = ForgeRegistries.BLOCKS.getValue(blockKey);
        check(block != null && ForgeRegistries.BLOCKS.getKey(block) != null,
                "Missing Create fixture block " + blockKey);
        BlockState state = block.defaultBlockState();
        if (facing != null) {
            check(state.hasProperty(BlockStateProperties.FACING),
                    blockKey + " has no facing property");
            state = state.setValue(BlockStateProperties.FACING, facing);
        }
        check(level.setBlockAndUpdate(position, state), "Failed to place " + blockKey + " at " + position);
    }

    private static KineticCaptureRequest requestAt(BlockPos position) {
        return new KineticCaptureRequest(new BlockPos3i(position.getX(), position.getY(), position.getZ()));
    }

    private static ChunkPos findUnloadedChunk(ServerLevel level, ChunkPos origin) {
        for (int distance = 128; distance <= 2048; distance += 128) {
            int chunkX = origin.x + distance;
            int chunkZ = origin.z + distance;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new ChunkPos(chunkX, chunkZ);
            }
        }
        throw new IllegalStateException("Could not find an unloaded chunk for the C-02 safety probe");
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private record ActiveFixture(
            MinecraftServer server,
            Logger logger,
            ForgeCreateKineticAdapter adapter,
            int startTick,
            BlockPos stopped,
            BlockPos poweredPositive,
            BlockPos poweredNegative,
            BlockPos overloaded,
            AtomicReference<AdapterResult<KineticSnapshot>> wrongThreadResult) {
    }
}
