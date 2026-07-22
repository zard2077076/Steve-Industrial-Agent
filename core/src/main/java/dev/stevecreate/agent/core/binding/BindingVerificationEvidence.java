package dev.stevecreate.agent.core.binding;

import java.util.List;
import java.util.Objects;

/** Audit evidence for one binding verification check. */
public record BindingVerificationEvidence(
        BindingVerificationCheck check,
        List<String> trace,
        String detail) {
    public BindingVerificationEvidence {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(trace, "trace");
        trace = trace.stream().map(value -> requireText(value, "trace element")).toList();
        if (trace.isEmpty()) {
            throw new IllegalArgumentException("Verification evidence trace cannot be empty");
        }
        detail = requireText(detail, "detail");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
