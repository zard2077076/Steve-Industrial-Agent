package dev.stevecreate.agent.core.deployment;

import static dev.stevecreate.agent.core.deployment.WritableTestWorldFailure.UNAVAILABLE;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Fail-closed unique discovery. It never opens world content or accepts a caller-selected path. */
public final class WritableTestWorldDiscovery {
    public WritableTestWorldDiscoveryResult discover(
            List<WritableTestWorldCandidate> candidates,
            WritableTestWorldPolicy policy) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(policy, "policy");
        if (candidates.size() > 64 || candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates are outside the discovery bound");
        }

        Path instance = CanonicalWorldPaths.canonical(policy.testInstanceRoot());
        if (!Files.isDirectory(instance)) {
            return refused(WritableTestWorldFailureCode.TEST_INSTANCE_NOT_FOUND,
                    policy.expectedTestInstanceIdentity(), UNAVAILABLE, instance, UNAVAILABLE,
                    List.of("test instance root is absent"),
                    "The dedicated test instance does not exist",
                    "Build SteveAgent_DeceasedCraft_Test beneath the repository work directory");
        }

        List<WritableTestWorldCandidate> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (WritableTestWorldCandidate candidate : candidates) {
            Path game = CanonicalWorldPaths.canonical(candidate.gameDirectory());
            Path world = CanonicalWorldPaths.canonical(candidate.worldRoot());
            String reason = rejection(candidate, policy, instance, game, world);
            if (reason == null) accepted.add(candidate);
            else rejected.add(candidate.sourceWorldIdentity() + ":" + reason);
        }
        accepted.sort(Comparator.comparing(WritableTestWorldCandidate::sourceWorldIdentity));
        if (accepted.isEmpty()) {
            List<String> evidence = rejected.isEmpty()
                    ? List.of("no direct test-world candidates were supplied") : List.copyOf(rejected);
            return refused(WritableTestWorldFailureCode.TEST_WORLD_NOT_FOUND,
                    policy.expectedTestInstanceIdentity(), UNAVAILABLE, instance, UNAVAILABLE,
                    evidence, "No uniquely marked isolated writable test world was found",
                    "Create Steve Agent Test in the dedicated instance, enter once, save, and fully close Minecraft");
        }
        if (accepted.size() > 1) {
            return refused(WritableTestWorldFailureCode.MULTIPLE_TEST_WORLDS_AMBIGUOUS,
                    policy.expectedTestInstanceIdentity(), UNAVAILABLE, instance, UNAVAILABLE,
                    accepted.stream().map(item -> item.sourceWorldIdentity() + "@"
                            + CanonicalWorldPaths.identity(CanonicalWorldPaths.canonical(item.worldRoot())))
                            .toList(),
                    "More than one marked test world matched the policy",
                    "Select one listed WorldIdentity before any backup, scan, or mutation");
        }

        WritableTestWorldCandidate candidate = accepted.get(0);
        Path game = CanonicalWorldPaths.canonical(candidate.gameDirectory());
        Path world = CanonicalWorldPaths.canonical(candidate.worldRoot());
        String value = "test-world:" + sha256(candidate.testInstanceIdentity() + "\n"
                + candidate.sourceWorldIdentity() + "\n" + CanonicalWorldPaths.identity(world));
        List<String> evidence = new ArrayList<>(candidate.evidence());
        evidence.add("marker=" + WritableTestWorldIdentity.REQUIRED_MARKER);
        evidence.add("canonicalGameDir=" + CanonicalWorldPaths.identity(game));
        evidence.add("canonicalWorldPath=" + CanonicalWorldPaths.identity(world));
        return WritableTestWorldDiscoveryResult.success(new WritableTestWorldIdentity(
                value, candidate.sourceWorldIdentity(), candidate.levelName(),
                candidate.saveDirectoryName(), game, world, candidate.runtimeFingerprint(),
                candidate.worldFingerprint(), candidate.testInstanceIdentity(),
                candidate.occupancy(), evidence));
    }

    private static String rejection(
            WritableTestWorldCandidate candidate,
            WritableTestWorldPolicy policy,
            Path instance,
            Path game,
            Path world) {
        if (!candidate.testInstanceIdentity().equals(policy.expectedTestInstanceIdentity())) {
            return "test instance identity mismatch";
        }
        if (candidate.environmentType() != WorldEnvironmentType.ISOLATED_TEST_WORLD
                || !candidate.disposable()) {
            return "candidate is not a disposable ISOLATED_TEST_WORLD";
        }
        if (!WritableTestWorldIdentity.REQUIRED_MARKER.equals(candidate.marker())) {
            return "required isolated writable marker is absent";
        }
        if (!CanonicalWorldPaths.within(game, instance)
                || !CanonicalWorldPaths.within(world, game.resolve("saves"))) {
            return "candidate is outside the dedicated test instance saves root";
        }
        if (CanonicalWorldPaths.within(game, policy.formalPlatformRoot())
                || CanonicalWorldPaths.within(world, policy.formalPlatformRoot())) {
            return "candidate intersects the formal platform root";
        }
        if (policy.excludedBackupRoots().stream().anyMatch(root ->
                CanonicalWorldPaths.within(world, root) || CanonicalWorldPaths.within(game, root))) {
            return "candidate intersects an excluded backup root";
        }
        if (policy.formalWorldIdentities().contains(candidate.sourceWorldIdentity())) {
            return "candidate WorldIdentity equals a formal WorldIdentity";
        }
        return null;
    }

    private static WritableTestWorldDiscoveryResult refused(
            WritableTestWorldFailureCode code,
            String instance,
            String world,
            Path path,
            String fingerprint,
            List<String> evidence,
            String reason,
            String next) {
        return WritableTestWorldDiscoveryResult.refused(new WritableTestWorldFailure(
                code, WritableTestWorldStage.DISCOVERY, instance, world,
                CanonicalWorldPaths.identity(path), fingerprint, UNAVAILABLE, UNAVAILABLE,
                UNAVAILABLE, UNAVAILABLE, evidence, reason, next));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
