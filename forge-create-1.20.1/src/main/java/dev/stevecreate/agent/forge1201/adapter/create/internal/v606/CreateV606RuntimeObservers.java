package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import java.util.Objects;

/** Exact capability-to-observer routing without constructing an executor. */
public final class CreateV606RuntimeObservers {
    private static final CreateV606RuntimeObserver CRUSHING =
            new CreateV606CrushingRuntimeObserver();
    private static final CreateV606RuntimeObserver FAN =
            new CreateV606FanRuntimeObserver();
    private static final CreateV606RuntimeObserver CUTTING =
            new CreateV606CuttingRuntimeObserver();
    private static final CreateV606RuntimeObserver MIXING =
            new CreateV606MixingRuntimeObserver();
    private static final CreateV606RuntimeObserver COMPACTING =
            new CreateV606CompactingRuntimeObserver();
    private static final CreateV606RuntimeObserver DEPLOYING =
            new CreateV606DeployingRuntimeObserver();

    private CreateV606RuntimeObservers() {
    }

    public static CreateV606RuntimeObserver forCapability(CreateCapabilityId capability) {
        Objects.requireNonNull(capability, "capability");
        return switch (capability) {
            case CRUSHING -> CRUSHING;
            case FAN_WASHING, FAN_SMOKING, FAN_HAUNTING, FAN_BLASTING -> FAN;
            case CUTTING -> CUTTING;
            case MIXING_PHASE_I -> MIXING;
            case COMPACTING_PHASE_I -> COMPACTING;
            case DEPLOYING_PHASE_I -> DEPLOYING;
        };
    }
}
