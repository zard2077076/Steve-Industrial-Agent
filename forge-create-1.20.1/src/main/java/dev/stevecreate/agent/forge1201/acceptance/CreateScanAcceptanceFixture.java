package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.ObservedComponent;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.adapter.api.WorldSnapshot;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.adapter.ForgeRegistryIndustrialAdapter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Development-only, isolated dedicated-server fixture for the C-01 acceptance.
 * It uses the real Create registry block, production adapter and registered command.
 */
public final class CreateScanAcceptanceFixture {
    private static final ResourceLocation SHAFT_KEY = ResourceLocation.fromNamespaceAndPath("create", "shaft");
    private static final ResourceId EXPECTED_SHAFT_ID = new ResourceId("create", "shaft");
    private static final String EXPECTED_CREATE_VERSION = "6.0.6-150";
    private static final int SCAN_RADIUS = 1;
    private static final String COMMAND = "/industrialagent scan create 1";

    private CreateScanAcceptanceFixture() {
    }

    public static void run(MinecraftServer server, Logger logger) {
        try {
            check(!FMLLoader.isProduction(), "C-01 acceptance fixture is disabled in production");
            ServerLevel level = server.overworld();
            check(server.isSameThread(), "C-01 fixture must run on the authoritative server thread");

            BlockPos fixturePosition = fixturePosition(level);
            placeShaft(level, fixturePosition);

            ForgeRegistryIndustrialAdapter adapter = new ForgeRegistryIndustrialAdapter(level, "create");
            WorldSnapshot snapshot = requireSuccess(adapter.capture(requestAt(fixturePosition)), "fixture scan");
            ObservedComponent component = verifySnapshot(snapshot, fixturePosition);
            logger.info(
                    "CREATE_SCAN_FIXTURE_RESULT block={} namespace={} position={},{},{} state.axis={} radius={} components={}",
                    component.blockId(),
                    component.blockId().namespace(),
                    component.position().x(),
                    component.position().y(),
                    component.position().z(),
                    component.state().get("axis"),
                    snapshot.radius(),
                    snapshot.components().size());

            RecordingCommandSource recording = new RecordingCommandSource();
            CommandSourceStack commandSource = server.createCommandSourceStack()
                    .withSource(recording)
                    .withLevel(level)
                    .withPosition(Vec3.atCenterOf(fixturePosition))
                    .withPermission(4);
            int commandResult = server.getCommands().performPrefixedCommand(commandSource, COMMAND);
            String commandMessage = recording.messages().stream()
                    .filter(message -> message.startsWith("create: 1 components; runtime="))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Command did not report the expected Create component: " + recording.messages()));
            check(commandResult == 1, "Expected command result 1 but received " + commandResult);
            logger.info(
                    "CREATE_SCAN_COMMAND_RESULT command=\"{}\" return={} message=\"{}\"",
                    COMMAND,
                    commandResult,
                    commandMessage);

            ChunkPos unloadedChunk = findUnloadedChunk(level, new ChunkPos(fixturePosition));
            BlockPos unloadedCenter = new BlockPos(
                    unloadedChunk.getMinBlockX() + 8,
                    fixturePosition.getY(),
                    unloadedChunk.getMinBlockZ() + 8);
            check(!level.hasChunk(unloadedChunk.x, unloadedChunk.z),
                    "Unloaded-chunk precondition changed before capture");
            AdapterResult<WorldSnapshot> unloadedResult = adapter.capture(requestAt(unloadedCenter));
            check(unloadedResult instanceof AdapterResult.Failure<WorldSnapshot>,
                    "Expected unloaded scan failure but received " + unloadedResult);
            AdapterResult.Failure<WorldSnapshot> unloadedFailure =
                    (AdapterResult.Failure<WorldSnapshot>) unloadedResult;
            check(unloadedFailure.code() == AdapterFailureCode.CHUNK_NOT_LOADED,
                    "Expected CHUNK_NOT_LOADED but received " + unloadedFailure);
            check(!level.hasChunk(unloadedChunk.x, unloadedChunk.z),
                    "Read-only scan loaded chunk " + unloadedChunk.x + "," + unloadedChunk.z);
            logger.info(
                    "CREATE_SCAN_UNLOADED_CHUNK_RESULT chunk={},{} before=false code={} after=false",
                    unloadedChunk.x,
                    unloadedChunk.z,
                    unloadedFailure.code());

            logger.info(
                    "CREATE_SCAN_ACCEPTANCE PASS minecraft={} forge={} create={} boundedRadius={} commandReturn={} unloadedChunkPreserved=true",
                    snapshot.runtime().minecraftVersion(),
                    snapshot.runtime().loaderVersion(),
                    snapshot.runtime().industrialModVersions().get("create"),
                    snapshot.radius(),
                    commandResult);
            server.halt(false);
        } catch (RuntimeException exception) {
            logger.error("CREATE_SCAN_ACCEPTANCE FAIL", exception);
            server.halt(false);
            throw exception;
        }
    }

