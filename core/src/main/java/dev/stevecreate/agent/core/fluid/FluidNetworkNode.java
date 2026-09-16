package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record FluidNetworkNode(
        ResourceId nodeId,
        FluidNodeKind kind,
        BlockPos3i position,
        Optional<FluidIdentity> contents,
        long amountMb,
        long capacityMb,
        Set<Direction6> inputFaces,
        Set<Direction6> outputFaces,
        long pumpRateMbPerTick,
        String snapshotSha256,
        boolean serverObserved) {
    public FluidNetworkNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        contents = Objects.requireNonNull(contents, "contents");
        if (amountMb < 0 || capacityMb < amountMb || capacityMb > 1_000_000_000_000L
                || (amountMb > 0) != contents.isPresent()
                || pumpRateMbPerTick < 0 || pumpRateMbPerTick > 1_000_000_000_000L) {
            throw new IllegalArgumentException("fluid node quantity or identity is invalid");
        }
        inputFaces = Set.copyOf(Objects.requireNonNull(inputFaces, "inputFaces"));
        outputFaces = Set.copyOf(Objects.requireNonNull(outputFaces, "outputFaces"));
        if (kind == FluidNodeKind.PUMP && pumpRateMbPerTick < 1) {
            throw new IllegalArgumentException("fluid pump requires a positive rate");
        }
        Objects.requireNonNull(snapshotSha256, "snapshotSha256");
        if (!snapshotSha256.matches("[0-9a-f]{64}") || !serverObserved) {
            throw new IllegalArgumentException("fluid node observation is incomplete");
        }
    }
}
