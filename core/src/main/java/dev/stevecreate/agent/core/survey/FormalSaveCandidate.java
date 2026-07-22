package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;

public record FormalSaveCandidate(
        FormalWorldIdentity worldIdentity,
        String canonicalWorldPath,
        String metadataSource,
        String levelDatFingerprint,
        long lastPlayedEpochMillis,
        int evidenceScore,
        List<String> rankingEvidence) {
    public FormalSaveCandidate {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        canonicalWorldPath = text(canonicalWorldPath, "canonicalWorldPath");
        metadataSource = text(metadataSource, "metadataSource");
        Objects.requireNonNull(levelDatFingerprint, "levelDatFingerprint");
        if (!levelDatFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("levelDatFingerprint must be lowercase SHA-256 hex");
        }
        if (lastPlayedEpochMillis < 0) throw new IllegalArgumentException("lastPlayedEpochMillis must be non-negative");
        if (evidenceScore < 0 || evidenceScore > 1_000) throw new IllegalArgumentException("evidenceScore is invalid");
        rankingEvidence = List.copyOf(Objects.requireNonNull(rankingEvidence, "rankingEvidence"));
        if (rankingEvidence.isEmpty() || rankingEvidence.size() > 32) {
            throw new IllegalArgumentException("rankingEvidence is empty or too large");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
