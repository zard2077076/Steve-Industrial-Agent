package dev.stevecreate.agent.core.graph;

import dev.stevecreate.agent.core.model.Direction6;
import java.util.Objects;
import java.util.Optional;

/** Optional facing and axis carried without a game block-state dependency. */
public record MachineOrientation(Optional<Direction6> facing, Optional<MachineAxis> axis) {
    public static final MachineOrientation NONE = new MachineOrientation(Optional.empty(), Optional.empty());

    public MachineOrientation {
        facing = Objects.requireNonNull(facing, "facing");
        axis = Objects.requireNonNull(axis, "axis");
    }

    public static MachineOrientation ofFacing(Direction6 facing) {
        return new MachineOrientation(Optional.of(Objects.requireNonNull(facing, "facing")), Optional.empty());
    }

    public static MachineOrientation ofAxis(MachineAxis axis) {
        return new MachineOrientation(Optional.empty(), Optional.of(Objects.requireNonNull(axis, "axis")));
    }

    public static MachineOrientation of(Direction6 facing, MachineAxis axis) {
        return new MachineOrientation(
                Optional.of(Objects.requireNonNull(facing, "facing")),
                Optional.of(Objects.requireNonNull(axis, "axis")));
    }
}
