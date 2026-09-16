package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact structure and formation semantics published by a versioned mod adapter. */
public record MultiblockStructureContract(
        ResourceId structureId,
        List<MultiblockComponentContract> components,
        Set<QuarterTurn> supportedOrientations,
        boolean mirrored,
        ResourceId formationActionId,
        ResourceId formedStateSignal,
        String definitionFingerprint) {
    public static final int MAX_COMPONENTS = 4_096;

    public MultiblockStructureContract {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(components, "components");
        if (components.isEmpty() || components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("multiblock components are empty or unbounded");
        }
        components = components.stream().sorted(Comparator.comparing(
                        MultiblockComponentContract::relativePosition,
                        Comparator.comparingInt(dev.stevecreate.agent.core.model.BlockPos3i::x)
                                .thenComparingInt(dev.stevecreate.agent.core.model.BlockPos3i::y)
                                .thenComparingInt(dev.stevecreate.agent.core.model.BlockPos3i::z)))
                .toList();
        if (components.stream().map(MultiblockComponentContract::relativePosition).distinct().count()
                != components.size()) {
            throw new IllegalArgumentException("multiblock component positions overlap");
        }
        supportedOrientations = Set.copyOf(Objects.requireNonNull(
                supportedOrientations, "supportedOrientations"));
        if (supportedOrientations.isEmpty()) {
            throw new IllegalArgumentException("multiblock orientations are empty");
        }
        Objects.requireNonNull(formationActionId, "formationActionId");
        Objects.requireNonNull(formedStateSignal, "formedStateSignal");
        Objects.requireNonNull(definitionFingerprint, "definitionFingerprint");
        if (!definitionFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("multiblock definition fingerprint is invalid");
        }
    }
}