    private static BlockPos fixturePosition(ServerLevel level) {
        ChunkPos spawnChunk = new ChunkPos(level.getSharedSpawnPos());
        check(level.hasChunk(spawnChunk.x, spawnChunk.z),
                "Shared spawn chunk is not loaded: " + spawnChunk.x + "," + spawnChunk.z);
        int x = spawnChunk.getMinBlockX() + 8;
        int z = spawnChunk.getMinBlockZ() + 8;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = Math.min(level.getMaxBuildHeight() - 2, surfaceY + 2);
        check(y - SCAN_RADIUS >= level.getMinBuildHeight(), "Fixture is below the build limit");
        return new BlockPos(x, y, z);
    }

    private static void placeShaft(ServerLevel level, BlockPos position) {
        Block shaft = ForgeRegistries.BLOCKS.getValue(SHAFT_KEY);
        check(shaft != null && ForgeRegistries.BLOCKS.getKey(shaft) != null,
                "Create shaft is absent from the active Forge block registry");
        BlockState state = shaft.defaultBlockState();
        check(state.hasProperty(BlockStateProperties.AXIS),
                "create:shaft does not expose the expected axis block state");
        state = state.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        check(level.setBlockAndUpdate(position, state), "Failed to place create:shaft at " + position);
        check(level.getBlockState(position).is(shaft), "Placed fixture is not create:shaft");
    }

    private static ScanRequest requestAt(BlockPos position) {
        return new ScanRequest(
                new BlockPos3i(position.getX(), position.getY(), position.getZ()),
                SCAN_RADIUS);
    }

    private static WorldSnapshot requireSuccess(
            AdapterResult<WorldSnapshot> result,
            String operation) {
        if (result instanceof AdapterResult.Success<WorldSnapshot> success) {
            return success.value();
        }
        throw new IllegalStateException(operation + " expected SUCCESS but received " + result);
    }

    private static ObservedComponent verifySnapshot(WorldSnapshot snapshot, BlockPos fixturePosition) {
        BlockPos3i expectedPosition = new BlockPos3i(
                fixturePosition.getX(), fixturePosition.getY(), fixturePosition.getZ());
        check(snapshot.center().equals(expectedPosition),
                "Snapshot center mismatch: " + snapshot.center());
        check(snapshot.radius() == SCAN_RADIUS,
                "Snapshot radius mismatch: " + snapshot.radius());
        check(snapshot.components().size() == 1,
                "Expected exactly one Create component but received " + snapshot.components());
        ObservedComponent component = snapshot.components().get(0);
        check(component.blockId().equals(EXPECTED_SHAFT_ID),
                "Expected create:shaft but received " + component.blockId());
        check(component.position().equals(expectedPosition),
                "Component position mismatch: " + component.position());
        check("x".equals(component.state().get("axis")),
                "Expected shaft axis=x but received " + component.state());
        check(EXPECTED_CREATE_VERSION.equals(snapshot.runtime().industrialModVersions().get("create")),
                "Expected Create " + EXPECTED_CREATE_VERSION + " but received " + snapshot.runtime());
        return component;
    }

    private static ChunkPos findUnloadedChunk(ServerLevel level, ChunkPos origin) {
        for (int distance = 128; distance <= 2048; distance += 128) {
            int chunkX = origin.x + distance;
            int chunkZ = origin.z + distance;
            if (!level.hasChunk(chunkX, chunkZ)) {
                return new ChunkPos(chunkX, chunkZ);
            }
        }
        throw new IllegalStateException("Could not find an unloaded chunk for the C-01 safety probe");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }

    private static final class RecordingCommandSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
