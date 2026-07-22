package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldEnvironmentClassifierTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void classifiesRepositoryOwnedDisposableWorldWithCompleteEvidence() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path gameDirectory = Files.createDirectories(repository.resolve("forge/run/pw01"));
        Path worldRoot = Files.createDirectories(gameDirectory.resolve("world"));
        WorldEnvironmentPolicy policy = policy(repository, temporaryRoot.resolve("formal"));

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(gameDirectory, worldRoot, WorldEnvironmentType.ISOLATED_TEST_WORLD, true), policy);

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.ISOLATED_TEST_WORLD);
        assertThat(descriptor.disposable()).isTrue();
        assertThat(descriptor.writable()).isTrue();
        assertThat(descriptor.backupRequired()).isFalse();
        assertThat(descriptor.humanApprovalRequired()).isFalse();
        assertThat(descriptor.executionAllowed()).isTrue();
        assertThat(descriptor.classificationEvidence()).contains(
                "gameDir is inside an allowed canonical root",
                "world root is inside the canonical gameDir");
    }

    @Test
    void formalRootAndEveryDescendantArePermanentlyNonExecutable() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path formal = Files.createDirectory(temporaryRoot.resolve("formal"));
        Path gameDirectory = Files.createDirectories(formal.resolve(".minecraft/versions/pack"));
        Path worldRoot = Files.createDirectories(gameDirectory.resolve("saves/player-world"));

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(gameDirectory, worldRoot, WorldEnvironmentType.ISOLATED_TEST_WORLD, true),
                policy(repository, formal));

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.FORBIDDEN_WORLD);
        assertThat(descriptor.writable()).isFalse();
        assertThat(descriptor.executionAllowed()).isFalse();
        assertThat(descriptor.backupRequired()).isTrue();
        assertThat(descriptor.humanApprovalRequired()).isTrue();
        assertThat(descriptor.warnings()).contains("forbidden canonical root matched");
    }

    @Test
    void unknownWorldFailsClosedInsteadOfTrustingDirectoryNames() throws IOException {
        Path gameDirectory = Files.createDirectory(temporaryRoot.resolve("isolated-test-world"));
        Path worldRoot = Files.createDirectory(gameDirectory.resolve("world"));
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(gameDirectory, worldRoot, WorldEnvironmentType.ISOLATED_TEST_WORLD, true),
                policy(repository, temporaryRoot.resolve("formal")));

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.UNKNOWN_WORLD);
        assertThat(descriptor.executionAllowed()).isFalse();
        assertThat(descriptor.writable()).isFalse();
        assertThat(descriptor.warnings()).contains("world is outside every allowed canonical root");
    }

    @Test
    void normalizedTraversalCannotEscapeAnAllowedRoot() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path outside = Files.createDirectory(temporaryRoot.resolve("outside"));
        Path worldRoot = Files.createDirectory(outside.resolve("world"));
        Path traversal = repository.resolve("subdirectory/../../outside");

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(traversal, worldRoot, WorldEnvironmentType.DEVELOPMENT_WORLD, false),
                policy(repository, temporaryRoot.resolve("formal")));

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.UNKNOWN_WORLD);
        assertThat(descriptor.executionAllowed()).isFalse();
    }

    @Test
    void symbolicLinkIntoForbiddenRootCannotMasqueradeAsIsolated() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path formal = Files.createDirectory(temporaryRoot.resolve("formal"));
        Path formalGame = Files.createDirectory(formal.resolve("game"));
        Path formalWorld = Files.createDirectory(formalGame.resolve("world"));
        Path link = repository.resolve("linked-game");
        try {
            Files.createSymbolicLink(link, formalGame);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            WorldEnvironmentPolicy aliasedPolicy = new WorldEnvironmentPolicy(
                    Set.of(repository), Set.of(formal), Map.of(link, formalGame), true);
            WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                    evidence(link, link.resolve("world"), WorldEnvironmentType.ISOLATED_TEST_WORLD, true),
                    aliasedPolicy);
            assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.FORBIDDEN_WORLD);
            assertThat(descriptor.executionAllowed()).isFalse();
            return;
        }

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(link, formalWorld, WorldEnvironmentType.ISOLATED_TEST_WORLD, true),
                policy(repository, formal));
        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.FORBIDDEN_WORLD);
        assertThat(descriptor.executionAllowed()).isFalse();
    }

    @Test
    void mismatchedGameDirectoryAndSaveRootAreUnknown() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path gameDirectory = Files.createDirectory(repository.resolve("game"));
        Path differentGame = Files.createDirectory(repository.resolve("other-game"));
        Path worldRoot = Files.createDirectory(differentGame.resolve("world"));

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(gameDirectory, worldRoot, WorldEnvironmentType.DEVELOPMENT_WORLD, false),
                policy(repository, temporaryRoot.resolve("formal")));

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.UNKNOWN_WORLD);
        assertThat(descriptor.classificationEvidence()).contains("world root is outside the canonical gameDir");
        assertThat(descriptor.executionAllowed()).isFalse();
    }

    @Test
    void unresolvedAllowedPathsFailClosed() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path missingGame = repository.resolve("missing-game");
        Path missingWorld = missingGame.resolve("world");

        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(missingGame, missingWorld, WorldEnvironmentType.ISOLATED_TEST_WORLD, true),
                policy(repository, temporaryRoot.resolve("formal")));

        assertThat(descriptor.environmentType()).isEqualTo(WorldEnvironmentType.UNKNOWN_WORLD);
        assertThat(descriptor.executionAllowed()).isFalse();
        assertThat(descriptor.warnings()).contains("canonical path identity could not be verified");
    }

    @Test
    void evidenceAndPolicyAreBoundedAndImmutable() throws IOException {
        Path repository = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path gameDirectory = Files.createDirectory(repository.resolve("game"));
        Path worldRoot = Files.createDirectory(gameDirectory.resolve("world"));
        WorldEnvironmentDescriptor descriptor = new WorldEnvironmentClassifier().classify(
                evidence(gameDirectory, worldRoot, WorldEnvironmentType.DEVELOPMENT_WORLD, false),
                policy(repository, temporaryRoot.resolve("formal")));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> descriptor.warnings().add("changed"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new WorldEnvironmentEvidence(
                        "", "world", gameDirectory, worldRoot, "1.20.1", "forge:47.4.10",
                        "runtime", "save", "server", WorldEnvironmentType.DEVELOPMENT_WORLD,
                        false, true, "test", List.of("evidence")));
    }

    private static WorldEnvironmentPolicy policy(Path repository, Path formal) {
        return new WorldEnvironmentPolicy(Set.of(repository), Set.of(formal), Map.of(), true);
    }

    private static WorldEnvironmentEvidence evidence(
            Path gameDirectory,
            Path worldRoot,
            WorldEnvironmentType intendedType,
            boolean disposable) {
        return new WorldEnvironmentEvidence(
                "pw01-environment",
                "world-identity",
                gameDirectory,
                worldRoot,
                "1.20.1",
                "forge:47.4.10|create:6.0.6",
                "sha256:runtime",
                "sha256:save",
                "dedicated:test-server",
                intendedType,
                disposable,
                true,
                "explicit-test-fixture",
                List.of("marker fingerprint verified", "server identity verified"));
    }
}
