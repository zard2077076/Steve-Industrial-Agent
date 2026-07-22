package dev.stevecreate.agent.core.safety;

import java.util.Objects;

public record SafetyDecision(boolean allowed, String reasonCode) {
    public SafetyDecision {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }

    public static SafetyDecision allow() {
        return new SafetyDecision(true, "ALLOWLISTED");
    }

    public static SafetyDecision deny(String reasonCode) {
        return new SafetyDecision(false, reasonCode);
    }
}

