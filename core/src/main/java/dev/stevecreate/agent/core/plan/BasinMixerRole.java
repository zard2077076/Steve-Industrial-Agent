package dev.stevecreate.agent.core.plan;

/** Fixed roles owned by the C-08 Basin + Mechanical Mixer plan. */
public enum BasinMixerRole {
    FLOW_CATCH_FLOOR,
    FLOW_CATCH_WEST_WALL,
    FLOW_CATCH_EAST_WALL,
    FLOW_CATCH_NORTH_WALL,
    FLOW_CATCH_SOUTH_WALL,
    FLOW_CHAMBER_WEST_WALL,
    FLOW_CHAMBER_EAST_WALL,
    FLOW_CHAMBER_NORTH_WALL,
    FLOW_CHAMBER_SOUTH_WALL,
    FLOW_CHANNEL_WEST_WALL,
    FLOW_CHANNEL_EAST_WALL,
    FLOW_CHANNEL_NORTH_WALL,
    MIXER_PLATFORM,
    WATER_WHEEL,
    BOTTOM_GEARBOX,
    VERTICAL_SHAFT,
    LARGE_COGWHEEL_INPUT,
    SMALL_COGWHEEL,
    LARGE_COGWHEEL_OUTPUT,
    MECHANICAL_MIXER,
    BASIN,
    HEAT_SOURCE,
    OUTPUT_CHEST,
    WATER_SOURCE;

    public boolean isKinetic() {
        return switch (this) {
            case WATER_WHEEL, BOTTOM_GEARBOX, VERTICAL_SHAFT,
                    LARGE_COGWHEEL_INPUT, SMALL_COGWHEEL,
                    LARGE_COGWHEEL_OUTPUT, MECHANICAL_MIXER -> true;
            default -> false;
        };
    }
}
