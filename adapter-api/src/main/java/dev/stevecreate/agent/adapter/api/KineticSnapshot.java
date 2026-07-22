package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.kinetics.ComponentId;
import dev.stevecreate.agent.core.kinetics.KineticNetworkTelemetry;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Immutable loader-neutral kinetic state captured from one loaded block entity. */
public record KineticSnapshot(
        long gameTick,
        RuntimeFingerprint runtime,
        ResourceId dimensionId,
        ResourceId blockId,
        BlockPos3i position,
        OptionalLong networkId,
        int networkSize,
        double speedRpm,
        KineticRotationDirection rotationDirection,
        boolean stressEnabled,
        double stressCapacity,
        double stressLoad,
        boolean overstressed) {
    public KineticSnapshot {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(rotationDirection, "rotationDirection");
        if (gameTick < 0) {
            throw new IllegalArgumentException("gameTick must be non-negative");
        }
        if (networkSize < 0) {
            throw new IllegalArgumentException("networkSize must be non-negative");
        }
        if (networkId.isPresent() != (networkSize > 0)) {
            throw new IllegalArgumentException("networkId presence must agree with networkSize");
        }
        if (!Double.isFinite(speedRpm) || speedRpm < 0) {
            throw new IllegalArgumentException("speedRpm must be finite and non-negative");
        }
        if (!Double.isFinite(stressCapacity) || stressCapacity < 0) {
            throw new IllegalArgumentException("stressCapacity must be finite and non-negative");
        }
        if (!Double.isFinite(stressLoad) || stressLoad < 0) {
            throw new IllegalArgumentException("stressLoad must be finite and non-negative");
        }
        if ((speedRpm == 0) != (rotationDirection == KineticRotationDirection.STATIONARY)) {
            throw new IllegalArgumentException("rotationDirection must agree with actual speedRpm");
        }
        if (overstressed != (stressEnabled && stressLoad > stressCapacity)) {
            throw new IllegalArgumentException("overstressed must agree with enabled stress totals");
        }
        if (overstressed && speedRpm != 0) {
            throw new IllegalArgumentException("an overstressed network cannot have actual rotation");
        }
    }

    public double signedSpeedRpm() {
        return switch (rotationDirection) {
            case STATIONARY -> 0;
            case POSITIVE -> speedRpm;
            case NEGATIVE -> -speedRpm;
        };
    }

    public KineticOperatingState operatingState() {
        if (overstressed) {
            return KineticOperatingState.OVERSTRESSED;
        }
        return speedRpm == 0 ? KineticOperatingState.STOPPED : KineticOperatingState.POWERED;
    }

    public KineticNetworkTelemetry toTelemetry() {
        String normalizedNetworkId = networkId.isPresent()
                ? dimensionId + "#" + Long.toUnsignedString(networkId.getAsLong())
                : "unconnected@" + dimensionId + ":" + position.x() + "," + position.y() + "," + position.z();
        ComponentId componentId = new ComponentId(
                blockId + "@" + dimensionId + ":" + position.x() + "," + position.y() + "," + position.z());
        return new KineticNetworkTelemetry(
                normalizedNetworkId,
                Set.of(componentId),
                signedSpeedRpm(),
                stressEnabled,
                stressCapacity,
                stressLoad);
    }
}
