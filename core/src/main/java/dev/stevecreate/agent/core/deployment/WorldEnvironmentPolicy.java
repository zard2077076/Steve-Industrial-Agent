package dev.stevecreate.agent.core.deployment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit canonical-root policy. Alias entries support independently verified junction/symlink identities. */
public record WorldEnvironmentPolicy(
        Set<Path> allowedRoots,
        Set<Path> forbiddenRoots,
        Map<Path, Path> verifiedAliases,
        boolean isolatedExecutionEnabled) {
    public static final int MAX_ROOTS = 64;

    public WorldEnvironmentPolicy {
        allowedRoots = paths(allowedRoots, "allowedRoots");
        forbiddenRoots = paths(forbiddenRoots, "forbiddenRoots");
        if (allowedRoots.isEmpty() || forbiddenRoots.isEmpty()) {
            throw new IllegalArgumentException("allowedRoots and forbiddenRoots must be explicit");
        }
        Objects.requireNonNull(verifiedAliases, "verifiedAliases");
        if (verifiedAliases.size() > MAX_ROOTS) throw new IllegalArgumentException("Too many verified aliases");
        Map<Path, Path> aliases = new LinkedHashMap<>();
        verifiedAliases.forEach((alias, target) -> aliases.put(
                CanonicalWorldPaths.normalized(Objects.requireNonNull(alias, "alias")),
                CanonicalWorldPaths.canonical(Objects.requireNonNull(target, "alias target"))));
        verifiedAliases = Map.copyOf(aliases);
    }

    private static Set<Path> paths(Set<Path> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_ROOTS || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        LinkedHashSet<Path> canonical = new LinkedHashSet<>();
        values.forEach(value -> canonical.add(CanonicalWorldPaths.canonical(value)));
        return Set.copyOf(canonical);
    }
}
