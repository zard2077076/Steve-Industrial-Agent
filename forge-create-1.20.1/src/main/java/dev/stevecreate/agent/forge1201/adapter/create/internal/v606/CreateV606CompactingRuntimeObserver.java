package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.create.CapabilityObservationRequest;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationResult;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

public final class CreateV606CompactingRuntimeObserver implements CreateV606RuntimeObserver {
    private final CreateV606CapabilityRuntimeObserver delegate = new CreateV606CapabilityRuntimeObserver();

    @Override
    public CapabilityObservationResult observe(ServerLevel level, String runtime, String world,
            CapabilityObservationRequest request, CapabilityRecipeSemantics semantics) {
        return delegate.observe(level, runtime, world, request, semantics,
                Set.of(CreateCapabilityId.COMPACTING_PHASE_I));
    }
}
