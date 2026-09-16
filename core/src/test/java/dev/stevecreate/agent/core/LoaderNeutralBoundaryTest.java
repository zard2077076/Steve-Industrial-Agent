package dev.stevecreate.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md's highest-stakes architectural rule: the pure modules must not depend on
 * Minecraft, Forge, Create or Immersive Engineering.
 *
 * <p>The compiler already enforces most of this, and deliberately so: neither pure
 * module declares a game dependency, so an offending import fails to compile rather
 * than reaching review. The gap this closes is one level up — nothing stops someone
 * from <em>adding</em> such a dependency, and the moment they do the compiler stops
 * guarding and the boundary fails silently.</p>
 *
 * <p>So the dependency check below is the one that matters, and the import scan is
 * defence in depth for the window where a dependency exists but has not been used yet.
 * Everything downstream rests on this: the loader-neutral core is what lets planning,
 * ledgers and contracts be tested in seconds rather than by booting a dedicated server,
 * which is currently the project's main constraint on iteration speed.</p>
 */
class LoaderNeutralBoundaryTest {
    private static final List<String> PURE_MODULE_SOURCE_ROOTS = List.of(
            "src/main/java",
            "../adapter-api/src/main/java");

    /** Game-side packages. A pure module may name these as strings, never import them. */
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "net.minecraft.",
            "net.minecraftforge.",
            "com.simibubi.",
            "blusunrize.",
            "com.mojang.");

    /**
     * Coordinate fragments that would give a pure module sight of game classes. These
     * are matched only inside a dependencies block, and are deliberately specific:
     * the project's own group is {@code dev.stevecreate}, so a bare "create" matches
     * every one of its own class names.
     */
    private static final List<String> FORBIDDEN_DEPENDENCY_MARKERS = List.of(
            "net.minecraft",
            "minecraftforge",
            "forgegradle",
            "com.simibubi",
            "blusunrize",
            "curse.maven",
            "immersive-engineering",
            "fg.deobf");

    @Test
    void pureModulesNeverImportGameClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String root : PURE_MODULE_SOURCE_ROOTS) {
            Path base = Path.of(root);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> files = Files.walk(base)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scanned++;
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String trimmed = line.strip();
                        if (!trimmed.startsWith("import ")) continue;
                        String imported = trimmed.substring("import ".length())
                                .replaceFirst("^static ", "");
                        for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                            if (imported.startsWith(forbidden)) {
                                violations.add(file + " -> " + trimmed);
                            }
                        }
                    }
                }
            }
        }

        assertThat(scanned)
                .as("boundary scan found no sources; the roots are wrong, not the code")
                .isGreaterThan(200);
        assertThat(violations)
                .as("a pure module imported a game class, which breaks loader neutrality")
                .isEmpty();
    }

    /**
     * The check the compiler cannot make for us. A game dependency here would not break
     * any build; it would quietly turn every offending import into legal code.
     */
    @Test
    void pureModulesNeverDeclareAGameDependency() throws IOException {
        int blocksScanned = 0;
        List<String> violations = new ArrayList<>();
        for (String buildFile : List.of("build.gradle", "../adapter-api/build.gradle")) {
            Path path = Path.of(buildFile);
            if (!Files.isRegularFile(path)) continue;
            String script = Files.readString(path, StandardCharsets.UTF_8);
            if (script.contains("dependencies")) blocksScanned++;
            forbiddenDependencyLines(script)
                    .forEach(line -> violations.add(buildFile + " -> " + line));
        }

        assertThat(blocksScanned)
                .as("found no dependencies block; the build files moved, not the rule")
                .isEqualTo(2);
        assertThat(violations)
                .as("a pure module declared a game dependency, which silently removes the "
                        + "compiler's enforcement of loader neutrality")
                .isEmpty();
    }

    /**
     * Both scans must be able to fail, or they are decoration.
     *
     * <p>This exercises the scanner rather than the real build files, because actually
     * adding a game dependency makes the module fail to resolve before any test runs —
     * the failure would prove the classpath is clean, not that the scan works.</p>
     */
    @Test
    void bothScansRecogniseTheirOwnViolation() {
        assertThat(FORBIDDEN_IMPORT_PREFIXES).anyMatch(prefix ->
                "net.minecraft.world.level.Level;".startsWith(prefix));

        assertThat(forbiddenDependencyLines("""
                dependencies {
                    testImplementation 'org.assertj:assertj-core:3.25.3'
                    compileOnly 'net.minecraftforge:forge:1.20.1-47.4.6'
                }
                """)).hasSize(1);
        // The project's own group contains "create"; it must not trip the scan.
        assertThat(forbiddenDependencyLines("""
                dependencies {
                    implementation project(':core')
                }
                mainClass = 'dev.stevecreate.agent.core.survey.FormalSurveyAcceptanceMain'
                """)).isEmpty();
    }

    /** Forbidden coordinates inside a dependencies block, ignoring the rest of a script. */
    private static List<String> forbiddenDependencyLines(String buildScript) {
        List<String> found = new ArrayList<>();
        int depth = 0;
        for (String line : buildScript.split("\n")) {
            String trimmed = line.strip();
            if (depth == 0) {
                if (trimmed.startsWith("dependencies")) depth = 1;
                continue;
            }
            depth += trimmed.chars().filter(character -> character == '{').count()
                    - trimmed.chars().filter(character -> character == '}').count();
            if (depth <= 0) { depth = 0; continue; }
            if (trimmed.startsWith("//")) continue;
            String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_DEPENDENCY_MARKERS.stream().anyMatch(lower::contains)) found.add(trimmed);
        }
        return found;
    }
}
