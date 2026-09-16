package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Converts one conclusive diagnosis into a bounded proposal without granting repair authority. */
public final class FactoryMaintenanceProposalService {
    public Result propose(FactoryHealthReport report, long expiresAtTick) {
        if (report.status() == FactoryHealthStatus.HEALTHY) {
            return Result.refused("MAINTENANCE_NOT_REQUIRED");
        }
        if (report.status() == FactoryHealthStatus.INCONCLUSIVE
                || !report.unknownCategories().isEmpty()) {
            return Result.refused("MAINTENANCE_EVIDENCE_INCOMPLETE");
        }
        if (report.findings().size() != 1) {
            return Result.refused("MAINTENANCE_MULTI_FAULT_REPLAN_REQUIRED");
        }
        if (expiresAtTick <= report.observedTick()
                || expiresAtTick - report.observedTick()
                        > FactoryMaintenanceProposal.MAX_LIFETIME_TICKS) {
            return Result.refused("MAINTENANCE_EXPIRY_INVALID");
        }
        FactoryDiagnosis finding = report.findings().get(0);
        String diagnosticHash = hash(canonical(report));
        ResourceId proposalId = ResourceId.parse("steve_industrial:maintenance/"
                + diagnosticHash.substring(0, 32));
        FactoryMaintenanceProposal proposal = new FactoryMaintenanceProposal(
                proposalId, report.subjectId(), report.observedTick(), expiresAtTick,
                finding.code(), finding.category(), finding.recommendation(),
                finding.evidenceCode(), diagnosticHash,
                FactoryMaintenanceProposal.State.AWAITING_EXPLICIT_APPROVAL,
                false, false, false);
        return new Result(true, "MAINTENANCE_PROPOSAL_READY", Optional.of(proposal));
    }

    static String canonical(FactoryHealthReport report) {
        StringBuilder value = new StringBuilder();
        // observedTick is bound separately by the proposal. Excluding it here lets a
        // later read-only diagnosis prove that the exact same fault scope still exists.
        value.append(report.subjectId()).append('|').append(report.status()).append('\n');
        for (FactoryHealthObservation observation : report.observations()) {
            value.append(observation.category()).append('|').append(observation.state()).append('|')
                    .append(observation.source()).append('|').append(observation.evidenceCode())
                    .append('|').append(observation.detail()).append('\n');
            observation.metrics().forEach((key, metric) -> value.append("m|").append(key)
                    .append('=').append(metric).append('\n'));
            observation.resources().forEach(resource -> value.append("r|").append(resource)
                    .append('\n'));
        }
        FactoryDiagnosis finding = report.findings().get(0);
        return value.append("f|").append(finding.code()).append('|').append(finding.category())
                .append('|').append(finding.recommendation()).append('|')
                .append(finding.evidenceCode()).append('\n').toString();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static boolean matchesFreshDiagnosis(
            FactoryMaintenanceProposal proposal, FactoryHealthReport fresh) {
        if (fresh.observedTick() < proposal.diagnosticTick()
                || fresh.status() != FactoryHealthStatus.FAULTED
                || !fresh.unknownCategories().isEmpty() || fresh.findings().size() != 1) {
            return false;
        }
        FactoryDiagnosis finding = fresh.findings().get(0);
        return fresh.subjectId().equals(proposal.subjectId())
                && finding.code() == proposal.faultCode()
                && finding.category() == proposal.category()
                && finding.recommendation() == proposal.recommendedAction()
                && finding.evidenceCode().equals(proposal.evidenceCode())
                && hash(canonical(fresh)).equals(proposal.diagnosticHash());
    }

    public record Result(boolean proposed, String code,
            Optional<FactoryMaintenanceProposal> proposal) {
        public Result {
            if (code == null || code.isBlank() || proposal == null
                    || proposed != proposal.isPresent()) {
                throw new IllegalArgumentException("maintenance proposal result is invalid");
            }
        }

        static Result refused(String code) { return new Result(false, code, Optional.empty()); }
    }
}
