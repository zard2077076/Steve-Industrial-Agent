package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import java.util.Collection;
import java.util.Objects;

public record DeploymentBoundingBox(BlockPos3i minimum, BlockPos3i maximum) {
    public DeploymentBoundingBox {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("bounding box minimum exceeds maximum");
        }
    }

    public static DeploymentBoundingBox enclosing(Collection<BlockPos3i> positions) {
        Objects.requireNonNull(positions, "positions");
        if (positions.isEmpty()) throw new IllegalArgumentException("cannot bound an empty position set");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos3i position : positions) {
            Objects.requireNonNull(position, "position");
            minX = Math.min(minX, position.x());
            minY = Math.min(minY, position.y());
            minZ = Math.min(minZ, position.z());
            maxX = Math.max(maxX, position.x());
            maxY = Math.max(maxY, position.y());
            maxZ = Math.max(maxZ, position.z());
        }
        return new DeploymentBoundingBox(
                new BlockPos3i(minX, minY, minZ), new BlockPos3i(maxX, maxY, maxZ));
    }

    public boolean contains(BlockPos3i position) {
        Objects.requireNonNull(position, "position");
        return position.x() >= minimum.x() && position.x() <= maximum.x()
                && position.y() >= minimum.y() && position.y() <= maximum.y()
                && position.z() >= minimum.z() && position.z() <= maximum.z();
    }

    public long volume() {
        return Math.multiplyExact(Math.multiplyExact(
                (long) maximum.x() - minimum.x() + 1,
                (long) maximum.y() - minimum.y() + 1),
                (long) maximum.z() - minimum.z() + 1);
    }
}
