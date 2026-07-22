package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.ResourceId;

/** Common read boundary for an industrial mod without erasing mod-specific semantics. */
public interface IndustrialModAdapter {
    ResourceId adapterId();

    String targetModId();

    RuntimeFingerprint runtime();

    AdapterResult<WorldSnapshot> capture(ScanRequest request);
}

