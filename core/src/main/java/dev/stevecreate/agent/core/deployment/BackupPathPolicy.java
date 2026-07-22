package dev.stevecreate.agent.core.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Canonical isolated/backup/forbidden roots used before any backup filesystem access. */
public record BackupPathPolicy(
        Path isolatedRoot,
        Path backupRoot,
        Set<Path> forbiddenRoots,
        BackupRootRelationship backupRootRelationship) {
    public BackupPathPolicy(Path isolatedRoot, Path backupRoot, Set<Path> forbiddenRoots) {
        this(isolatedRoot, backupRoot, forbiddenRoots,
                BackupRootRelationship.WITHIN_ISOLATED_ROOT);
    }

    public BackupPathPolicy {
        isolatedRoot = normalized(isolatedRoot, "isolatedRoot");
        backupRoot = normalized(backupRoot, "backupRoot");
        Objects.requireNonNull(forbiddenRoots, "forbiddenRoots");
        Objects.requireNonNull(backupRootRelationship, "backupRootRelationship");
        forbiddenRoots = forbiddenRoots.stream()
                .map(path -> normalized(path, "forbiddenRoot"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (forbiddenRoots.isEmpty()) {
            throw new IllegalArgumentException("forbiddenRoots must be explicit");
        }
    }

    public boolean allowsSource(Path source) throws IOException {
        Path realIsolated = isolatedRoot.toRealPath();
        Path realBackup = backupRoot.toRealPath();
        Path realSource = normalized(source, "source").toRealPath();
        return validRootRelationship(realIsolated, realBackup) && Files.isDirectory(realSource)
                && realSource.startsWith(realIsolated)
                && !realSource.startsWith(realBackup) && !isForbidden(realSource);
    }

    public boolean allowsTarget(Path target) throws IOException {
        Path realIsolated = isolatedRoot.toRealPath();
        Path realBackup = backupRoot.toRealPath();
        Path normalizedTarget = normalized(target, "target");
        Path parent = Objects.requireNonNull(normalizedTarget.getParent(), "target parent")
                .toRealPath();
        Path canonical = parent.resolve(normalizedTarget.getFileName()).normalize();
        return validRootRelationship(realIsolated, realBackup) && canonical.startsWith(realBackup)
                && !canonical.equals(realBackup)
                && !isForbidden(canonical);
    }

    public Path canonicalSource(Path source) throws IOException {
        if (!allowsSource(source)) throw new IOException("source is outside isolated policy");
        return normalized(source, "source").toRealPath();
    }

    public Path canonicalTarget(Path target) throws IOException {
        if (!allowsTarget(target)) throw new IOException("target is outside backup policy");
        Path normalizedTarget = normalized(target, "target");
        return normalizedTarget.getParent().toRealPath()
                .resolve(normalizedTarget.getFileName()).normalize();
    }

    public Path canonicalBackupRoot() throws IOException {
        return backupRoot.toRealPath();
    }

    private boolean validRootRelationship(Path realIsolated, Path realBackup) {
        return switch (backupRootRelationship) {
            case WITHIN_ISOLATED_ROOT -> realBackup.startsWith(realIsolated);
            case EXTERNAL_DISJOINT -> !realBackup.startsWith(realIsolated)
                    && !realIsolated.startsWith(realBackup);
        };
    }

    private boolean isForbidden(Path path) throws IOException {
        for (Path forbidden : forbiddenRoots) {
            Path identity = Files.exists(forbidden) ? forbidden.toRealPath() : forbidden;
            if (path.equals(identity) || path.startsWith(identity)) return true;
        }
        return false;
    }

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
