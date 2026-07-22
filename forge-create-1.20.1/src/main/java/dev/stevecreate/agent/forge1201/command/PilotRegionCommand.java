package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogResult;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.PilotRegionConfirmation;
import dev.stevecreate.agent.core.deployment.PilotRegionResult;
import dev.stevecreate.agent.core.deployment.PilotRegionSelection;
import dev.stevecreate.agent.core.deployment.PilotRegionSelectionService;
import dev.stevecreate.agent.core.deployment.WritableTestWorldCandidate;
import dev.stevecreate.agent.core.deployment.WritableTestWorldDiscovery;
import dev.stevecreate.agent.core.deployment.WritableTestWorldFailure;
import dev.stevecreate.agent.core.deployment.WritableTestWorldFailureCode;
import dev.stevecreate.agent.core.deployment.WritableTestWorldIdentity;
import dev.stevecreate.agent.core.deployment.WritableTestWorldOccupancy;
import dev.stevecreate.agent.core.deployment.WritableTestWorldPolicy;
import dev.stevecreate.agent.core.deployment.WritableTestWorldStage;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.adapter.create.ForgeCreateRuntimeRecipeCatalogs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Server-authoritative IWP-01/02/03 region command surface. Preview never mutates a block. */
public final class PilotRegionCommand {
    public static final String EXPECTED_GAME_DIR = "steve_industrial.iwp.expectedGameDir";
    public static final String FORBIDDEN_ROOT = "steve_industrial.iwp.forbiddenRoot";
    public static final String TEST_INSTANCE_IDENTITY = "steve_industrial.iwp.testInstanceIdentity";
    public static final String FORMAL_WORLD_IDENTITY = "steve_industrial.iwp.formalWorldIdentity";
    public static final String INSTANCE_MARKER = ".steve-industrial-writable-test-instance";
    public static final String INSTANCE_MARKER_VALUE = "steve-industrial:iwp-test-instance/v1";
    public static final String WORLD_MARKER = ".steve-industrial-writable-test-world";
    private static final String POLICY_VERSION = "steve-industrial:iwp-region-policy/v1";
    private static final int SCAN_PER_TICK = 4_096;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PilotRegionSelectionService REGIONS = new PilotRegionSelectionService();
    private static final Map<UUID, PlayerPilotState> STATES = new HashMap<>();
    private static final Map<UUID, PreviewScan> SCANS = new HashMap<>();

