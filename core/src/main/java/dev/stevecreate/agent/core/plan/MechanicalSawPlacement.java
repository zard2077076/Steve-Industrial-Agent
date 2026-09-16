package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** One immutable C-07 placement. */
public record MechanicalSawPlacement(
        int order,
        MechanicalSawRole role,
        ResourceId blockId,
        BlockPos3i position,
        PlanBlockAxis rotationAxis,
        PlanBlockFacing facing) {

    public MechanicalSawPlacement {
        if (order < 1) throw new IllegalArgumentException("C-07 placement order must be positive");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationAxis, "rotationAxis");
        Objects.requireNonNull(facing, "facing");
        if (role.isKinetic() && rotationAxis == PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("C-07 kinetic roles require an axis");
        }
        if (!role.isKinetic() && rotationAxis != PlanBlockAxis.NONE) {
            throw new IllegalArgumentException("C-07 non-kinetic roles cannot claim an axis");
        }
        if (role == MechanicalSawRole.MECHANICAL_SAW && facing != PlanBlockFacing.UP) {
            throw new IllegalArgumentException(
                    "C-07 saw must face upward so world block cutting remains structurally disabled");
        }
    }
}
