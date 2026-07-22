package dev.stevecreate.agent.core.planning;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adapter-owned opaque version bounds plus exact accepted runtime fingerprints.
 *
 * <p>The core deliberately does not compare these strings because version semantics belong to the
 * adapter that declared them.</p>
 */
public record CapabilityVersionLimits(
        Optional<String> minimumInclusive,
        Optional<String> maximumExclusive,
        Set<String> acceptedRuntimeFingerprints) {
    public static final int MAX_VERSION_LENGTH = 128;
    public static final int MAX_FINGERPRINTS = 32;

    public CapabilityVersionLimits {
        minimumInclusive = copyOptional(minimumInclusive, "minimumInclusive");
        maximumExclusive = copyOptional(maximumExclusive, "maximumExclusive");
        Objects.requireNonNull(acceptedRuntimeFingerprints, "acceptedRuntimeFingerprints");
        if (acceptedRuntimeFingerprints.size() > MAX_FINGERPRINTS) {
            throw new IllegalArgumentException("acceptedRuntimeFingerprints exceeds its bound");
        }
        TreeSet<String> fingerprints = new TreeSet<>();
        for (String value : acceptedRuntimeFingerprints) {
            fingerprints.add(requireText(value, "acceptedRuntimeFingerprints element"));
        }
        acceptedRuntimeFingerprints = Collections.unmodifiableSet(new LinkedHashSet<>(fingerprints));
        if (minimumInclusive.isEmpty()
                && maximumExclusive.isEmpty()
                && acceptedRuntimeFingerprints.isEmpty()) {
            throw new IllegalArgumentException("At least one explicit version limit is required");
        }
        if (minimumInclusive.isPresent()
                && minimumInclusive.equals(maximumExclusive)) {
            throw new IllegalArgumentException("Version bounds cannot be identical");
        }
    }

    private static Optional<String> copyOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(item -> requireText(item, name));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_VERSION_LENGTH + " characters");
        }
        return value;
    }
}
