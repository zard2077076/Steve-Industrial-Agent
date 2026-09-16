package dev.stevecreate.agent.forge1201.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Exact repository-owned GameTest path proof; absence or drift always disables the Bot worker. */
final class TestOnlyBotWorldGuard {
    static final String ENABLED = "steve_industrial.test.botFleetGameTest";
    static final String EXPECTED_GAME_DIR = "steve_industrial.bot.expectedGameDir";
    static final String FORBIDDEN_ROOT = "steve_industrial.bot.forbiddenRoot";
    static final String MARKER = ".steve-industrial-bot-test";
    static final String MARKER_VALUE = "steve-industrial:isolated-bot/v1";

    private TestOnlyBotWorldGuard() {}

    static void requireAuthorized(ServerLevel level) {
        if (!Boolean.getBoolean(ENABLED)) {
            throw new IllegalStateException("Test-only Bot worker property is disabled");
        }
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Test-only Bot worker requires the authoritative server thread");
        }
        try {
            String expectedValue = requiredProperty(EXPECTED_GAME_DIR);
            String forbiddenValue = requiredProperty(FORBIDDEN_ROOT);
            Path actual = Path.of(System.getProperty("user.dir")).toRealPath();
            Path expected = Path.of(expectedValue).toRealPath();
            Path forbidden = Path.of(forbiddenValue).toRealPath();
            if (!actual.equals(expected) || actual.startsWith(forbidden)) {
                throw new IllegalStateException(
                        "Actual game directory is not the exact repository-owned Bot fixture");
            }
            Path marker = actual.resolve(MARKER);
            if (!Files.isRegularFile(marker)
                    || !MARKER_VALUE.equals(Files.readString(
                    marker, StandardCharsets.US_ASCII).trim())) {
                throw new IllegalStateException("Bot fixture marker is missing or invalid");
            }
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT)
                    .toRealPath();
            if (!worldRoot.startsWith(actual)) {
                throw new IllegalStateException("Bot fixture world is outside its exact game directory");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Bot fixture path proof failed closed", exception);
        }
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required Bot fixture property is absent: " + key);
        }
        return value;
    }
}
