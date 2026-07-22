package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Explicit test-instance roots and formal/backup exclusions. */
public record WritableTestWorldPolicy(
        Path testInstanceRoot,
        Path formalPlatformRoot,
        Set<Path> excludedBackupRoots,
        String expectedTestInstanceIdentity,
        Set<String> formalWorldIdentities) {
    public WritableTestWorldPolicy {
        Objects.requireNonNull(testInstanceRoot, "testInstanceRoot");
        Objects.requireNonNull(formalPlatformRoot, "formalPlatformRoot");
        excludedBackupRoots = paths(excludedBackupRoots);
        expectedTestInstanceIdentity = WorldEnvironmentEvidence.text(
                expectedTestInstanceIdentity, "expectedTestInstanceIdentity");
        formalWorldIdentities = Set.copyOf(Objects.requireNonNull(
                formalWorldIdentities, "formalWorldIdentities"));
        if (formalWorldIdentities.isEmpty() || formalWorldIdentities.size() > 64
                || formalWorldIdentities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("formalWorldIdentities is empty or invalid");
        }
    }

    private static Set<Path> paths(Set<Path> values) {
        Objects.requireNonNull(values, "excludedBackupRoots");
        if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("excludedBackupRoots is empty or invalid");
        }
        LinkedHashSet<Path> copy = new LinkedHashSet<>();
        values.forEach(path -> copy.add(CanonicalWorldPaths.canonical(path)));
        return Set.copyOf(copy);
    }
}