    private PilotRegionCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pilot");
        root.then(Commands.literal("region")
                        .then(Commands.literal("here")
                                .then(Commands.argument("size_x", IntegerArgumentType.integer(
                                                1, PilotRegionSelectionService.MAX_X))
                                        .then(Commands.argument("size_z", IntegerArgumentType.integer(
                                                        1, PilotRegionSelectionService.MAX_Z))
                                                .then(Commands.argument("size_y", IntegerArgumentType.integer(
                                                                1, PilotRegionSelectionService.MAX_Y))
                                                        .executes(context -> selectHere(context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "size_x"),
                                                                IntegerArgumentType.getInteger(context, "size_z"),
                                                                IntegerArgumentType.getInteger(context, "size_y")))))))
                        .then(Commands.literal("pos1").executes(context -> corner(context.getSource(), true)))
                        .then(Commands.literal("pos2").executes(context -> corner(context.getSource(), false)))
                        .then(Commands.literal("preview").executes(context -> preview(context.getSource())))
                        .then(Commands.literal("confirm").executes(context -> confirm(context.getSource())))
                        .then(Commands.literal("clear").executes(context -> clear(context.getSource())))
                        .then(Commands.literal("status").executes(context -> status(context.getSource()))));
        PilotDeploymentCommand.attach(root);
        return root;
    }

    public static void tick(MinecraftServer server) {
        if (SCANS.isEmpty()) return;
        List<UUID> completed = new ArrayList<>();
        for (Map.Entry<UUID, PreviewScan> entry : List.copyOf(SCANS.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                completed.add(entry.getKey());
                continue;
            }
            PreviewScan scan = entry.getValue();
            if (!player.level().dimension().location().toString().equals(
                    scan.selection.dimension().toString())) {
                sendFailure(player.createCommandSourceStack(), failure(
                        WritableTestWorldFailureCode.REGION_DIMENSION_MISMATCH,
                        WritableTestWorldStage.REGION_PREVIEW, scan.context,
                        scan.selection.bounds().toString(), scan.selection.regionHash(),
                        "Player changed dimension during region preview",
                        "Return to the selected dimension and run preview again"));
                completed.add(entry.getKey());
                continue;
            }
            if (scan.advance(player.serverLevel(), SCAN_PER_TICK)) {
                PlayerPilotState state = STATES.get(entry.getKey());
                if (state == null || state.selection != scan.selection) {
                    completed.add(entry.getKey());
                    continue;
                }
                String fingerprint = scan.fingerprint();
                PilotRegionResult<PilotRegionSelection> result = REGIONS.preview(
                        scan.selection, scan.context.identity.value(), scan.selection.dimension(),
                        fingerprint, Instant.now());
                if (result.failure().isPresent()) {
                    sendFailure(player.createCommandSourceStack(), result.failure().orElseThrow());
                } else {
                    state.selection = result.value().orElseThrow();
                    state.confirmation = null;
                    PilotDeploymentCommand.clearPlayer(player.getUUID());
                    String line = "Pilot region preview complete regionHash="
                            + state.selection.regionHash() + " fingerprint=" + fingerprint
                            + " bounds=" + format(state.selection.bounds())
                            + " nonAir=" + scan.nonAir + " dangerous=" + scan.dangerous
                            + " blockEntities=" + scan.blockEntities + " containers=" + scan.containers
                            + " unloaded=" + scan.unloaded + " worldMutation=false";
                    player.sendSystemMessage(Component.literal(line));
                    player.displayClientMessage(Component.literal(
                            "Pilot preview ready: confirm only after reviewing risks"), true);
                    LOGGER.info("IWP_REGION_PREVIEW_COMPLETE {}", line);
                }
                completed.add(entry.getKey());
            }
        }
        completed.forEach(SCANS::remove);
    }

    public static void clearServerState() {
        STATES.clear();
        SCANS.clear();
        PilotDeploymentCommand.clearServerState();
    }

    private static int selectHere(CommandSourceStack source, int sizeX, int sizeZ, int sizeY) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        if (sizeX > context.maxRegionSize || sizeZ > context.maxRegionSize) {
            source.sendFailure(Component.literal("REGION_SIZE_EXCEEDS_CONFIGURED_MAX max="
                    + context.maxRegionSize + " requested=" + sizeX + "x" + sizeZ));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region selection requires a player"));
            return 0;
        }
        if (PilotDeploymentCommand.hasActiveSession(player.getUUID())) {
            source.sendFailure(Component.literal(
                    "PILOT_DUPLICATE_SESSION: cancel or finish the active pilot before changing its region"));
            return 0;
        }
        PlayerPilotState state = state(player);
        PilotDeploymentCommand.clearPlayer(player.getUUID());
        BlockPos position = player.blockPosition();
        PilotRegionResult<PilotRegionSelection> result = REGIONS.selectHere(
                context.identity, dimension(source.getLevel()),
                new BlockPos3i(position.getX(), position.getY(), position.getZ()),
                sizeX, sizeZ, sizeY, source.getLevel().getMinBuildHeight(),
                source.getLevel().getMaxBuildHeight(), playerIdentity(player), state.sessionIdentity,
                Instant.now(), Instant.now().plusSeconds(900), POLICY_VERSION);
        return acceptSelection(source, player.getUUID(), state, result, context.maxRegionSize);
    }

    private static int corner(CommandSourceStack source, boolean first) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region selection requires a player"));
            return 0;
        }
        if (PilotDeploymentCommand.hasActiveSession(player.getUUID())) {
            source.sendFailure(Component.literal(
                    "PILOT_DUPLICATE_SESSION: cancel or finish the active pilot before changing its region"));
            return 0;
        }
        PlayerPilotState state = state(player);
        PilotDeploymentCommand.clearPlayer(player.getUUID());
        BlockPos block = player.blockPosition();
        BlockPos3i position = new BlockPos3i(block.getX(), block.getY(), block.getZ());
        if (first) state.pos1 = position;
        else state.pos2 = position;
        state.selection = null;
        state.confirmation = null;
        SCANS.remove(player.getUUID());
        source.sendSuccess(() -> Component.literal((first ? "pos1=" : "pos2=") + position
                + " world=" + context.identity.value() + " dimension=" + dimension(source.getLevel())
                + " worldMutation=false"), false);
        if (state.pos1 == null || state.pos2 == null) return 1;
        PilotRegionResult<PilotRegionSelection> result = REGIONS.selectCorners(
                context.identity, dimension(source.getLevel()), state.pos1, state.pos2,
                source.getLevel().getMinBuildHeight(), source.getLevel().getMaxBuildHeight(),
                playerIdentity(player), state.sessionIdentity, Instant.now(),
                Instant.now().plusSeconds(900), POLICY_VERSION);
        return acceptSelection(source, player.getUUID(), state, result, context.maxRegionSize);
    }

    private static int preview(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region preview requires a player"));
            return 0;
        }
        PlayerPilotState state = state(player);
        if (state.selection == null) {
            sendFailure(source, failure(WritableTestWorldFailureCode.REGION_NOT_SELECTED,
                    WritableTestWorldStage.REGION_PREVIEW, context,
                    WritableTestWorldFailure.UNAVAILABLE, WritableTestWorldFailure.UNAVAILABLE,
                    "No region is selected", "Run pilot region here or set pos1 and pos2"));
            return 0;
        }
        if (!state.selection.writableWorldIdentity().equals(context.identity.value())) {
            sendFailure(source, failure(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    WritableTestWorldStage.REGION_PREVIEW, context, state.selection.bounds().toString(),
                    state.selection.regionHash(), "Selected region belongs to another world",
                    "Clear and select a region in the current test world"));
            return 0;
        }
        PreviewScan scan = new PreviewScan(context, state.selection);
        SCANS.put(player.getUUID(), scan);
        showOutline(source.getLevel(), state.selection.bounds());
        source.sendSuccess(() -> Component.literal("Pilot region preview started regionHash="
                + state.selection.regionHash() + " bounds=" + format(state.selection.bounds())
                + " center=" + center(state.selection.bounds()) + " facing=" + player.getDirection()
                + " scanPerTick=" + SCAN_PER_TICK + " permanentBlocksPlaced=0 worldMutation=false"), false);
        return 1;
    }

    private static int confirm(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region confirmation requires a player"));
            return 0;
        }
        PlayerPilotState state = state(player);
        if (state.selection == null) {
            sendFailure(source, failure(WritableTestWorldFailureCode.REGION_NOT_SELECTED,
                    WritableTestWorldStage.REGION_CONFIRMATION, context,
                    WritableTestWorldFailure.UNAVAILABLE, WritableTestWorldFailure.UNAVAILABLE,
                    "No region is selected", "Select and preview a region first"));
            return 0;
        }
        if (SCANS.containsKey(player.getUUID())) {
            sendFailure(source, failure(WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                    WritableTestWorldStage.REGION_CONFIRMATION, context,
                    state.selection.bounds().toString(), state.selection.regionHash(),
                    "Region preview scan is still running", "Wait for preview complete, then review and confirm"));
            return 0;
        }
        PilotRegionResult<PilotRegionConfirmation> result = REGIONS.confirm(
                state.selection, context.identity.value(), dimension(source.getLevel()),
                state.selection.previewWorldFingerprint(), playerIdentity(player),
                state.sessionIdentity, Instant.now());
        if (result.failure().isPresent()) {
            sendFailure(source, result.failure().orElseThrow());
            return 0;
        }
        state.confirmation = result.value().orElseThrow();
        PilotDeploymentCommand.clearPlayer(player.getUUID());
        source.sendSuccess(() -> Component.literal("Pilot region confirmed confirmation="
                + state.confirmation.confirmationIdentity() + " world="
                + state.confirmation.writableWorldIdentity() + " dimension="
                + state.confirmation.dimension() + " bounds=" + format(state.confirmation.bounds())
                + " regionHash=" + state.confirmation.regionHash() + " player="
                + state.confirmation.playerIdentity() + " session="
                + state.confirmation.sessionIdentity() + " expires="
                + state.confirmation.expiresAt() + " policy=" + state.confirmation.policyVersion()
                + " worldMutation=false"), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region clear requires a player"));
            return 0;
        }
        if (PilotDeploymentCommand.hasActiveSession(player.getUUID())) {
            source.sendFailure(Component.literal(
                    "PILOT_DUPLICATE_SESSION: cancel or finish the active pilot before clearing its region"));
            return 0;
        }
        STATES.remove(player.getUUID());
        SCANS.remove(player.getUUID());
        PilotDeploymentCommand.clearPlayer(player.getUUID());
        source.sendSuccess(() -> Component.literal("Pilot region cleared world="
                + context.identity.value() + " worldMutation=false"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return 0;
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot region status requires a player"));
            return 0;
        }
        PlayerPilotState state = STATES.get(player.getUUID());
        if (state == null) {
            source.sendSuccess(() -> Component.literal("Pilot region status world="
                    + context.identity.value() + " state=NOT_SELECTED worldMutation=false"), false);
            return 1;
        }
        String line = "Pilot region status world=" + context.identity.value()
                + " pos1=" + value(state.pos1) + " pos2=" + value(state.pos2)
                + " selection=" + (state.selection == null ? "unavailable" : state.selection.selectionIdentity())
                + " state=" + (state.selection == null ? "NOT_SELECTED" : state.selection.state())
                + " regionHash=" + (state.selection == null ? "unavailable" : state.selection.regionHash())
                + " confirmed=" + (state.confirmation != null)
                + " previewScanRunning=" + SCANS.containsKey(player.getUUID())
                + " session=" + state.sessionIdentity + " worldMutation=false";
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int acceptSelection(
            CommandSourceStack source,
            UUID playerId,
            PlayerPilotState state,
            PilotRegionResult<PilotRegionSelection> result,
            int maxRegionSize) {
        if (result.failure().isPresent()) {
            sendFailure(source, result.failure().orElseThrow());
            return 0;
        }
        PilotRegionSelection selected = result.value().orElseThrow();
        int width = selected.bounds().maximum().x() - selected.bounds().minimum().x() + 1;
        int depth = selected.bounds().maximum().z() - selected.bounds().minimum().z() + 1;
        if (width > maxRegionSize || depth > maxRegionSize) {
            source.sendFailure(Component.literal("REGION_SIZE_EXCEEDS_CONFIGURED_MAX max="
                    + maxRegionSize + " requested=" + width + "x" + depth));
            return 0;
        }
        state.selection = selected;
        state.confirmation = null;
        PilotDeploymentCommand.clearPlayer(playerId);
        source.sendSuccess(() -> Component.literal("Pilot region selected bounds="
                + format(state.selection.bounds()) + " regionHash=" + state.selection.regionHash()
                + " state=SELECTED confirmBeforeWrite=false worldMutation=false"), false);
        return 1;
    }

    private static Optional<RuntimeContext> requireContext(CommandSourceStack source) {
        PublicAlphaRuntime.Result publicResult = PublicAlphaRuntime.resolve(source.getLevel(), true);
        if (publicResult instanceof PublicAlphaRuntime.Success success) {
            try {
                return Optional.of(publicContext(source, success.authorization()));
            } catch (Exception failure) {
                source.sendFailure(Component.literal("Pilot runtime refused code=PUBLIC_CONTEXT_INVALID:"
                        + failure.getMessage() + " paths=redacted"));
                return Optional.empty();
            }
        }
        if (System.getProperty(EXPECTED_GAME_DIR) == null) {
            source.sendFailure(Component.literal("Pilot runtime refused code="
                    + ((PublicAlphaRuntime.Failure) publicResult).code()
                    + " safeNextStep=/industrialagent setup show-config paths=redacted"));
            return Optional.empty();
        }
        return legacyContext(source);
    }

    private static RuntimeContext publicContext(
            CommandSourceStack source,
            PublicAlphaRuntime.Authorization authorization) throws Exception {
        RuntimeRecipeCatalogResult runtimeResult =
                ForgeCreateRuntimeRecipeCatalogs.forLevel(source.getLevel()).snapshot();
        if (!(runtimeResult instanceof RuntimeRecipeCatalogResult.Success runtime)) {
            throw new IllegalStateException("CREATE_RUNTIME_FINGERPRINT_UNAVAILABLE");
        }
        String instanceIdentity = "instance:" + sha256(normalized(authorization.gameDir()));
        String worldFingerprint = "sha256:" + sha256(authorization.worldIdentity() + "\n"
                + runtime.snapshot().runtimeFingerprint() + "\n" + authorization.markerGeneration());
        WritableTestWorldCandidate candidate = new WritableTestWorldCandidate(
                authorization.worldIdentity(), authorization.worldName(),
                authorization.worldRoot().getFileName().toString(), authorization.gameDir(),
                authorization.worldRoot(), WritableTestWorldIdentity.REQUIRED_MARKER,
                runtime.snapshot().runtimeFingerprint(), worldFingerprint, instanceIdentity,
                WorldEnvironmentType.ISOLATED_TEST_WORLD, true, WritableTestWorldOccupancy.IN_USE,
                List.of("authoritative server thread", "production Forge config validated",
                        "versioned world-local marker generation=" + authorization.markerGeneration()));
        Set<String> formalIdentities = authorization.importantRoots().stream()
                .map(path -> "formal-root:" + sha256(normalized(path))).collect(java.util.stream.Collectors.toSet());
        WritableTestWorldPolicy policy = new WritableTestWorldPolicy(
                authorization.gameDir(), authorization.importantRoots().get(0),
                Set.of(authorization.backupRoot()), instanceIdentity, formalIdentities);
        var discovery = new WritableTestWorldDiscovery().discover(List.of(candidate), policy);
        if (discovery.failure().isPresent()) throw new IllegalStateException(
                discovery.failure().orElseThrow().code().name());
        return new RuntimeContext(discovery.identity().orElseThrow(), policy,
                authorization.maxRegionSize());
    }

    private static Optional<RuntimeContext> legacyContext(CommandSourceStack source) {
        try {
            Path actualGame = Path.of(System.getProperty("user.dir")).toRealPath();
            String expectedValue = requiredProperty(EXPECTED_GAME_DIR);
            String forbiddenValue = requiredProperty(FORBIDDEN_ROOT);
            String instanceIdentity = requiredProperty(TEST_INSTANCE_IDENTITY);
            String formalWorldIdentity = requiredProperty(FORMAL_WORLD_IDENTITY);
            Path expectedGame = Path.of(expectedValue).toRealPath();
            Path forbidden = Path.of(forbiddenValue).toRealPath();
            Path world = source.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
            if (actualGame.startsWith(forbidden) || world.startsWith(forbidden)) {
                sendFailure(source, basicFailure(WritableTestWorldFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                        actualGame, world, instanceIdentity, formalWorldIdentity,
                        "Current gameDir or world is inside the formal platform root",
                        "Close this world and use the dedicated repository-owned test instance"));
                return Optional.empty();
            }
            if (!actualGame.equals(expectedGame)
                    || !world.startsWith(actualGame.resolve("saves"))
                    || !marker(actualGame.resolve(INSTANCE_MARKER), INSTANCE_MARKER_VALUE)
                    || !marker(world.resolve(WORLD_MARKER), WritableTestWorldIdentity.REQUIRED_MARKER)) {
                sendFailure(source, basicFailure(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                        actualGame, world, instanceIdentity, "unavailable",
                        "Exact test gameDir, saves root, instance marker, or world marker is invalid",
                        "Use only the prepared SteveAgent_DeceasedCraft_Test instance and marked world"));
                return Optional.empty();
            }
            RuntimeRecipeCatalogResult runtimeResult =
                    ForgeCreateRuntimeRecipeCatalogs.forLevel(source.getLevel()).snapshot();
            if (!(runtimeResult instanceof RuntimeRecipeCatalogResult.Success runtime)) {
                sendFailure(source, basicFailure(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                        actualGame, world, instanceIdentity, "unavailable",
                        "Create runtime fingerprint is unavailable: " + runtimeResult,
                        "Wait for datapack loading to finish and retry status"));
                return Optional.empty();
            }
            String levelName = source.getServer().getWorldData().getLevelName();
            String sourceWorldIdentity = "world:" + sha256(levelName + "\n" + normalized(world));
            String worldFingerprint = "sha256:" + sha256(sourceWorldIdentity + "\n"
                    + runtime.snapshot().runtimeFingerprint() + "\n" + normalized(world));
            WritableTestWorldCandidate candidate = new WritableTestWorldCandidate(
                    sourceWorldIdentity, levelName, world.getFileName().toString(), actualGame, world,
                    WritableTestWorldIdentity.REQUIRED_MARKER, runtime.snapshot().runtimeFingerprint(),
                    worldFingerprint, instanceIdentity, WorldEnvironmentType.ISOLATED_TEST_WORLD,
                    true, WritableTestWorldOccupancy.IN_USE,
                    List.of("authoritative server thread", "exact instance and world markers verified"));
            WritableTestWorldPolicy policy = new WritableTestWorldPolicy(
                    expectedGame.getParent(), forbidden,
                    Set.of(expectedGame.getParent().getParent().resolve("writable-world-backups")),
                    instanceIdentity, Set.of(formalWorldIdentity));
            var discovery = new WritableTestWorldDiscovery().discover(List.of(candidate), policy);
            if (discovery.failure().isPresent()) {
                sendFailure(source, discovery.failure().orElseThrow());
                return Optional.empty();
            }
            return Optional.of(new RuntimeContext(discovery.identity().orElseThrow(), policy,
                    PilotRegionSelectionService.MAX_X));
        } catch (Exception failure) {
            Path actual = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            sendFailure(source, basicFailure(WritableTestWorldFailureCode.TEST_INSTANCE_NOT_FOUND,
                    actual, actual, "unavailable", "unavailable",
                    "Test instance preflight failed closed: " + failure.getMessage(),
                    "Prepare the dedicated instance and required IWP launch properties"));
            return Optional.empty();
        }
    }

    static Optional<ConfirmedPilotContext> confirmedContext(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return Optional.empty();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot readiness requires a player"));
            return Optional.empty();
        }
        PlayerPilotState state = STATES.get(player.getUUID());
        if (state == null || state.selection == null || state.confirmation == null) {
            sendFailure(source, failure(WritableTestWorldFailureCode.REGION_NOT_CONFIRMED,
                    WritableTestWorldStage.READINESS, context,
                    state == null || state.selection == null
                            ? WritableTestWorldFailure.UNAVAILABLE
                            : state.selection.bounds().toString(),
                    state == null || state.selection == null
                            ? WritableTestWorldFailure.UNAVAILABLE
                            : state.selection.regionHash(),
                    "No current confirmed pilot region exists",
                    "Run pilot region here, preview, and confirm first"));
            return Optional.empty();
        }
        if (!state.confirmation.writableWorldIdentity().equals(context.identity.value())
                || !state.confirmation.dimension().equals(dimension(source.getLevel()))
                || !state.confirmation.playerIdentity().equals(playerIdentity(player))
                || !state.confirmation.sessionIdentity().equals(state.sessionIdentity)
                || !Instant.now().isBefore(state.confirmation.expiresAt())) {
            sendFailure(source, failure(WritableTestWorldFailureCode.REGION_HASH_STALE,
                    WritableTestWorldStage.READINESS, context,
                    state.confirmation.bounds().toString(), state.confirmation.regionHash(),
                    "Confirmed region identity, owner, dimension, session, or expiry is stale",
                    "Clear, preview, and confirm the region again"));
            return Optional.empty();
        }
        return Optional.of(new ConfirmedPilotContext(
                context.identity, context.policy, state.selection, state.confirmation,
                player, state.sessionIdentity));
    }

    static Optional<CurrentPilotContext> currentContext(CommandSourceStack source) {
        RuntimeContext context = requireContext(source).orElse(null);
        if (context == null) return Optional.empty();
        try {
            return Optional.of(new CurrentPilotContext(
                    context.identity, context.policy, source.getPlayerOrException()));
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("Pilot lifecycle command requires a player"));
            return Optional.empty();
        }
    }

    private static WritableTestWorldFailure basicFailure(
            WritableTestWorldFailureCode code,
            Path game,
            Path world,
            String instance,
            String worldIdentity,
            String reason,
            String next) {
        return new WritableTestWorldFailure(code, WritableTestWorldStage.IDENTITY,
                instance, worldIdentity, normalized(world), "unavailable", "unavailable",
                "unavailable", "unavailable", "unavailable",
                List.of("gameDir=" + normalized(game), "world=" + normalized(world)), reason, next);
    }

    private static WritableTestWorldFailure failure(
            WritableTestWorldFailureCode code,
            WritableTestWorldStage stage,
            RuntimeContext context,
            String region,
            String preview,
            String reason,
            String next) {
        return new WritableTestWorldFailure(code, stage, context.identity.testInstanceIdentity(),
                context.identity.value(), normalized(context.identity.canonicalWorldPath()),
                context.identity.worldFingerprint(), region, preview, "unavailable", "unavailable",
                List.of("policy=" + POLICY_VERSION), reason, next);
    }

    private static void sendFailure(CommandSourceStack source, WritableTestWorldFailure failure) {
        String line = "Pilot refused code=" + failure.code() + " stage=" + failure.stage()
                + " testInstance=" + failure.testInstanceIdentity() + " world=" + failure.worldIdentity()
                + " worldPath=" + failure.canonicalWorldPath() + " fingerprint="
                + failure.worldFingerprint() + " region=" + failure.region() + " previewHash="
                + failure.previewHash() + " backup=" + failure.backupIdentity() + " session="
                + failure.sessionIdentity() + " evidence=" + failure.evidence() + " reason="
                + failure.reason() + " safeNextStep=" + failure.safeNextStep()
                + " formalWorldExecutable=false worldMutation=false";
        source.sendFailure(Component.literal(line));
        LOGGER.info("IWP_TYPED_REFUSAL {}", line);
    }

    private static PlayerPilotState state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> new PlayerPilotState(
                "pilot-session:" + id + ":" + UUID.randomUUID()));
    }

    private static ResourceId dimension(ServerLevel level) {
        return ResourceId.parse(level.dimension().location().toString());
    }

    private static String playerIdentity(ServerPlayer player) {
        return "player:" + player.getUUID();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing property " + name);
        return value;
    }

    private static boolean marker(Path file, String expected) throws IOException {
        return Files.isRegularFile(file)
                && expected.equals(Files.readString(file, StandardCharsets.US_ASCII).trim());
    }

    private static void showOutline(ServerLevel level, DeploymentBoundingBox bounds) {
        BlockPos3i min = bounds.minimum();
        BlockPos3i max = bounds.maximum();
        for (int x : samples(min.x(), max.x())) {
            for (int y : List.of(min.y(), max.y())) {
                particle(level, x, y, min.z());
                particle(level, x, y, max.z());
            }
        }
        for (int z : samples(min.z(), max.z())) {
            for (int y : List.of(min.y(), max.y())) {
                particle(level, min.x(), y, z);
                particle(level, max.x(), y, z);
            }
        }
        for (int y : samples(min.y(), max.y())) {
            for (int x : List.of(min.x(), max.x())) {
                for (int z : List.of(min.z(), max.z())) particle(level, x, y, z);
            }
        }
        BlockPos3i center = center(bounds);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x() + 0.5,
                center.y() + 0.5, center.z() + 0.5, 12, 0.5, 0.5, 0.5, 0.0);
    }

    private static List<Integer> samples(int minimum, int maximum) {
        int step = Math.max(1, (maximum - minimum) / 15);
        List<Integer> result = new ArrayList<>();
        for (int value = minimum; value <= maximum; value += step) result.add(value);
        if (result.get(result.size() - 1) != maximum) result.add(maximum);
        return result;
    }

    private static void particle(ServerLevel level, int x, int y, int z) {
        level.sendParticles(ParticleTypes.END_ROD, x + 0.5, y + 0.5, z + 0.5,
                1, 0.0, 0.0, 0.0, 0.0);
    }

    private static BlockPos3i center(DeploymentBoundingBox bounds) {
        return new BlockPos3i((bounds.minimum().x() + bounds.maximum().x()) / 2,
                (bounds.minimum().y() + bounds.maximum().y()) / 2,
                (bounds.minimum().z() + bounds.maximum().z()) / 2);
    }

    private static String format(DeploymentBoundingBox bounds) {
        return bounds.minimum().x() + "," + bounds.minimum().y() + "," + bounds.minimum().z()
                + ".." + bounds.maximum().x() + "," + bounds.maximum().y() + "," + bounds.maximum().z();
    }

    private static String value(Object value) {
        return value == null ? "unavailable" : value.toString();
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record ConfirmedPilotContext(
            WritableTestWorldIdentity identity,
            WritableTestWorldPolicy policy,
            PilotRegionSelection selection,
            PilotRegionConfirmation confirmation,
            ServerPlayer player,
            String sessionIdentity) {}

    record CurrentPilotContext(
            WritableTestWorldIdentity identity,
            WritableTestWorldPolicy policy,
            ServerPlayer player) {}

    private record RuntimeContext(
            WritableTestWorldIdentity identity,
            WritableTestWorldPolicy policy,
            int maxRegionSize) {}

    private static final class PlayerPilotState {
        private final String sessionIdentity;
        private BlockPos3i pos1;
        private BlockPos3i pos2;
        private PilotRegionSelection selection;
        private PilotRegionConfirmation confirmation;

        private PlayerPilotState(String sessionIdentity) {
            this.sessionIdentity = sessionIdentity;
        }
    }

    private static final class PreviewScan {
        private final RuntimeContext context;
        private final PilotRegionSelection selection;
        private final MessageDigest digest;
        private int x;
        private int y;
        private int z;
        private int nonAir;
        private int dangerous;
        private int blockEntities;
        private int containers;
        private int unloaded;

        private PreviewScan(RuntimeContext context, PilotRegionSelection selection) {
            this.context = context;
            this.selection = selection;
            this.x = selection.bounds().minimum().x();
            this.y = selection.bounds().minimum().y();
            this.z = selection.bounds().minimum().z();
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
            update("regionHash=" + selection.regionHash());
        }

        private boolean advance(ServerLevel level, int limit) {
            DeploymentBoundingBox bounds = selection.bounds();
            int processed = 0;
            while (processed < limit && y <= bounds.maximum().y()) {
                BlockPos position = new BlockPos(x, y, z);
                if (!level.hasChunkAt(position)) {
                    unloaded++;
                    update(x + "," + y + "," + z + "=UNLOADED");
                } else {
                    var state = level.getBlockState(position);
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (!state.isAir()) nonAir++;
                    if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                            || state.is(Blocks.LAVA) || !state.getFluidState().isEmpty()) dangerous++;
                    BlockEntity blockEntity = level.getBlockEntity(position);
                    String entityId = "none";
                    if (blockEntity != null) {
                        blockEntities++;
                        if (blockEntity instanceof Container) containers++;
                        ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
                        entityId = key == null ? "unknown" : key.toString();
                    }
                    update(x + "," + y + "," + z + "="
                            + (blockId == null ? "minecraft:air" : blockId) + "|" + state
                            + "|blockEntity=" + entityId);
                }
                processed++;
                x++;
                if (x > bounds.maximum().x()) {
                    x = bounds.minimum().x();
                    z++;
                    if (z > bounds.maximum().z()) {
                        z = bounds.minimum().z();
                        y++;
                    }
                }
            }
            return y > bounds.maximum().y();
        }

        private String fingerprint() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }

        private void update(String value) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
    }
}
