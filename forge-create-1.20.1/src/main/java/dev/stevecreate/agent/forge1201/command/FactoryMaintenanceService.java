package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.diagnostic.FactoryHealthCategory;
import dev.stevecreate.agent.core.diagnostic.FactoryHealthReport;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApproval;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApprovalLedger;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposal;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceProposalService;
import dev.stevecreate.agent.core.diagnostic.FactoryRecommendedAction;
import dev.stevecreate.agent.core.industrial.MetalPressOrderStage;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020.ImmersiveEngineeringV1020MetalPressProduction;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceApprovalSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceExecutionSavedData;
import dev.stevecreate.agent.forge1201.industrial.FactoryMaintenanceProposalSavedData;
import dev.stevecreate.agent.forge1201.industrial.MetalPressOrderSavedData;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** First bounded A-MAINT-01 executor: restore only an owned Metal Press thermal source pair. */
public final class FactoryMaintenanceService {
    private static final FactoryMaintenanceProposalService PROPOSALS =
            new FactoryMaintenanceProposalService();
    private FactoryMaintenanceService() {}

    public static ProposalResult proposeOwnedMetalPressPower(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        FactoryMaintenanceProposalSavedData saved =
                FactoryMaintenanceProposalSavedData.forLevel(player.serverLevel());
        MetalPressOrderSavedData.StoredOrder stored = FactoryDiagnosticService
                .latestMetalPress(player);
        if (stored == null) {
            saved.remove(player.getUUID());
            return ProposalResult.refused("MAINTENANCE_SUBJECT_NOT_OWNED");
        }
        if (stored.order().stage() != MetalPressOrderStage.POWER_VERIFIED) {
            saved.remove(player.getUUID());
            return ProposalResult.refused("MAINTENANCE_METAL_PRESS_STAGE_NOT_ELIGIBLE");
        }
        dev.stevecreate.agent.core.model.ResourceId subject =
                dev.stevecreate.agent.core.model.ResourceId.parse(
                        "steve_industrial:ie_order/"
                                + stored.order().orderId().toString().replace("-", ""));
        FactoryHealthReport report = FactoryDiagnosticService
                .diagnoseOwnedMetalPressPower(player, subject).orElse(null);
        if (report == null) {
            saved.remove(player.getUUID());
            return ProposalResult.refused("MAINTENANCE_DIAGNOSIS_UNAVAILABLE");
        }
        var result = PROPOSALS.propose(report,
                player.serverLevel().getGameTime() + 1_200);
        if (!result.proposed()) {
            saved.remove(player.getUUID());
            return ProposalResult.refused(result.code());
        }
        FactoryMaintenanceProposal proposal = result.proposal().orElseThrow();
        if (!supportedPowerRestore(proposal)) {
            saved.remove(player.getUUID());
            return ProposalResult.refused("MAINTENANCE_ACTION_NOT_SUPPORTED");
        }
        saved.put(player.getUUID(), proposal);
        return new ProposalResult(true, result.code(), Optional.of(proposal));
    }

    public static Optional<FactoryMaintenanceProposal> currentProposal(ServerPlayer player) {
        FactoryMaintenanceProposalSavedData saved =
                FactoryMaintenanceProposalSavedData.forLevel(player.serverLevel());
        FactoryMaintenanceProposal proposal = saved.proposal(player.getUUID()).orElse(null);
        if (proposal == null) return Optional.empty();
        MetalPressOrderSavedData.StoredOrder stored = FactoryDiagnosticService
                .latestMetalPress(player);
        FactoryMaintenanceExecutionSavedData.Entry execution =
                FactoryMaintenanceExecutionSavedData.forLevel(player.serverLevel())
                        .entry(proposal.proposalId()).orElse(null);
        boolean transactionInFlight = execution != null
                && execution.state() != FactoryMaintenanceExecutionSavedData.State.VERIFIED
                && execution.playerId().equals(player.getUUID())
                && execution.diagnosticHash().equals(proposal.diagnosticHash());
        boolean current = (player.serverLevel().getGameTime() < proposal.expiresAtTick()
                    || transactionInFlight)
                && stored != null && stored.order().stage() == MetalPressOrderStage.POWER_VERIFIED
                && FactoryDiagnosticService.metalPressSubject(stored.order())
                        .equals(proposal.subjectId())
                && supportedPowerRestore(proposal);
        if (!current) {
            saved.remove(player.getUUID());
            return Optional.empty();
        }
        return Optional.of(proposal);
    }

