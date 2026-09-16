package dev.stevecreate.agent.core.diagnostic;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Append-only authority boundary between read-only diagnosis and a future executor. */
public final class FactoryMaintenanceApprovalLedger {
    private final Map<ResourceId, FactoryMaintenanceApproval> approvals = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private long generation;

    public synchronized Result approve(
            FactoryMaintenanceProposal proposal, UUID playerId, long tick) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(playerId, "playerId");
        if (tick < proposal.diagnosticTick() || tick >= proposal.expiresAtTick()) {
            return Result.refused("MAINTENANCE_PROPOSAL_EXPIRED");
        }
        FactoryMaintenanceApproval existing = approvals.get(proposal.proposalId());
        if (existing != null) {
            boolean exactReplay = existing.playerId().equals(playerId)
                    && existing.diagnosticHash().equals(proposal.diagnosticHash())
                    && existing.faultCode() == proposal.faultCode()
                    && existing.recommendedAction() == proposal.recommendedAction();
            return exactReplay && existing.state() == FactoryMaintenanceApproval.State.APPROVED
                    ? new Result(true, "MAINTENANCE_APPROVAL_ALREADY_RECORDED",
                            Optional.of(existing))
                    : Result.refused("MAINTENANCE_APPROVAL_IDENTITY_REUSED");
        }
        FactoryMaintenanceApproval approval = new FactoryMaintenanceApproval(
                proposal.proposalId(), playerId, proposal.diagnosticHash(),
                proposal.faultCode(), proposal.recommendedAction(), tick,
                proposal.expiresAtTick(), FactoryMaintenanceApproval.State.APPROVED, 1);
        approvals.put(proposal.proposalId(), approval);
        journal("APPROVED", approval, tick);
        return new Result(true, "MAINTENANCE_APPROVED", Optional.of(approval));
    }

    public synchronized Result consume(
            FactoryMaintenanceProposal proposal, FactoryHealthReport freshDiagnosis,
            UUID playerId, long tick) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(freshDiagnosis, "freshDiagnosis");
        Objects.requireNonNull(playerId, "playerId");
        FactoryMaintenanceApproval approval = approvals.get(proposal.proposalId());
        if (approval == null) return Result.refused("MAINTENANCE_APPROVAL_MISSING");
        if (approval.state() == FactoryMaintenanceApproval.State.CONSUMED) {
            return Result.refused("MAINTENANCE_APPROVAL_ALREADY_CONSUMED");
        }
        if (!approval.playerId().equals(playerId)
                || !approval.diagnosticHash().equals(proposal.diagnosticHash())
                || approval.faultCode() != proposal.faultCode()
                || approval.recommendedAction() != proposal.recommendedAction()) {
            return Result.refused("MAINTENANCE_APPROVAL_SCOPE_MISMATCH");
        }
        if (tick >= approval.expiresAtTick()
                || freshDiagnosis.observedTick() < approval.approvedAtTick()
                || freshDiagnosis.observedTick() > tick) {
            return Result.refused("MAINTENANCE_APPROVAL_OR_DIAGNOSIS_EXPIRED");
        }
        if (!FactoryMaintenanceProposalService.matchesFreshDiagnosis(proposal, freshDiagnosis)) {
            return Result.refused("MAINTENANCE_FRESH_DIAGNOSIS_MISMATCH");
        }
        FactoryMaintenanceApproval consumed = approval.consume(tick);
        approvals.put(proposal.proposalId(), consumed);
        journal("CONSUMED", consumed, tick);
        return new Result(true, "MAINTENANCE_APPROVAL_CONSUMED", Optional.of(consumed));
    }

    public synchronized Map<ResourceId, FactoryMaintenanceApproval> approvals() {
        return Map.copyOf(approvals);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(approvals, journal, generation);
    }

    public static FactoryMaintenanceApprovalLedger restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        FactoryMaintenanceApprovalLedger ledger = new FactoryMaintenanceApprovalLedger();
        ledger.approvals.putAll(snapshot.approvals());
        ledger.journal.addAll(snapshot.journal());
        ledger.generation = snapshot.generation();
        for (FactoryMaintenanceApproval approval : ledger.approvals.values()) {
            int approved = ledger.eventCount(approval, "APPROVED");
            int consumed = ledger.eventCount(approval, "CONSUMED");
            if (approved != 1 || consumed != (approval.state()
                    == FactoryMaintenanceApproval.State.CONSUMED ? 1 : 0)) {
                throw new IllegalArgumentException(
                        "maintenance approval journal does not match authority state");
            }
        }
        if (ledger.journal.size() != ledger.approvals.size()
                + ledger.approvals.values().stream().filter(value -> value.state()
                        == FactoryMaintenanceApproval.State.CONSUMED).count()) {
            throw new IllegalArgumentException("maintenance approval journal has orphan events");
        }
        long expectedGeneration = 1;
        for (String row : ledger.journal) {
            String[] parts = row.split("\\|", 6);
            if (parts.length != 6 || Long.parseLong(parts[0]) != expectedGeneration++) {
                throw new IllegalArgumentException("maintenance approval journal order is invalid");
            }
        }
        if (ledger.generation != ledger.journal.size()) {
            throw new IllegalArgumentException("maintenance approval generation drifted");
        }
        return ledger;
    }

    private void journal(String event, FactoryMaintenanceApproval approval, long tick) {
        if (journal.size() >= 16_384) {
            throw new IllegalStateException("maintenance approval journal is full");
        }
        journal.add(++generation + "|" + tick + "|" + event + "|"
                + approval.proposalId() + "|" + approval.playerId() + "|"
                + approval.diagnosticHash());
    }

    private int eventCount(FactoryMaintenanceApproval approval, String event) {
        int count = 0;
        for (String row : journal) {
            String[] parts = row.split("\\|", 6);
            if (parts.length == 6 && parts[2].equals(event)
                    && parts[3].equals(approval.proposalId().toString())
                    && parts[4].equals(approval.playerId().toString())
                    && parts[5].equals(approval.diagnosticHash())) count++;
        }
        return count;
    }

    public record Snapshot(
            Map<ResourceId, FactoryMaintenanceApproval> approvals,
            List<String> journal,
            long generation) {
        public Snapshot {
            approvals = Map.copyOf(Objects.requireNonNull(approvals, "approvals"));
            journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
            if (approvals.size() > 8_192 || journal.size() > 16_384 || generation < 0
                    || journal.size() > generation) {
                throw new IllegalArgumentException("maintenance approval snapshot is invalid");
            }
        }
    }

    public record Result(boolean success, String code,
            Optional<FactoryMaintenanceApproval> approval) {
        public Result {
            if (code == null || code.isBlank() || approval == null
                    || success != approval.isPresent()) {
                throw new IllegalArgumentException("maintenance approval result is invalid");
            }
        }
        static Result refused(String code) { return new Result(false, code, Optional.empty()); }
    }
}
