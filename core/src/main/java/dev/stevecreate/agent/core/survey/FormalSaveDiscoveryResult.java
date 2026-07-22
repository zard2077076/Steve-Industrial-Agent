package dev.stevecreate.agent.core.survey;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FormalSaveDiscoveryResult(
        String canonicalSavesRoot,
        List<FormalSaveCandidate> candidates,
        FormalSaveCandidate selected,
        List<FormalSurveyFailure> failures) {
    public FormalSaveDiscoveryResult {
        canonicalSavesRoot = text(canonicalSavesRoot);
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (candidates.size() > 256) throw new IllegalArgumentException("too many candidates");
        if (selected != null && !candidates.contains(selected)) {
            throw new IllegalArgumentException("selected candidate is not in candidates");
        }
        if (selected != null && !failures.isEmpty()) {
            throw new IllegalArgumentException("a failed discovery cannot select a world");
        }
    }

    public Optional<FormalSaveCandidate> selectedCandidate() {
        return Optional.ofNullable(selected);
    }

    public boolean uniquelySelected() {
        return selected != null && failures.isEmpty();
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "canonicalSavesRoot");
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException("canonicalSavesRoot is invalid");
        }
        return value;
    }
}
