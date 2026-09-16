package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;

/** Stable role IDs shared by verified physical-plan bindings and v606 observers. */
public final class CapabilityObservationRoles {
    public static final ResourceId PRIMARY_MACHINE = id("steve_industrial:primary_machine");
    public static final ResourceId SECONDARY_MACHINE = id("steve_industrial:secondary_machine");
    public static final ResourceId BASIN = id("steve_industrial:basin");
    public static final ResourceId MEDIUM = id("steve_industrial:medium");
    public static final ResourceId HEAT_SOURCE = id("steve_industrial:heat_source");
    public static final ResourceId ITEM_PROCESSING_LANE = id("steve_industrial:item_processing_lane");

    private CapabilityObservationRoles() {
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
