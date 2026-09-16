package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.create.CapabilityObservationRequest;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationResult;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;

public final class CreateV606FanRuntimeObserver implements CreateV606RuntimeObserver {
    private final CreateV606CapabilityRuntimeObserver delegate = new CreateV606CapabilityRuntimeObserver();

    @Override
    public CapabilityObservationResult observe(ServerLevel level, String runtime, String world,
            CapabilityObservationRequest request, CapabilityRecipeSemantics semantics) {
        return delegate.observe(level, runtime, world, request, semantics, EnumSet.of(
                CreateCapabilityId.FAN_WASHING,
                CreateCapabilityId.FAN_SMOKING,
                CreateCapabilityId.FAN_HAUNTING,
                CreateCapabilityId.FAN_BLASTING));
    }
}
