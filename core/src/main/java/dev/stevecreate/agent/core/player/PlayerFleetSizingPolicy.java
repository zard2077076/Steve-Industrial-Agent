package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Frozen player-facing fleet sizing for the reviewed C-01 through C-10 and Composite workloads. */
public final class PlayerFleetSizingPolicy {
    private PlayerFleetSizingPolicy() {}

    /** Discovery-only C-01/C-02 never manufacture construction or material work. */
    public static int discoveryWorkers(String capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        if (!capabilityId.equals("C-01") && !capabilityId.equals("C-02")) {
            throw new IllegalArgumentException("not a reviewed discovery capability");
        }
        return 0;
    }

    /** C-03 through C-07 use two roles; counted multi-input C-08 through C-10 use three. */
    public static int constructionWorkers(ResourceId capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        CreateCapabilityContractV1 contract = CreateCapabilityContractV1
                .forProcessCapability(capabilityId)
                .filter(CreateCapabilityContractV1::materialBearing)
                .orElseThrow(() -> new IllegalArgumentException(
                        "not a reviewed C-03 through C-10 construction capability"));
        return contract == CreateCapabilityContractV1.C08
                || contract == CreateCapabilityContractV1.C09
                || contract == CreateCapabilityContractV1.C10 ? 3 : 2;
    }

    /** Composite-01 is a three-worker linear line; branched Composite-02/03 use all five. */
    public static int compositeWorkers(String compositeId) {
        Objects.requireNonNull(compositeId, "compositeId");
        return switch (compositeId) {
            case "Composite-01" -> 3;
            case "Composite-02", "Composite-03" -> 5;
            default -> throw new IllegalArgumentException("unknown reviewed Composite workload");
        };
    }
}
