package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.create.CapabilityObservationRequest;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationResult;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import net.minecraft.server.level.ServerLevel;

/** Version-confined, mutation-free observer boundary consumed by A-owned executors. */
public interface CreateV606RuntimeObserver {
    CapabilityObservationResult observe(
            ServerLevel level,
            String currentRuntimeFingerprint,
            String currentWorldSnapshotFingerprint,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics);
}
