package dev.stevecreate.agent.core.plan;

/** One unique, validated role in the bounded C-03 water-wheel/millstone layout. */
public enum WaterWheelMillstoneRole {
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
    WATER_WHEEL,
    GEARBOX,
    VERTICAL_SHAFT,
    MILLSTONE,
    WATER_SOURCE
}
