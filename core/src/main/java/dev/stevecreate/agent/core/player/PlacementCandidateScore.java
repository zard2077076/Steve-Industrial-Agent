package dev.stevecreate.agent.core.player;

import java.util.List;
import java.util.Objects;

public record PlacementCandidateScore(
        PlacementCandidateFacts candidate,
        boolean recommendedSafe,
        long score,
        List<String> reasons) {
    public PlacementCandidateScore {
        Objects.requireNonNull(candidate, "candidate");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (score < 0 || reasons.isEmpty() || reasons.size() > 8
                || reasons.stream().anyMatch(value -> value.isBlank() || value.length() > 96)) {
            throw new IllegalArgumentException("candidate score evidence is invalid");
        }
    }
}
