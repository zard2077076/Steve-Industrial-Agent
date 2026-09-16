package dev.stevecreate.agent.acceptance.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

/**
 * One-request-per-connection JSON bridge bound to loopback. The endpoint contains no token.
 */
final class ClientAcceptanceBridge implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_REQUEST_CHARS = 65_536;
    private static final String ENDPOINT_PROTOCOL = "steve-agent-client-acceptance-bridge/v1";
    private static final String EXACT_WORLD = "Steve Agent Mac Acceptance";
    private static volatile ClientAcceptanceBridge active;

    private final String token;
    private final String expectedWorld;
    private final Path gameRoot;
    private final Path endpointPath;
    private final ServerSocket server;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ClientAcceptanceBridge(
            String token, String expectedWorld, Path gameRoot, Path endpointPath, int port)
            throws IOException {
        this.token = token;
        this.expectedWorld = expectedWorld;
        this.gameRoot = gameRoot;
        this.endpointPath = endpointPath;
        this.server = new ServerSocket();
        this.server.setReuseAddress(false);
        // The endpoint contract advertises IPv4. macOS resolves getLoopbackAddress() to ::1,
        // so binding that value while publishing 127.0.0.1 makes a healthy bridge unreachable.
        this.server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
    }

    static synchronized void startFromProperties() {
        if (active != null) throw new IllegalStateException("Acceptance bridge already started");
        if (!Boolean.getBoolean("steve_industrial.acceptance.bridge.enabled")) {
            throw new IllegalStateException("Acceptance bridge source set requires explicit enablement");
        }
        String tokenFileText = System.getProperty(
                "steve_industrial.acceptance.bridge.tokenFile", "");
        String world = System.getProperty("steve_industrial.acceptance.bridge.world", "");
        String expectedRoot = System.getProperty("steve_industrial.acceptance.bridge.gameRoot", "");
        String endpoint = System.getProperty("steve_industrial.acceptance.bridge.endpoint", "");
        String portText = System.getProperty("steve_industrial.acceptance.bridge.port", "0");
        if (!EXACT_WORLD.equals(world)) {
            throw new IllegalStateException("Acceptance bridge world must be exactly " + EXACT_WORLD);
        }
        try {
            Path gameRoot = Path.of(expectedRoot).toRealPath();
            Path actualRoot = Minecraft.getInstance().gameDirectory.toPath().toRealPath();
            if (!gameRoot.equals(actualRoot)) {
                throw new IllegalStateException("Acceptance bridge game root mismatch");
            }
            refuseGitAncestor(gameRoot);
            Path stateRoot = gameRoot.resolve(".steve-agent-harness").normalize();
            Path tokenPath = Path.of(tokenFileText).toAbsolutePath().normalize();
            Path expectedTokenPath = stateRoot.resolve("bridge-token").normalize();
            if (!tokenPath.equals(expectedTokenPath) || Files.isSymbolicLink(tokenPath)) {
                throw new IllegalStateException(
                        "Acceptance bridge token file is outside its state root");
            }
            String token;
            try {
                token = Files.readString(tokenPath, StandardCharsets.US_ASCII).trim();
            } finally {
                // Authority is single-use at launch. The 0600 session file remains the
                // CLI's private copy; neither the endpoint nor the process list receives it.
                Files.deleteIfExists(tokenPath);
            }
            if (!token.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException(
                        "Acceptance bridge token is not 256-bit lowercase hex");
            }
            Path endpointPath = Path.of(endpoint).toAbsolutePath().normalize();
            if (!endpointPath.equals(stateRoot.resolve("bridge.json"))) {
                throw new IllegalStateException("Acceptance bridge endpoint is outside its state root");
            }
            int port = Integer.parseInt(portText);
            if (port < 0 || port > 65_535) throw new IllegalArgumentException("port");
            Files.createDirectories(stateRoot);
            disablePauseOnLostFocusForHarness();
            ClientAcceptanceBridge bridge = new ClientAcceptanceBridge(
                    token, world, gameRoot, endpointPath, port);
            bridge.writeEndpoint();
            bridge.startThread();
            active = bridge;
            Runtime.getRuntime().addShutdownHook(new Thread(bridge::close,
                    "steve-acceptance-bridge-shutdown"));
            LOGGER.info("STEVE_CLIENT_ACCEPTANCE_BRIDGE READY host=127.0.0.1 port={} world={}",
                    bridge.server.getLocalPort(), world);
        } catch (Exception error) {
            throw new IllegalStateException("Acceptance bridge refused startup: "
                    + error.getMessage(), error);
        }
    }

    private static void disablePauseOnLostFocusForHarness() {
        // Codex drives this dev-only bridge while another macOS process owns keyboard focus.
        // Leaving the vanilla preference active repeatedly opens PauseScreen during otherwise
        // screenless scenario phases.  Change runtime state only after every bridge authority
        // check has passed; do not persist an options.txt change or touch the production source set.
        Minecraft.getInstance().options.pauseOnLostFocus = false;
        LOGGER.info("STEVE_CLIENT_ACCEPTANCE_BRIDGE pauseOnLostFocus=false (runtime only)");
    }

    private static void refuseGitAncestor(Path root) {
        Path current = root;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                throw new IllegalStateException("SOURCE_TREE_GAME_DIR_REFUSED: " + current);
            }
            current = current.getParent();
        }
    }

    private void writeEndpoint() throws IOException {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("protocol_version", ENDPOINT_PROTOCOL);
        endpoint.put("host", "127.0.0.1");
        endpoint.put("port", server.getLocalPort());
        endpoint.put("pid", ProcessHandle.current().pid());
        endpoint.put("game_root", gameRoot.toString());
        endpoint.put("allowed_world", expectedWorld);
        endpoint.put("started_at", Instant.now().toString());
        Path temporary = endpointPath.resolveSibling(endpointPath.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(endpoint) + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temporary, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows acceptance keeps the same token boundary without POSIX modes.
        }
        try {
            Files.move(temporary, endpointPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, endpointPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void startThread() {
        Thread thread = new Thread(this::acceptLoop, "steve-acceptance-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(15_000);
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    write(socket, error(null, "BRIDGE_NOT_LOOPBACK", ""));
                    continue;
                }
                handle(socket);
            } catch (Exception error) {
                if (running.get()) LOGGER.warn("Acceptance bridge request failed", error);
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        String requestId = null;
        try {
            String line = readBoundedLine(socket);
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            requestId = string(request, "request_id");
            if (!authenticated(string(request, "token"))) {
                write(socket, error(requestId, "BRIDGE_AUTHENTICATION_FAILED", ""));
                return;
            }
            String command = string(request, "command");
            JsonObject args = request.has("args") && request.get("args").isJsonObject()
                    ? request.getAsJsonObject("args") : new JsonObject();
            write(socket, success(requestId, dispatch(command, args)));
        } catch (Exception error) {
            Throwable cause = unwrap(error);
            if (cause instanceof BridgeRefusal refusal) {
                write(socket, error(requestId, refusal.code(), refusal.getMessage()));
                return;
            }
            write(socket, error(requestId, "BRIDGE_INTERNAL_ERROR",
                    cause.getClass().getSimpleName()));
            LOGGER.warn("Acceptance bridge command failed", cause);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private Map<String, Object> dispatch(String command, JsonObject args) throws Exception {
        if (command == null || command.length() > 96) {
            throw new BridgeRefusal("BRIDGE_COMMAND_INVALID");
        }
        return switch (command) {
            case "client.status" -> onClient(ScreenInspector::clientStatus);
            case "screen.inspect" -> onClient(ScreenInspector::screen);
            case "screen.press" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.press(minecraft, string(args, "widget_id"));
            });
            case "screen.text" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.text(minecraft, string(args, "widget_id"),
                        string(args, "value"));
            });
            case "screen.close" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.closeScreen(minecraft);
            });
            case "screen.select_target" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.selectTarget(minecraft, string(args, "target"));
            });
            case "player.command" -> onClient(minecraft -> playerCommand(
                    minecraft, string(args, "command")));
            case "player.use_main_hand" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.useMainHand(minecraft);
            });
            case "player.confirm_placement" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.confirmPlacement(minecraft);
            });
            case "player.look_at" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.lookAt(minecraft, boundedPosition(minecraft, args));
            });
            case "player.bind_salvage" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.bindSalvage(minecraft, boundedPosition(minecraft, args));
            });
            case "player.bind_material_source" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                BlockPos position = boundedPosition(minecraft, args);
                return InputInjector.bindMaterialSource(minecraft, position, direction(args));
            });
            case "player.sneak_use_main_hand" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                return InputInjector.sneakUseMainHand(minecraft);
            });
            case "player.sneak_use_on" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                BlockPos position = boundedPosition(minecraft, args);
                Direction face = direction(args);
                return InputInjector.sneakUseOn(minecraft, position, face);
            });
            case "world.inspect" -> onClient(minecraft -> {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                BlockPos position = boundedPosition(minecraft, args);
                int radius = integer(args, "radius", 0, 3);
                return WorldInspector.inspect(minecraft, position, radius);
            });
            case "screenshot.capture" -> screenshot(string(args, "filename"));
            case "client.stop" -> stopAfterResponse();
            default -> throw new BridgeRefusal("BRIDGE_COMMAND_UNKNOWN", command);
        };
    }

    private Map<String, Object> playerCommand(Minecraft minecraft, String command) {
        ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
        if (minecraft.getConnection() == null) throw new BridgeRefusal("PLAYER_NOT_READY");
        if (command == null || command.length() > 240 || command.contains("\n")
                || command.contains("\r") || command.startsWith("/")
                || !allowedPlayerCommand(command)) {
            throw new BridgeRefusal("PLAYER_COMMAND_REFUSED", String.valueOf(command));
        }
        validateCommandPosition(minecraft, command);
        minecraft.getConnection().sendCommand(command);
        return Map.of("status", "COMMAND_SENT", "command", command);
    }

    private static boolean allowedPlayerCommand(String command) {
        return command.matches("tp @s -?[0-9]{1,8} -?[0-9]{1,8} -?[0-9]{1,8}")
                || command.matches("give @s steve_create_agent:[a-z0-9_]+( [1-9][0-9]?)?")
                || command.equals("item replace entity @s weapon.mainhand with "
                        + "steve_create_agent:engineer_terminal")
                || command.matches("setblock -?[0-9]{1,8} -?[0-9]{1,8} -?[0-9]{1,8} "
                        + "(air|chest|stone)( replace)?")
                || command.matches("item replace block -?[0-9]{1,8} -?[0-9]{1,8} "
                        + "-?[0-9]{1,8} container\\.[0-9]{1,2} with "
                        + "(immersiveengineering:(steel_scaffolding_standard|heavy_engineering|"
                        + "rs_engineering|conveyor_basic|hammer|mold_plate|thermoelectric_generator|"
                        + "connector_lv|wirecoil_copper)|minecraft:(piston|iron_ingot|blue_ice|magma_block))"
                        + "( [1-9][0-9]?)?")
                || command.matches("steveagent composite (status|cancel)")
                || command.matches("steveagent composite create \\\"[a-z0-9_.-]+:[a-z0-9/._-]+\\\" "
                        + "-?[0-9]{1,8} -?[0-9]{1,8} -?[0-9]{1,8} "
                        + "-?[0-9]{1,8} -?[0-9]{1,8} -?[0-9]{1,8} (direct|bots|hybrid)"
                        + "( -?[0-9]{1,8} -?[0-9]{1,8} -?[0-9]{1,8})?")
                || command.startsWith("industrialagent ");
    }

    private static void validateCommandPosition(Minecraft minecraft, String command) {
        String[] parts = command.split(" ");
        if (parts.length < 4 || minecraft.player == null) return;
        if (command.startsWith("steveagent composite create ")) {
            validatePosition(minecraft, parts, 4);
            validatePosition(minecraft, parts, 7);
            if (parts.length > 11) validatePosition(minecraft, parts, 11);
            return;
        }
        int offset = command.startsWith("setblock ") ? 1
                : command.startsWith("item replace block ") ? 3 : -1;
        if (offset >= 0) validatePosition(minecraft, parts, offset);
    }

    private static void validatePosition(Minecraft minecraft, String[] parts, int offset) {
        if (parts.length <= offset + 2) {
            throw new BridgeRefusal("PLAYER_COMMAND_REFUSED", "missing block position");
        }
        try {
            int x = Integer.parseInt(parts[offset]);
            int y = Integer.parseInt(parts[offset + 1]);
            int z = Integer.parseInt(parts[offset + 2]);
            if (minecraft.player.distanceToSqr(x + .5D, y + .5D, z + .5D) > 96D * 96D) {
                throw new BridgeRefusal("PLAYER_COMMAND_OUT_OF_RANGE");
            }
        } catch (NumberFormatException error) {
            throw new BridgeRefusal("PLAYER_COMMAND_REFUSED", "invalid block position");
        }
    }

    private static BlockPos boundedPosition(Minecraft minecraft, JsonObject args) {
        if (minecraft.player == null) throw new BridgeRefusal("PLAYER_NOT_READY");
        int x = integer(args, "x", -30_000_000, 30_000_000);
        int y = integer(args, "y", -2_048, 2_048);
        int z = integer(args, "z", -30_000_000, 30_000_000);
        if (minecraft.player.distanceToSqr(x + .5D, y + .5D, z + .5D) > 96D * 96D) {
            throw new BridgeRefusal("WORLD_PROBE_OUT_OF_RANGE");
        }
        return new BlockPos(x, y, z);
    }

    private static Direction direction(JsonObject args) {
        String value = string(args, "face");
        if (value == null) throw new BridgeRefusal("BLOCK_FACE_REQUIRED");
        try {
            return Direction.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new BridgeRefusal("BLOCK_FACE_INVALID", value);
        }
    }

    private static int integer(JsonObject args, String key, int minimum, int maximum) {
        JsonElement value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new BridgeRefusal("INTEGER_ARGUMENT_REQUIRED", key);
        }
        int parsed;
        try {
            parsed = value.getAsInt();
        } catch (RuntimeException error) {
            throw new BridgeRefusal("INTEGER_ARGUMENT_INVALID", key);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new BridgeRefusal("INTEGER_ARGUMENT_OUT_OF_RANGE", key);
        }
        return parsed;
    }

    private Map<String, Object> screenshot(String filename) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture<Map<String, Object>> capture = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                ClientStateProbe.requireAcceptanceWorld(minecraft, expectedWorld);
                ScreenshotCapture.capture(minecraft, filename).whenComplete((value, error) -> {
                    if (error != null) capture.completeExceptionally(error);
                    else capture.complete(value);
                });
            } catch (Exception error) {
                capture.completeExceptionally(error);
            }
        });
        return capture.get(20, TimeUnit.SECONDS);
    }

    private Map<String, Object> stopAfterResponse() {
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
        }, "steve-acceptance-client-stop");
        stopper.setDaemon(true);
        stopper.start();
        return Map.of("status", "STOP_REQUESTED");
    }

    private <T> T onClient(ClientCall<T> call) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture<T> result = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                result.complete(call.run(minecraft));
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private boolean authenticated(String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String readBoundedLine(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder line = new StringBuilder();
        for (int index = 0; index <= MAX_REQUEST_CHARS; index++) {
            int value = reader.read();
            if (value == -1) throw new IOException("request ended before newline");
            if (value == '\n') return line.toString();
            if (value == '\r') throw new IOException("carriage return refused");
            line.append((char) value);
        }
        throw new IOException("request too large");
    }

    private static void write(Socket socket, JsonObject response) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(GSON.toJson(response));
        writer.write('\n');
        writer.flush();
    }

    private static JsonObject success(String requestId, Map<String, Object> result) {
        JsonObject response = new JsonObject();
        response.addProperty("request_id", requestId);
        response.addProperty("ok", true);
        response.add("result", GSON.toJsonTree(result));
        return response;
    }

    private static JsonObject error(String requestId, String code, String detail) {
        JsonObject response = new JsonObject();
        response.addProperty("request_id", requestId);
        response.addProperty("ok", false);
        response.addProperty("code", code);
        response.addProperty("detail", detail);
        return response;
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(endpointPath);
        } catch (IOException error) {
            LOGGER.warn("Could not remove acceptance bridge endpoint {}", endpointPath, error);
        }
    }

    @FunctionalInterface
    private interface ClientCall<T> {
        T run(Minecraft minecraft) throws Exception;
    }
}
