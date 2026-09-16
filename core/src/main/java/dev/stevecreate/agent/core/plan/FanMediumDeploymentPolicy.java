package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import java.util.Objects;

/** Loader-neutral C-06 gate that prevents Bots from deploying or entering dangerous media. */
public final class FanMediumDeploymentPolicy {
    private FanMediumDeploymentPolicy() {}

    public static Decision decide(FanProcessingMode medium, ExecutionMode requestedMode) {
        Objects.requireNonNull(medium, "medium");
        Objects.requireNonNull(requestedMode, "requestedMode");
        if (!medium.dangerousToBots()) {
            return new Decision(requestedMode, false, "medium_safe_for_bounded_bot_standoff");
        }
        return switch (requestedMode) {
            case DIRECT -> new Decision(
                    ExecutionMode.DIRECT, false, "dangerous_medium_direct_only");
            case HYBRID -> new Decision(
                    ExecutionMode.DIRECT, true,
                    "dangerous_medium_deployment_fallback_to_direct");
            case BOTS -> throw new UnsupportedOperationException(
                    "C-06 Bot execution cannot deploy or enter "
                            + medium.name().toLowerCase()
                            + "; request Hybrid for typed Direct fallback");
        };
    }

    public record Decision(
            ExecutionMode executionMode,
            boolean fallback,
            String reason) {
        public Decision {
            Objects.requireNonNull(executionMode, "executionMode");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()
                    || (fallback && executionMode != ExecutionMode.DIRECT)) {
                throw new IllegalArgumentException("Invalid C-06 medium deployment decision");
            }
        }
    }
}
