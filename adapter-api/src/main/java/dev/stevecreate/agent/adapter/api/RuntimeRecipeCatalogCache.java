package dev.stevecreate.agent.adapter.api;

import java.util.Objects;
import java.util.Optional;

/** One-world, one-generation cache that retains only loader-neutral catalog snapshots. */
public final class RuntimeRecipeCatalogCache {
    private RuntimeRecipeCatalogSnapshot snapshot;
    private long generation;

    public synchronized Optional<RuntimeRecipeCatalogSnapshot> find(
            String worldIdentity,
            String canonicalRuntimeFingerprint,
            long reloadGeneration) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(canonicalRuntimeFingerprint, "canonicalRuntimeFingerprint");
        if (snapshot == null
                || reloadGeneration != generation
                || !snapshot.worldIdentity().equals(worldIdentity)
                || !snapshot.canonicalRuntimeFingerprint().equals(canonicalRuntimeFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public synchronized void store(RuntimeRecipeCatalogSnapshot value) {
        RuntimeRecipeCatalogSnapshot candidate = Objects.requireNonNull(value, "value");
        if (snapshot == null && generation == 0) {
            generation = candidate.reloadGeneration();
        }
        if (candidate.reloadGeneration() != generation) {
            throw new IllegalArgumentException(
                    "Snapshot reload generation does not match the active cache generation");
        }
        snapshot = candidate;
    }

    public synchronized long invalidate(long nextGeneration) {
        if (nextGeneration <= generation) {
            throw new IllegalArgumentException("Reload generation must increase monotonically");
        }
        snapshot = null;
        generation = nextGeneration;
        return generation;
    }

    public synchronized long generation() {
        return generation;
    }
}
