package dev.stevecreate.agent.core.graph;

/** Resource-flow capability of one machine port. */
public enum PortMode {
    INPUT,
    OUTPUT,
    BIDIRECTIONAL;

    public boolean acceptsInput() {
        return this == INPUT || this == BIDIRECTIONAL;
    }

    public boolean providesOutput() {
        return this == OUTPUT || this == BIDIRECTIONAL;
    }
}