    public static DismissResult dismissCurrent(ServerPlayer player) {
        FactoryMaintenanceProposalSavedData saved =
                FactoryMaintenanceProposalSavedData.forLevel(player.serverLevel());
        FactoryMaintenanceProposal proposal = saved.proposal(player.getUUID()).orElse(null);
        if (proposal == null) return new DismissResult(false, "MAINTENANCE_PROPOSAL_MISSING");
        FactoryMaintenanceExecutionSavedData.Entry execution =
                FactoryMaintenanceExecutionSavedData.forLevel(player.serverLevel())
                        .entry(proposal.proposalId()).orElse(null);
        if (execution != null
                && execution.state() != FactoryMaintenanceExecutionSavedData.State.VERIFIED) {
            return new DismissResult(false, "MAINTENANCE_EXECUTION_IN_PROGRESS");
        }
        saved.remove(player.getUUID());
        return new DismissResult(true, "MAINTENANCE_PROPOSAL_DISMISSED");
    }

    public static Optional<FactoryMaintenanceExecutionSavedData.Entry> currentExecution(
            ServerPlayer player) {
        FactoryMaintenanceProposal proposal = currentProposal(player).orElse(null);
        if (proposal == null) return Optional.empty();
        return FactoryMaintenanceExecutionSavedData.forLevel(player.serverLevel())
                .entry(proposal.proposalId());
    }

    public static FactoryMaintenanceApprovalLedger.Result approveCurrent(ServerPlayer player) {
        FactoryMaintenanceProposal proposal = currentProposal(player).orElse(null);
        if (proposal == null) return new FactoryMaintenanceApprovalLedger.Result(false,
                "MAINTENANCE_PROPOSAL_MISSING", Optional.empty());
        return approve(player, proposal);
    }

    public static Result executeCurrent(ServerPlayer player) {
        FactoryMaintenanceProposal proposal = currentProposal(player).orElse(null);
        if (proposal == null) return Result.refused("MAINTENANCE_PROPOSAL_MISSING");
        Result result = executeOwnedMetalPressPowerRestore(player, proposal);
        if (result.success()) FactoryMaintenanceProposalSavedData.forLevel(player.serverLevel())
                .remove(player.getUUID());
        return result;
    }

