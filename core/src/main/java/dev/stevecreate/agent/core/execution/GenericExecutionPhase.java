package dev.stevecreate.agent.core.execution;

import java.util.Objects;

/** Stable loader-neutral phases for bounded industrial execution steps. */
public enum GenericExecutionPhase {
    PREPARE("prepare"),
    BUILD("build"),
    CONNECT("connect"),
    POWER("power"),
    FEED_INPUT("feed_input"),
    PROCESS("process"),
    TRANSFER_OUTPUT("transfer_output"),
    VERIFY("verify"),
    CLEANUP("cleanup");

    private final String serializedName;

    GenericExecutionPhase(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static GenericExecutionPhase fromSerializedName(String serializedName) {
        Objects.requireNonNull(serializedName, "serializedName");
        for (GenericExecutionPhase phase : values()) {
            if (phase.serializedName.equals(serializedName)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown generic execution phase: " + serializedName);
    }
}
