package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Exact fluid and component identity; all quantities using it are millibuckets. */
public record FluidIdentity(ResourceId fluidId, String componentSha256)
        implements Comparable<FluidIdentity> {
    public static final String EMPTY_COMPONENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public FluidIdentity {
        Objects.requireNonNull(fluidId, "fluidId");
        Objects.requireNonNull(componentSha256, "componentSha256");
        if (!componentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fluid component hash is invalid");
        }
    }

    @Override
    public int compareTo(FluidIdentity other) {
        int id = fluidId.toString().compareTo(other.fluidId.toString());
        return id != 0 ? id : componentSha256.compareTo(other.componentSha256);
    }
}