    public static FactoryMaintenanceApprovalLedger.Result approve(
            ServerPlayer player, FactoryMaintenanceProposal proposal) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(proposal, "proposal");
        FactoryMaintenanceApprovalSavedData data =
                FactoryMaintenanceApprovalSavedData.forLevel(player.serverLevel());
        FactoryMaintenanceApprovalLedger ledger = data.ledger();
        FactoryMaintenanceApprovalLedger.Result result = ledger.approve(
                proposal, player.getUUID(), player.serverLevel().getGameTime());
        if (result.success()) data.put(ledger);
        return result;
    }

    public static Result executeOwnedMetalPressPowerRestore(
            ServerPlayer player, FactoryMaintenanceProposal proposal) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(proposal, "proposal");
        if (!supportedPowerRestore(proposal)) {
            return Result.refused("MAINTENANCE_ACTION_NOT_SUPPORTED");
        }
        MetalPressOrderSavedData.StoredOrder stored = FactoryDiagnosticService
                .latestMetalPress(player);
        if (stored == null || stored.order().stage() != MetalPressOrderStage.POWER_VERIFIED) {
            return Result.refused("MAINTENANCE_METAL_PRESS_STAGE_NOT_ELIGIBLE");
        }
        Optional<FactoryHealthReport> fresh = FactoryDiagnosticService
                .diagnoseOwnedMetalPressPower(player, proposal.subjectId());
        if (fresh.isEmpty()) return Result.refused("MAINTENANCE_SUBJECT_NOT_OWNED");

        BlockPos origin = block(stored.order().machineOrigin());
        FactoryMaintenanceApprovalSavedData approvals =
                FactoryMaintenanceApprovalSavedData.forLevel(player.serverLevel());
        FactoryMaintenanceExecutionSavedData executions =
                FactoryMaintenanceExecutionSavedData.forLevel(player.serverLevel());
        FactoryMaintenanceApprovalLedger ledger = approvals.ledger();
        int before = ImmersiveEngineeringV1020MetalPressProduction
                .thermalGenerationSourceCount(player.serverLevel(), origin);
        FactoryMaintenanceExecutionSavedData.Entry execution = executions
                .entry(proposal.proposalId()).orElse(null);

        if (execution == null) {
            var preflight = ImmersiveEngineeringV1020MetalPressProduction
                    .thermalGenerationStartPreflight(player.serverLevel(), origin);
            if (!preflight.success()) return Result.refused(preflight.code());
            // A new transaction may only claim an entirely empty pair. A partial pair is
            // recoverable solely when a durable PREPARED transaction already owns it.
            if (before != 0) return Result.refused("THERMAL_SOURCE_PARTIAL_WITHOUT_JOURNAL");
            if (!executions.canPrepare(proposal.proposalId())) {
                return Result.refused("MAINTENANCE_EXECUTION_JOURNAL_FULL");
            }
            FactoryMaintenanceApprovalLedger.Result consumed = ledger.consume(proposal,
                    fresh.orElseThrow(), player.getUUID(), player.serverLevel().getGameTime());
            if (!consumed.success()) return Result.refused(consumed.code());
            approvals.put(ledger);
            execution = executions.prepare(proposal.proposalId(), player.getUUID(),
                    proposal.diagnosticHash(), player.serverLevel().getGameTime());
            // PREPARED and CONSUMED reach disk together before the first world mutation.
            persistMaintenanceBoundary(player);
        } else {
            Result scope = validateRecoverableExecution(player, proposal, execution, ledger);
            if (scope != null) return scope;
            if (execution.state() == FactoryMaintenanceExecutionSavedData.State.VERIFIED) {
                return Result.refused("MAINTENANCE_EXECUTION_ALREADY_VERIFIED");
            }
            if (execution.state() == FactoryMaintenanceExecutionSavedData.State.APPLIED) {
                if (before != 2) return Result.refused("MAINTENANCE_APPLIED_WORLD_DRIFT");
                FactoryHealthReport post = fresh.orElseThrow();
                if (post.status()
                        != dev.stevecreate.agent.core.diagnostic.FactoryHealthStatus.HEALTHY) {
                    return new Result(false, "MAINTENANCE_POST_DIAGNOSIS_NOT_HEALTHY", 0,
                            Optional.of(post));
                }
                executions.transition(proposal.proposalId(),
                        FactoryMaintenanceExecutionSavedData.State.VERIFIED,
                        player.serverLevel().getGameTime(), 2);
                persistMaintenanceBoundary(player);
                return new Result(true, "MAINTENANCE_RECOVERY_VERIFIED", 0,
                        Optional.of(post));
            }
        }

        if (before < 0 || before > 2) return Result.refused("THERMAL_SOURCE_PROBE_INVALID");
        if (before < 2) {
            var preflight = ImmersiveEngineeringV1020MetalPressProduction
                    .thermalGenerationStartPreflight(player.serverLevel(), origin);
            if (!preflight.success()) return Result.refused(preflight.code());
            var action = ImmersiveEngineeringV1020MetalPressProduction
                    .startThermalGeneration(player.serverLevel(), origin);
            if (!action.success()) return new Result(false, action.code(), 0, Optional.empty());
        }
        int after = ImmersiveEngineeringV1020MetalPressProduction
                .thermalGenerationSourceCount(player.serverLevel(), origin);
        if (after != 2) {
            return new Result(false, "MAINTENANCE_WORLD_MUTATION_NOT_EXACT", 0,
                    Optional.empty());
        }
        executions.transition(proposal.proposalId(),
                FactoryMaintenanceExecutionSavedData.State.APPLIED,
                player.serverLevel().getGameTime(), 2);
        // If the server stops after this save, reload verifies instead of mutating twice.
        persistMaintenanceBoundary(player);
        FactoryHealthReport post = FactoryDiagnosticService
                .diagnoseOwnedMetalPressPower(player, proposal.subjectId()).orElseThrow();
        if (post.status() != dev.stevecreate.agent.core.diagnostic.FactoryHealthStatus.HEALTHY) {
            return new Result(false, "MAINTENANCE_POST_DIAGNOSIS_NOT_HEALTHY",
                    Math.max(0, after - before), Optional.of(post));
        }
        executions.transition(proposal.proposalId(),
                FactoryMaintenanceExecutionSavedData.State.VERIFIED,
                player.serverLevel().getGameTime(), 2);
        persistMaintenanceBoundary(player);
        return new Result(true, "MAINTENANCE_METAL_PRESS_POWER_RESTORED",
                Math.max(0, after - before), Optional.of(post));
    }

    private static Result validateRecoverableExecution(ServerPlayer player,
            FactoryMaintenanceProposal proposal,
            FactoryMaintenanceExecutionSavedData.Entry execution,
            FactoryMaintenanceApprovalLedger ledger) {
        if (!execution.playerId().equals(player.getUUID())
                || !execution.diagnosticHash().equals(proposal.diagnosticHash())) {
            return Result.refused("MAINTENANCE_EXECUTION_SCOPE_MISMATCH");
        }
        FactoryMaintenanceApproval approval = ledger.approvals().get(proposal.proposalId());
        if (approval == null || approval.state() != FactoryMaintenanceApproval.State.CONSUMED
                || !approval.playerId().equals(player.getUUID())
                || !approval.diagnosticHash().equals(proposal.diagnosticHash())
                || approval.faultCode() != proposal.faultCode()
                || approval.recommendedAction() != proposal.recommendedAction()) {
            return Result.refused("MAINTENANCE_EXECUTION_AUTHORITY_MISSING");
        }
        return null;
    }

    private static void persistMaintenanceBoundary(ServerPlayer player) {
        player.serverLevel().getServer().overworld().getDataStorage().save();
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static boolean supportedPowerRestore(FactoryMaintenanceProposal proposal) {
        return proposal.category() == FactoryHealthCategory.ENERGY_SUPPLY
                && proposal.recommendedAction() == FactoryRecommendedAction.RESTORE_POWER
                && proposal.evidenceCode().equals("PLAN_FE_POWER_INSUFFICIENT");
    }

    public record Result(boolean success, String code, int worldMutations,
            Optional<FactoryHealthReport> postDiagnosis) {
        public Result {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(postDiagnosis, "postDiagnosis");
            if (code.isBlank() || worldMutations < 0
                    || success && postDiagnosis.isEmpty()) {
                throw new IllegalArgumentException("maintenance execution result is invalid");
            }
        }

        static Result refused(String code) {
            return new Result(false, code, 0, Optional.empty());
        }
    }

    public record ProposalResult(boolean success, String code,
            Optional<FactoryMaintenanceProposal> proposal) {
        public ProposalResult {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(proposal, "proposal");
            if (code.isBlank() || success != proposal.isPresent()) {
                throw new IllegalArgumentException("maintenance proposal result is invalid");
            }
        }

        static ProposalResult refused(String code) {
            return new ProposalResult(false, code, Optional.empty());
        }
    }

    public record DismissResult(boolean success, String code) {
        public DismissResult {
            Objects.requireNonNull(code, "code");
            if (code.isBlank()) throw new IllegalArgumentException("dismiss code is blank");
        }
    }
}
