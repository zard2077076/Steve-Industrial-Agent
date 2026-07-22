package dev.stevecreate.agent.core.deployment;

import static dev.stevecreate.agent.core.deployment.WritableTestWorldFailure.UNAVAILABLE;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Rechecks exact identity/path/marker/fingerprint before every pilot boundary. */
public final class WritableTestWorldGuard {
    public WritableTestWorldGuardResult check(
            WritableTestWorldIdentity identity,
            WritableTestWorldPolicy policy,
            WritableTestWorldGuardRequest request) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        Path game = CanonicalWorldPaths.canonical(request.currentGameDirectory());
        Path world = CanonicalWorldPaths.canonical(request.currentWorldPath());

        if (CanonicalWorldPaths.within(game, policy.formalPlatformRoot())
                || CanonicalWorldPaths.within(world, policy.formalPlatformRoot())
                || policy.formalWorldIdentities().contains(request.currentSourceWorldIdentity())) {
            return refused(WritableTestWorldFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                    identity, request, game, world,
                    "The current world intersects formal identity or path policy",
                    "Return to the dedicated SteveAgent_DeceasedCraft_Test instance");
        }
        if (!identity.sourceWorldIdentity().equals(request.currentSourceWorldIdentity())
                || !identity.canonicalGameDirectory().equals(game)
                || !identity.canonicalWorldPath().equals(world)
                || !identity.testInstanceIdentity().equals(policy.expectedTestInstanceIdentity())
                || !WritableTestWorldIdentity.REQUIRED_MARKER.equals(request.currentMarker())) {
            return refused(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    identity, request, game, world,
                    "Current world identity, path, instance, or marker differs from discovery",
                    "Rediscover the unique marked test world; do not continue this session");
        }
        if (!identity.worldFingerprint().equals(request.currentWorldFingerprint())) {
            return refused(WritableTestWorldFailureCode.TEST_WORLD_FINGERPRINT_STALE,
                    identity, request, game, world,
                    "Current world fingerprint differs from the bound identity",
                    "Cancel the stale operation and create a fresh preview from a fresh fingerprint");
        }
        if (request.operation() == WritableTestWorldOperation.BACKUP
                && request.currentOccupancy() != WritableTestWorldOccupancy.CLOSED) {
            return refused(WritableTestWorldFailureCode.TEST_WORLD_IN_USE_DURING_BACKUP,
                    identity, request, game, world,
                    "Backup requires a fully closed Minecraft world",
                    "Save, exit, fully close Minecraft, and retry the test-world backup");
        }
        return WritableTestWorldGuardResult.success();
    }

    private static WritableTestWorldGuardResult refused(
            WritableTestWorldFailureCode code,
            WritableTestWorldIdentity identity,
            WritableTestWorldGuardRequest request,
            Path game,
            Path world,
            String reason,
            String next) {
        return WritableTestWorldGuardResult.refused(new WritableTestWorldFailure(
                code, request.operation() == WritableTestWorldOperation.BACKUP
                        ? WritableTestWorldStage.BACKUP : WritableTestWorldStage.IDENTITY,
                identity.testInstanceIdentity(), request.currentSourceWorldIdentity(),
                CanonicalWorldPaths.identity(world), request.currentWorldFingerprint(),
                UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE,
                List.of("expectedGameDir=" + CanonicalWorldPaths.identity(identity.canonicalGameDirectory()),
                        "currentGameDir=" + CanonicalWorldPaths.identity(game),
                        "expectedWorldPath=" + CanonicalWorldPaths.identity(identity.canonicalWorldPath()),
                        "currentWorldPath=" + CanonicalWorldPaths.identity(world),
                        "operation=" + request.operation()), reason, next));
    }
}
