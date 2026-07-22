package dev.stevecreate.agent.core.verification;

import java.util.Objects;

/** Stable loader-neutral evidence kinds used by generic verification rules. */
public enum VerificationEvidenceKind {
    BLOCK_PRESENT("block_present"),
    BLOCK_STATE_MATCH("block_state_match"),
    NETWORK_CONNECTED("network_connected"),
    POWER_PRESENT("power_present"),
    INPUT_CONSUMED("input_consumed"),
    PROCESS_STARTED("process_started"),
    PROCESS_COMPLETED("process_completed"),
    OUTPUT_PRODUCED("output_produced"),
    OUTPUT_STORED("output_stored"),
    NO_NEW_CRASH("no_new_crash"),
    CUSTOM_ADAPTER_EVIDENCE("custom_adapter_evidence");

    private final String serializedName;

    VerificationEvidenceKind(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public static VerificationEvidenceKind fromSerializedName(String serializedName) {
        Objects.requireNonNull(serializedName, "serializedName");
        for (VerificationEvidenceKind kind : values()) {
            if (kind.serializedName.equals(serializedName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown verification evidence kind: " + serializedName);
    }
}
