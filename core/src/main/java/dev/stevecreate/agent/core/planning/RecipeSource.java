package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.regex.Pattern;

/** Loader-neutral attribution and runtime identity for one catalog recipe. */
public record RecipeSource(
        ResourceId adapterId,
        String sourceModId,
        String runtimeFingerprint,
        boolean runtimeVerified) {
    public static final int MAX_FINGERPRINT_LENGTH = 512;
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public RecipeSource {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(sourceModId, "sourceModId");
        if (!MOD_ID.matcher(sourceModId).matches()) {
            throw new IllegalArgumentException("Invalid source mod ID: " + sourceModId);
        }
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank()
                || runtimeFingerprint.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException(
                    "runtimeFingerprint must contain 1 to " + MAX_FINGERPRINT_LENGTH + " characters");
        }
    }
}
