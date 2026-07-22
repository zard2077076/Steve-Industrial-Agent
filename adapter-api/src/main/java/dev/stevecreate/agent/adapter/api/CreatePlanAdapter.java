package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;

/** Loader-neutral entry point for validated Create build-and-process plans. */
public interface CreatePlanAdapter {
    ResourceId adapterId();

    RuntimeFingerprint runtime();

    default String targetModId() {
        return "create";
    }

    AdapterResult<CreatePlanExecutionSession> begin(WaterWheelMillstonePlan plan);
}
