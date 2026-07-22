package dev.stevecreate.agent.forge1201.adapter.create;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.placement.BasicPlacementFeasibility;
import dev.stevecreate.agent.core.placement.PlacementConflict;
import dev.stevecreate.agent.core.placement.PlacementConflictKind;
import dev.stevecreate.agent.core.placement.PlacementFeasibilityReport;
import dev.stevecreate.agent.core.placement.PlacementSiteObservation;
import dev.stevecreate.agent.core.placement.PlacementTarget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Stable Forge world reader for the loader-neutral basic placement gate. */
final class ForgeBasicPlacementFeasibility {
    private ForgeBasicPlacementFeasibility() {
    }

    static Set<BlockPos3i> copyProtectedPositions(Set<BlockPos3i> protectedPositions) {
        Set<BlockPos3i> copy = Set.copyOf(Objects.requireNonNull(protectedPositions, "protectedPositions"));
        if (copy.size() > BasicPlacementFeasibility.MAX_TARGETS
                || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Protected positions exceed the basic placement bound");
        }
        return copy;
    }

    static PlacementFeasibilityReport inspect(
            ServerLevel level,
            List<PlacementTarget> targets,
            Set<BlockPos3i> protectedPositions) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(protectedPositions, "protectedPositions");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Placement feasibility world reads require the server thread");
        }

        LinkedHashSet<BlockPos3i> positions = new LinkedHashSet<>();
        targets.forEach(target -> positions.add(target.position()));
        List<PlacementSiteObservation> observations = positions.stream()
                .map(position -> observe(level, position, protectedPositions.contains(position)))
                .toList();
        return BasicPlacementFeasibility.evaluate(targets, observations);
    }

    static AdapterFailureCode failureCode(PlacementFeasibilityReport report) {
        Objects.requireNonNull(report, "report");
        return report.conflicts().stream()
                        .allMatch(conflict -> conflict.kind() == PlacementConflictKind.UNLOADED)
                ? AdapterFailureCode.CHUNK_NOT_LOADED
                : AdapterFailureCode.PLAN_REJECTED;
    }

    static String detail(String planName, PlacementFeasibilityReport report) {
        Objects.requireNonNull(planName, "planName");
        Objects.requireNonNull(report, "report");
        String conflicts = report.conflicts().stream()
                .map(ForgeBasicPlacementFeasibility::conflictDetail)
                .collect(Collectors.joining(";"));
        return planName + " basic placement feasibility rejected: targets=" + report.targetCount()
                + " conflicts=" + report.conflicts().size()
                + " positions=" + report.conflictingPositions().size()
                + " [" + conflicts + "]";
    }

    private static PlacementSiteObservation observe(
            ServerLevel level,
            BlockPos3i position,
            boolean protectedPosition) {
        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        boolean loaded = level.hasChunk(chunkX, chunkZ);
        boolean withinBuildHeight = position.y() >= level.getMinBuildHeight()
                && position.y() < level.getMaxBuildHeight();
        boolean replaceable = loaded
                && withinBuildHeight
                && level.getBlockState(new BlockPos(position.x(), position.y(), position.z()))
                        .canBeReplaced();
        return new PlacementSiteObservation(position, loaded, replaceable, protectedPosition);
    }

    private static String conflictDetail(PlacementConflict conflict) {
        return conflict.kind() + "@" + conflict.position().x() + ","
                + conflict.position().y() + "," + conflict.position().z()
                + " roles=" + conflict.roleIds().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(","));
    }
}
