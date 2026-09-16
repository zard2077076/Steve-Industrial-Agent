package dev.stevecreate.agent.adapter.api.create;

import java.util.Objects;

/** One bounded failure/warning attached to an exact runtime recipe field. */
public record CapabilityRecipeLimitation(
        CapabilityRecipeLimitationCode code,
        String field,
        String detail,
        boolean blocksPhaseI) {
    public CapabilityRecipeLimitation {
        Objects.requireNonNull(code, "code");
        field = CapabilityContracts.text(field, "field");
        detail = CapabilityContracts.text(detail, "detail");
    }
}
