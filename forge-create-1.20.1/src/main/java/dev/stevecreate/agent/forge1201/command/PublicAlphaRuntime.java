package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.forge1201.runtime.PublicAlphaConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** One fail-closed production authority shared by setup, backup, pilot, and every write guard. */
public final class PublicAlphaRuntime {
    private static final String PREFIX = "steve_industrial.runtime.";
    private static final String ENV_PREFIX = "STEVE_INDUSTRIAL_";

    private PublicAlphaRuntime() {}

    public static Result resolve(ServerLevel level, boolean requireMarker) {
        try {
            int configuredSchema = integer("configSchemaVersion", "CONFIG_SCHEMA_VERSION",
                    PublicAlphaConfig.SCHEMA_VERSION.get());
            int schema = configuredSchema == 0 ? PublicAlphaConfig.CURRENT_SCHEMA : configuredSchema;
            if (schema != PublicAlphaConfig.CURRENT_SCHEMA) return failure("CONFIG_SCHEMA_UNSUPPORTED");
            String mode = string("mode", "RUNTIME_MODE", PublicAlphaConfig.RUNTIME_MODE.get());
            if (!"DISPOSABLE_TEST_WORLD".equalsIgnoreCase(mode)) {
                return failure("RUNTIME_MODE_SETUP_REQUIRED");
            }
            if (!bool("allowDirectPilot", "ALLOW_DIRECT_PILOT", PublicAlphaConfig.ALLOW_DIRECT_PILOT.get())) {
                return failure("DIRECT_PILOT_DISABLED");
            }
            if (!bool("diagnosticsRedaction", "DIAGNOSTICS_REDACTION",
                    PublicAlphaConfig.DIAGNOSTICS_REDACTION.get())) {
                return failure("DIAGNOSTICS_REDACTION_REQUIRED");
            }
            if (!"DENY_CONFIGURED_ROOTS".equalsIgnoreCase(string("formalWorldPolicy",
                    "FORMAL_WORLD_POLICY", PublicAlphaConfig.FORMAL_WORLD_POLICY.get()))) {
                return failure("FORMAL_WORLD_POLICY_UNSAFE");
            }

            Path actualGame = canonicalExisting(Path.of(System.getProperty("user.dir")), "GAME_DIR");
            String configuredInstance = string("testInstanceRoot", "TEST_INSTANCE_ROOT",
                    PublicAlphaConfig.TEST_INSTANCE_ROOT.get());
            if (configuredInstance.isBlank()) return failure("TEST_INSTANCE_ROOT_NOT_CONFIGURED");
            Path instance = canonicalExisting(Path.of(configuredInstance), "TEST_INSTANCE_ROOT");
            if (!actualGame.equals(instance)) return failure("TEST_INSTANCE_ROOT_MISMATCH");
            if (hasGitAncestor(actualGame)) return failure("SOURCE_TREE_GAME_DIR_REFUSED");

            List<String> allowed = list("allowedTestWorlds", "ALLOWED_TEST_WORLDS",
                    PublicAlphaConfig.ALLOWED_TEST_WORLDS.get());
            String levelName = level.getServer().getWorldData().getLevelName();
            if (allowed.isEmpty() || allowed.stream().noneMatch(levelName::equals)) {
                return failure("CURRENT_WORLD_NOT_ALLOWLISTED");
            }
            Path world = canonicalExisting(level.getServer().getWorldPath(LevelResource.ROOT), "WORLD_ROOT");
            Path saves = canonicalExisting(actualGame.resolve("saves"), "SAVES_ROOT");
            if (!world.startsWith(saves) || world.equals(saves)) return failure("WORLD_OUTSIDE_TEST_INSTANCE");

            List<Path> importantRoots = new ArrayList<>();
            for (String raw : list("importantInstanceRoots", "IMPORTANT_INSTANCE_ROOTS",
                    PublicAlphaConfig.IMPORTANT_INSTANCE_ROOTS.get())) {
                importantRoots.add(canonicalExisting(Path.of(raw), "IMPORTANT_INSTANCE_ROOT"));
            }
            if (importantRoots.isEmpty()) return failure("IMPORTANT_INSTANCE_ROOT_REQUIRED");
            for (Path root : importantRoots) {
                if (actualGame.startsWith(root) || root.startsWith(actualGame)
                        || world.startsWith(root) || root.startsWith(world)) {
                    return failure("FORMAL_WORLD_EXECUTION_FORBIDDEN");
                }
            }

            String backupValue = string("backupRoot", "BACKUP_ROOT", PublicAlphaConfig.BACKUP_ROOT.get());
            Path backup = backupValue.isBlank() ? defaultBackupRoot() : Path.of(backupValue);
            backup = canonicalFuture(backup, "BACKUP_ROOT");
            if (backup.startsWith(actualGame) || actualGame.startsWith(backup)
                    || hasGitAncestor(backup)) return failure("BACKUP_ROOT_UNSAFE");
            for (Path root : importantRoots) {
                if (backup.startsWith(root) || root.startsWith(backup)) return failure("BACKUP_ROOT_FORMAL_OVERLAP");
            }

            boolean backupRequired = bool("requireBackup", "REQUIRE_BACKUP", PublicAlphaConfig.REQUIRE_BACKUP.get());
            boolean markerRequired = bool("requireWorldMarker", "REQUIRE_WORLD_MARKER",
                    PublicAlphaConfig.REQUIRE_WORLD_MARKER.get());
            if (!backupRequired || !markerRequired) return failure("PUBLIC_ALPHA_SAFETY_GATES_REQUIRED");
            int maxRegion = integer("maxRegionSize", "MAX_REGION_SIZE", PublicAlphaConfig.MAX_REGION_SIZE.get());
            if (maxRegion < 8 || maxRegion > 64) return failure("MAX_REGION_SIZE_INVALID");

            String worldIdentity = worldIdentity(level, world);
            Optional<PilotWorldMarkerSavedData.Marker> marker =
                    PilotWorldMarkerSavedData.forLevel(level).marker();
            if (requireMarker && (marker.isEmpty()
                    || !marker.orElseThrow().worldIdentity().equals(worldIdentity))) {
                return failure("TEST_WORLD_MARKER_REQUIRED");
            }
            return new Success(new Authorization(schema, configuredSchema == 0, actualGame, world,
                    backup, List.copyOf(importantRoots), levelName, worldIdentity,
                    marker.map(PilotWorldMarkerSavedData.Marker::generation).orElse("unmarked"),
                    maxRegion, backupRequired, markerRequired));
        } catch (Exception failure) {
            return failure("CONFIG_VALIDATION_FAILED:" + safe(failure.getMessage()));
        }
    }

