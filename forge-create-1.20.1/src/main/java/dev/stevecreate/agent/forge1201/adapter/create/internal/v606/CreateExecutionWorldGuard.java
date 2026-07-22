package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.forge1201.command.PublicAlphaRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Fail-closed path proof for the only worlds where goal-driven mutation is permitted. */
final class CreateExecutionWorldGuard {
    static final String EXPECTED_GAME_DIR = "steve_industrial.execution.expectedGameDir";
    static final String FORBIDDEN_ROOT = "steve_industrial.execution.forbiddenRoot";
    static final String MARKER = ".steve-industrial-execution-test";
    static final String MARKER_VALUE = "steve-industrial:isolated-execution/v1";

    private CreateExecutionWorldGuard() {}

    static GuardResult verify(
            ServerLevel level,
            ExecutionWorldClassification requested) {
        if (requested != ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST) {
            return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                    "Execution classification is not an isolated repository test world");
        }
        PublicAlphaRuntime.Result publicResult = PublicAlphaRuntime.resolve(level, true);
        if (publicResult instanceof PublicAlphaRuntime.Success success) {
            return new GuardSuccess(success.authorization().gameDir(),
                    success.authorization().worldRoot());
        }
        try {
            String expectedValue = System.getProperty(EXPECTED_GAME_DIR);
            String forbiddenValue = System.getProperty(FORBIDDEN_ROOT);
            if (expectedValue == null || expectedValue.isBlank()
                    || forbiddenValue == null || forbiddenValue.isBlank()) {
                return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                        "Isolated execution path properties are absent");
            }
            Path actual = Path.of(System.getProperty("user.dir")).toRealPath();
            Path expected = Path.of(expectedValue).toRealPath();
            Path forbidden = Path.of(forbiddenValue).toRealPath();
            if (!actual.equals(expected) || actual.startsWith(forbidden)) {
                return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                        "Actual gameDir is not the exact permitted isolated directory");
            }
            Path marker = actual.resolve(MARKER);
            if (!Files.isRegularFile(marker)
                    || !MARKER_VALUE.equals(Files.readString(marker, StandardCharsets.US_ASCII).trim())) {
                return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                        "Isolated execution marker is missing or invalid");
            }
            Path world = level.getServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            if (!world.startsWith(actual)) {
                return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                        "World root is outside the permitted isolated gameDir");
            }
            return new GuardSuccess(actual, world);
        } catch (IOException | RuntimeException exception) {
            return new GuardFailure(ExecutionReadinessFailureCode.FORMAL_WORLD_FORBIDDEN,
                    "Isolated execution path proof failed closed: " + exception.getMessage());
        }
    }

    static Optional<String> mutationFailure(ServerLevel level) {
        GuardResult result = verify(level, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST);
        return result instanceof GuardFailure failure ? Optional.of(failure.detail()) : Optional.empty();
    }

    sealed interface GuardResult permits GuardSuccess, GuardFailure {}
    record GuardSuccess(Path gameDir, Path worldRoot) implements GuardResult {}
    record GuardFailure(ExecutionReadinessFailureCode code, String detail) implements GuardResult {}
}
