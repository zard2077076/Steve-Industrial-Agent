package dev.stevecreate.agent.adapter.api;

import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;

/** Exact runtime identity attached to every snapshot and executable plan. */
public record RuntimeFingerprint(
        String minecraftVersion,
        String loader,
        String loaderVersion,
        Map<String, String> industrialModVersions,
        String adapterId,
        int normalizationSchemaVersion) {
    public RuntimeFingerprint {
        requireText(minecraftVersion, "minecraftVersion");
        requireText(loader, "loader");
        requireText(loaderVersion, "loaderVersion");
        industrialModVersions = Map.copyOf(Objects.requireNonNull(industrialModVersions, "industrialModVersions"));
        requireText(adapterId, "adapterId");
        if (normalizationSchemaVersion < 1) {
            throw new IllegalArgumentException("normalizationSchemaVersion must be positive");
        }
    }

    /** Canonical, insertion-order-independent identity suitable for catalog provenance and cache keys. */
    public String canonicalIdentity() {
        StringBuilder value = new StringBuilder(192);
        append(value, "minecraft", minecraftVersion);
        append(value, "loader", loader);
        append(value, "loaderVersion", loaderVersion);
        value.append("mods[");
        new TreeMap<>(industrialModVersions).forEach((modId, version) -> {
            append(value, "mod", modId);
            append(value, "version", version);
        });
        value.append("];");
        append(value, "adapter", adapterId);
        value.append("schema=").append(normalizationSchemaVersion).append(';');
        return value.toString();
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append(name).append('=').append(value.length()).append(':').append(value).append(';');
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
