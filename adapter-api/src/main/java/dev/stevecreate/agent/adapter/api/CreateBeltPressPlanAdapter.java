package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;

/** Loader-neutral entry point dedicated to the validated C-04 belt/press plan. */
public interface CreateBeltPressPlanAdapter {
    ResourceId adapterId();

    RuntimeFingerprint runtime();

    default String targetModId() {
        return "create";
    }

    AdapterResult<BeltPressExecutionSession> begin(BeltPressPlan plan);
}
