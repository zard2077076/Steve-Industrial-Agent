package dev.stevecreate.agent.core.layout;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit cells captured by an Adapter without loading chunks or mutating the world. */
public record PlacementSnapshot(
        String runtimeFingerprint,
        long snapshotGeneration,
        Map<BlockPos3i, LayoutCellState> cells) {
    public static final int MAX_CELLS = 262_144;

    public PlacementSnapshot {
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank()) throw new IllegalArgumentException("runtimeFingerprint is blank");
        if (snapshotGeneration < 0) throw new IllegalArgumentException("snapshotGeneration cannot be negative");
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty() || cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("snapshot cell count is outside its bound");
        }
        cells.forEach((position, state) -> {
            Objects.requireNonNull(position, "snapshot position");
            Objects.requireNonNull(state, "snapshot state");
        });
        cells = Map.copyOf(cells);
    }

    public Optional<LayoutCellState> state(BlockPos3i position) {
        return Optional.ofNullable(cells.get(Objects.requireNonNull(position, "position")));
    }
}
