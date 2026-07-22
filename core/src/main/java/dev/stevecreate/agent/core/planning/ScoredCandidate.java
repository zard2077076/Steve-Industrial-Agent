package dev.stevecreate.agent.core.planning;

import java.util.Objects;

/** Candidate paired with its complete deterministic score breakdown. */
public record ScoredCandidate(CandidatePlan candidate, PlanScore score) {
    public ScoredCandidate {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(score, "score");
        if (!candidate.candidateId().equals(score.candidateId())) {
            throw new IllegalArgumentException("Candidate and score identities do not match");
        }
    }
}