    public static Optional<String> writeFailure(ServerLevel level) {
        Result result = resolve(level, true);
        return result instanceof Failure failure ? Optional.of(failure.code()) : Optional.empty();
    }

    public static String worldIdentity(ServerLevel level) throws IOException {
        return worldIdentity(level, canonicalExisting(
                level.getServer().getWorldPath(LevelResource.ROOT), "WORLD_ROOT"));
    }

    public static String summary() {
        int schema = PublicAlphaConfig.SCHEMA_VERSION.get();
        return "schema=" + schema + " mode=" + PublicAlphaConfig.RUNTIME_MODE.get()
                + " instanceConfigured=" + !PublicAlphaConfig.TEST_INSTANCE_ROOT.get().isBlank()
                + " allowedWorlds=" + PublicAlphaConfig.ALLOWED_TEST_WORLDS.get().size()
                + " backupConfigured=" + !PublicAlphaConfig.BACKUP_ROOT.get().isBlank()
                + " importantRoots=" + PublicAlphaConfig.IMPORTANT_INSTANCE_ROOTS.get().size()
                + " requireBackup=" + PublicAlphaConfig.REQUIRE_BACKUP.get()
                + " requireWorldMarker=" + PublicAlphaConfig.REQUIRE_WORLD_MARKER.get()
                + " allowDirectPilot=" + PublicAlphaConfig.ALLOW_DIRECT_PILOT.get()
                + " diagnosticsRedaction=" + PublicAlphaConfig.DIAGNOSTICS_REDACTION.get()
                + " paths=redacted";
    }

    private static String worldIdentity(ServerLevel level, Path world) {
        return "world:" + sha256(level.getServer().getWorldData().getLevelName() + "\n" + key(world));
    }

    private static Path defaultBackupRoot() {
        String appData = System.getenv("APPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".steve-industrial-agent")
                : Path.of(appData, "SteveIndustrialAgent");
        return base.resolve("backups");
    }

    private static Path canonicalExisting(Path input, String name) throws IOException {
        Path absolute = input.toAbsolutePath().normalize();
        rejectReparseAncestors(absolute, name);
        return absolute.toRealPath();
    }

    private static Path canonicalFuture(Path input, String name) throws IOException {
        Path absolute = input.toAbsolutePath().normalize();
        Path current = absolute;
        List<Path> suffix = new ArrayList<>();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(0, current.getFileName());
            current = current.getParent();
        }
        if (current == null) throw new IOException(name + " has no existing ancestor");
        rejectReparseAncestors(current, name);
        Path resolved = current.toRealPath();
        for (Path segment : suffix) resolved = resolved.resolve(segment);
        return resolved.normalize();
    }

    private static void rejectReparseAncestors(Path path, String name) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current)
                    || Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).isOther())) {
                throw new IOException(name + " reparse ancestor refused");
            }
            current = current.getParent();
        }
    }

    private static boolean hasGitAncestor(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return true;
            current = current.getParent();
        }
        return false;
    }

    private static String string(String property, String environment, String fallback) {
        String value = System.getProperty(PREFIX + property);
        if (value == null || value.isBlank()) value = System.getenv(ENV_PREFIX + environment);
        return value == null || value.isBlank() ? fallback.trim() : value.trim();
    }

    private static boolean bool(String property, String environment, boolean fallback) {
        String value = string(property, environment, Boolean.toString(fallback));
        return "true".equalsIgnoreCase(value);
    }

    private static int integer(String property, String environment, int fallback) {
        return Integer.parseInt(string(property, environment, Integer.toString(fallback)));
    }

    private static List<String> list(
            String property, String environment, List<? extends String> fallback) {
        String override = System.getProperty(PREFIX + property);
        if (override == null || override.isBlank()) override = System.getenv(ENV_PREFIX + environment);
        if (override != null && !override.isBlank()) {
            return java.util.Arrays.stream(override.split(";"))
                    .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        }
        return fallback.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static Failure failure(String code) {
        return new Failure(code);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        return value.replaceAll("(?i)[a-z]:[\\\\/][^ ]+", "[REDACTED_PATH]")
                .replaceAll("/(?:home|users)/[^ ]+", "[REDACTED_PATH]");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public sealed interface Result permits Success, Failure {}
    public record Success(Authorization authorization) implements Result {}
    public record Failure(String code) implements Result {}
    public record Authorization(
            int schemaVersion,
            boolean migratedFromV0,
            Path gameDir,
            Path worldRoot,
            Path backupRoot,
            List<Path> importantRoots,
            String worldName,
            String worldIdentity,
            String markerGeneration,
            int maxRegionSize,
            boolean backupRequired,
            boolean markerRequired) {}
}
