package dev.stevecreate.agent.core.formalbackup;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FormalDeploymentCandidateBatch(
        String worldIdentity,
        String backupIdentity,
        List<FormalDeploymentCandidatePackage> candidates,
        int maximumCandidates,
        Optional<String> userSelectedCandidateIdentity,
        boolean topCandidateAutomaticallySelected,
        boolean approvalDecisionCreated,
        boolean executionAllowed) {
    public FormalDeploymentCandidateBatch {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(backupIdentity, "backupIdentity");
        candidates = candidates.stream().sorted(Comparator
                .comparingInt((FormalDeploymentCandidatePackage value) ->
                        value.candidateZone().totalScore()).reversed()
                .thenComparing(FormalDeploymentCandidatePackage::packageIdentity)).toList();
        userSelectedCandidateIdentity = Objects.requireNonNull(
                userSelectedCandidateIdentity, "userSelectedCandidateIdentity");
        if (maximumCandidates < 1 || maximumCandidates > 64 || candidates.isEmpty()
                || candidates.size() > maximumCandidates
                || candidates.stream().map(FormalDeploymentCandidatePackage::packageIdentity).distinct().count()
                        != candidates.size()
                || candidates.stream().anyMatch(candidate ->
                        !candidate.worldIdentity().equals(worldIdentity)
                                || !candidate.backupIdentity().backupIdentity().equals(backupIdentity)
                                || candidate.status() != FormalDeploymentCandidateStatus.PENDING_USER_SELECTION)
                || userSelectedCandidateIdentity.isPresent() || topCandidateAutomaticallySelected
                || approvalDecisionCreated || executionAllowed) {
            throw new IllegalArgumentException("candidate batch cannot select, approve or execute");
        }
    }
}
