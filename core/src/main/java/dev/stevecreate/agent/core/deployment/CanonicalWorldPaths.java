package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

final class CanonicalWorldPaths {
    record Resolution(Path path, boolean verified) {}

    private CanonicalWorldPaths() {}

    static Path canonical(Path value) {
        Path normalized = normalized(value);
        try {
            return normalized.toRealPath();
        } catch (IOException | SecurityException unavailable) {
            return normalized;
        }
    }

    static Path canonical(Path value, Map<Path, Path> aliases) {
        return resolve(value, aliases).path();
    }

    static Resolution resolve(Path value, Map<Path, Path> aliases) {
        Path normalized = normalized(value);
        Map.Entry<Path, Path> alias = aliases.entrySet().stream()
                .filter(entry -> within(normalized, entry.getKey()))
                .max(Comparator.comparingInt(entry -> key(entry.getKey()).length()))
                .orElse(null);
        if (alias != null) {
            Path remainder = alias.getKey().relativize(normalized);
            return real(alias.getValue().resolve(remainder));
        }
        return real(normalized);
    }

    static Path normalized(Path value) {
        return value.toAbsolutePath().normalize();
    }

    static boolean within(Path candidate, Path root) {
        String candidateKey = key(candidate);
        String rootKey = key(root);
        if (candidateKey.equals(rootKey)) return true;
        String separator = rootKey.endsWith("/") ? "" : "/";
        return candidateKey.startsWith(rootKey + separator);
    }

    static String identity(Path value) {
        return value.toString().replace('\\', '/');
    }

    private static String key(Path value) {
        return identity(normalized(value)).toLowerCase(Locale.ROOT);
    }

    private static Resolution real(Path value) {
        Path normalized = normalized(value);
        try {
            return new Resolution(normalized.toRealPath(), true);
        } catch (IOException | SecurityException unavailable) {
            return new Resolution(normalized, false);
        }
    }
}
