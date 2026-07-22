package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical-path and fingerprint-evidence classifier. Unknown, formal and forbidden results fail closed. */
public final class WorldEnvironmentClassifier {
    public WorldEnvironmentDescriptor classify(
            WorldEnvironmentEvidence source,
            WorldEnvironmentPolicy policy) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(policy, "policy");
        CanonicalWorldPaths.Resolution gameResolution =
                CanonicalWorldPaths.resolve(source.gameDirectory(), policy.verifiedAliases());
        CanonicalWorldPaths.Resolution worldResolution =
                CanonicalWorldPaths.resolve(source.worldRoot(), policy.verifiedAliases());
        Path gameDirectory = gameResolution.path();
        Path worldRoot = worldResolution.path();
        boolean forbidden = policy.forbiddenRoots().stream().anyMatch(root ->
                CanonicalWorldPaths.within(gameDirectory, root) || CanonicalWorldPaths.within(worldRoot, root));
        boolean gameAllowed = policy.allowedRoots().stream().anyMatch(root ->
                CanonicalWorldPaths.within(gameDirectory, root));
        boolean worldInsideGame = CanonicalWorldPaths.within(worldRoot, gameDirectory);

        List<String> evidence = new ArrayList<>(source.classificationEvidence());
        List<String> warnings = new ArrayList<>();
        WorldEnvironmentType type;
        if (forbidden) {
            type = WorldEnvironmentType.FORBIDDEN_WORLD;
            evidence.add("canonical gameDir or world root matched an explicit forbidden root");
            warnings.add("forbidden canonical root matched");
        } else if (!gameResolution.verified() || !worldResolution.verified()) {
            type = WorldEnvironmentType.UNKNOWN_WORLD;
            evidence.add("canonical gameDir and world-root identities were not both verified");
            warnings.add("canonical path identity could not be verified");
        } else if (!gameAllowed) {
            type = WorldEnvironmentType.UNKNOWN_WORLD;
            evidence.add("gameDir is outside every allowed canonical root");
            warnings.add("world is outside every allowed canonical root");
        } else if (!worldInsideGame) {
            type = WorldEnvironmentType.UNKNOWN_WORLD;
            evidence.add("world root is outside the canonical gameDir");
            warnings.add("gameDir and world root identity mismatch");
        } else {
            evidence.add("gameDir is inside an allowed canonical root");
            evidence.add("world root is inside the canonical gameDir");
            type = acceptedType(source, warnings);
        }

        boolean safeType = type == WorldEnvironmentType.ISOLATED_TEST_WORLD
                || type == WorldEnvironmentType.ISOLATED_PACK_WORLD
                || type == WorldEnvironmentType.DEVELOPMENT_WORLD;
        boolean writable = safeType && gameAllowed && worldInsideGame && source.writableRequested();
        boolean executionAllowed = writable && policy.isolatedExecutionEnabled()
                && (type == WorldEnvironmentType.ISOLATED_TEST_WORLD
                || type == WorldEnvironmentType.ISOLATED_PACK_WORLD
                || type == WorldEnvironmentType.DEVELOPMENT_WORLD);
        boolean backupRequired = !source.disposable() || !safeType;
        boolean humanApprovalRequired = !source.disposable() || !safeType;
        return new WorldEnvironmentDescriptor(
                source.environmentId(), type, source.worldIdentity(),
                CanonicalWorldPaths.identity(worldRoot), CanonicalWorldPaths.identity(gameDirectory),
                source.minecraftVersion(), source.loaderAndModFingerprint(), source.runtimeFingerprint(),
                source.saveFingerprint(), source.serverIdentity(), source.disposable(), writable,
                backupRequired, humanApprovalRequired, executionAllowed, source.provenance(), evidence, warnings);
    }

    private static WorldEnvironmentType acceptedType(
            WorldEnvironmentEvidence source,
            List<String> warnings) {
        return switch (source.intendedType()) {
            case ISOLATED_TEST_WORLD, ISOLATED_PACK_WORLD -> {
                if (!source.disposable()) {
                    warnings.add("isolated classification requires a disposable world");
                    yield WorldEnvironmentType.UNKNOWN_WORLD;
                }
                yield source.intendedType();
            }
            case DEVELOPMENT_WORLD -> WorldEnvironmentType.DEVELOPMENT_WORLD;
            case FORMAL_PLAYER_WORLD -> {
                warnings.add("formal player world remains non-executable");
                yield WorldEnvironmentType.FORMAL_PLAYER_WORLD;
            }
            case FORBIDDEN_WORLD -> WorldEnvironmentType.FORBIDDEN_WORLD;
            case UNKNOWN_WORLD -> WorldEnvironmentType.UNKNOWN_WORLD;
        };
    }
}
