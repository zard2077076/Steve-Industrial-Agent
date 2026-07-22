package dev.stevecreate.agent.core.survey;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pre-sanitized metadata only; this value has no file or database access. */
public record ClaimMetadataDocument(String relativeSourcePath, Map<String, String> metadata) {
    private static final java.util.Set<String> ALLOWED_KEYS = java.util.Set.of(
            "modId", "version", "displayName", "configPath", "dataSource", "apiHint", "protectionEnabled");

    public ClaimMetadataDocument {
        relativeSourcePath = SurveyModelValues.text(relativeSourcePath, "relativeSourcePath", 1_024);
        if (relativeSourcePath.startsWith("/") || relativeSourcePath.contains("\\")
                || relativeSourcePath.contains("..") || relativeSourcePath.contains(":")) {
            throw new IllegalArgumentException("claim metadata source must be a safe relative path");
        }
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.isEmpty() || metadata.size() > 16 || !ALLOWED_KEYS.containsAll(metadata.keySet())) {
            throw new IllegalArgumentException("claim metadata keys are not allowlisted");
        }
        TreeMap<String, String> canonical = new TreeMap<>();
        metadata.forEach((key, value) -> {
            String normalized = SurveyModelValues.text(value, "metadata[" + key + "]", 1_024);
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.contains("token=") || lower.contains("password=") || lower.contains("secret=")
                    || lower.contains("authorization=") || lower.startsWith("bearer ")) {
                throw new IllegalArgumentException("claim metadata cannot contain credential values");
            }
            if ((key.equals("dataSource") || key.equals("configPath"))
                    && (normalized.startsWith("/") || normalized.contains("\\")
                        || normalized.contains("..") || normalized.contains("://")
                        || normalized.matches("^[A-Za-z]:.*"))) {
                throw new IllegalArgumentException("claim metadata cannot expose an external/private data path");
            }
            canonical.put(key, normalized);
        });
        metadata = Map.copyOf(canonical);
    }
}
