package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.diagnostics.ReleaseDiagnostics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import dev.stevecreate.agent.core.diagnostics.ReleaseCompatibility;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

/** Player-facing, offline release information and explicitly allowlisted diagnostics. */
public final class ReleaseInfoCommand {
    private static final Properties RELEASE = loadRelease();

    private ReleaseInfoCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("version").executes(context -> version(context.getSource())));
        root.then(Commands.literal("compatibility").executes(
                context -> compatibility(context.getSource())));
        root.then(Commands.literal("diagnostics").executes(
                context -> diagnostics(context.getSource())));
        root.then(Commands.literal("export-diagnostics").executes(
                context -> exportDiagnostics(context.getSource())));
    }

    private static int version(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Steve Industrial Agent "
                + release("version") + " git=" + shortCommit()
                + " minecraft=" + release("minecraft") + " forge=" + release("forge")
                + " create=" + release("create")), false);
        return 1;
    }

    private static int compatibility(CommandSourceStack source) {
        String minecraft = SharedConstants.getCurrentVersion().getName();
        String forge = modVersion("forge");
        String create = modVersion("create");
        ReleaseCompatibility.Result result = ReleaseCompatibility.evaluate(minecraft, forge, create);
        String message = result.message();
        if (result.compatible()) source.sendSuccess(() -> Component.literal(message), false);
        else source.sendFailure(Component.literal(message
                + " — install the documented versions in a disposable test instance"));
        return result.compatible() ? 1 : 0;
    }

    private static int diagnostics(CommandSourceStack source) {
        Map<String, String> facts = facts(source);
        source.sendSuccess(() -> Component.literal("Diagnostics PASS version="
                + facts.get("version") + " compatibilityCommand=/industrialagent compatibility"
                + " network=false telemetry=false privateFields=redacted"), false);
        return 1;
    }

    private static int exportDiagnostics(CommandSourceStack source) {
        try {
            byte[] archive = ReleaseDiagnostics.archive(facts(source));
            Path directory = Path.of(System.getProperty("user.dir"), "industrialagent-diagnostics")
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path output = directory.resolve("steve-industrial-agent-diagnostics.zip");
            Files.write(output, archive, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            source.sendSuccess(() -> Component.literal(
                    "Diagnostics exported to industrialagent-diagnostics/"
                    + output.getFileName() + "; upload is manual; private fields redacted"), false);
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.literal(
                    "Diagnostics export failed: cannot write the fixed diagnostics directory"));
            return 0;
        }
    }

    private static Map<String, String> facts(CommandSourceStack source) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("version", release("version"));
        facts.put("gitCommit", release("gitCommit"));
        facts.put("minecraft", SharedConstants.getCurrentVersion().getName());
        facts.put("forge", modVersion("forge"));
        facts.put("create", modVersion("create"));
        facts.put("java", System.getProperty("java.version", "unavailable"));
        facts.put("os", System.getProperty("os.name", "unavailable"));
        facts.put("network", "false");
        facts.put("telemetry", "false");
        facts.put("dimension", source.getLevel().dimension().location().toString());
        facts.put("worldClassification", "runtime-check-required");
        return facts;
    }

    private static String modVersion(String id) {
        return ModList.get().getModContainerById(id)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("not-installed");
    }

    private static String release(String key) {
        return RELEASE.getProperty(key, "unavailable");
    }

    private static String shortCommit() {
        String commit = release("gitCommit");
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    private static Properties loadRelease() {
        Properties properties = new Properties();
        try (InputStream input = ReleaseInfoCommand.class.getClassLoader()
                .getResourceAsStream("steve-industrial-agent-release.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException ignored) {
            // Missing build metadata remains the explicit "unavailable" typed display state.
        }
        return properties;
    }
}
