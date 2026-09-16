package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.Direction6;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One server-observed FE network node and its bounded energy contract. */
public record ElectricalNetworkNode(
        ResourceId nodeId,
        ElectricalNodeKind kind,
        BlockPos3i position,
        VoltageTier primaryTier,
        Optional<VoltageTier> secondaryTier,
        Set<Direction6> inputFaces,
        Set<Direction6> outputFaces,
        long generationPerTick,
        long consumptionPerTick,
        long storedEnergy,
        long storageCapacity,
        String stateSha256,
        boolean serverObserved) {
    public ElectricalNetworkNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(primaryTier, "primaryTier");
        secondaryTier = Objects.requireNonNull(secondaryTier, "secondaryTier");
        inputFaces = Set.copyOf(Objects.requireNonNull(inputFaces, "inputFaces"));
        outputFaces = Set.copyOf(Objects.requireNonNull(outputFaces, "outputFaces"));
        if (generationPerTick < 0 || consumptionPerTick < 0 || storedEnergy < 0
                || storageCapacity < storedEnergy || storageCapacity > 1_000_000_000_000L) {
            throw new IllegalArgumentException("electrical node quantities are invalid");
        }
        Objects.requireNonNull(stateSha256, "stateSha256");
        if (!stateSha256.matches("[0-9a-f]{64}") || !serverObserved) {
            throw new IllegalArgumentException("electrical node observation is incomplete");
        }
        if ((kind == ElectricalNodeKind.TRANSFORMER) != secondaryTier.isPresent()
                || secondaryTier.filter(primaryTier::equals).isPresent()) {
            throw new IllegalArgumentException("transformer voltage tiers are invalid");
        }
        if (kind == ElectricalNodeKind.GENERATOR && generationPerTick < 1) {
            throw new IllegalArgumentException("generator must publish positive generation");
        }
        if (kind == ElectricalNodeKind.CONSUMER && consumptionPerTick < 1) {
            throw new IllegalArgumentException("consumer must publish positive load");
        }
        if (kind == ElectricalNodeKind.STORAGE && storageCapacity < 1) {
            throw new IllegalArgumentException("storage must publish positive capacity");
        }
    }

    public boolean supports(VoltageTier tier) {
        return primaryTier == tier || secondaryTier.filter(value -> value == tier).isPresent();
    }
}
