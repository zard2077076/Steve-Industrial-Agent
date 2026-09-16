package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.industrial.IndustrialCompletionReportV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderPlanV1;
import dev.stevecreate.agent.core.execution.construction.MaterialTransactionState;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.SingleMachineGoalResolver;
import dev.stevecreate.agent.forge1201.player.MaterialLedgerProjection;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;

/** Bridges the ordinary player workflow into the common durable order envelope. */
public final class IndustrialPlayerOrderService {
    private IndustrialPlayerOrderService() {}

    public static SyncResult ensurePlan(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            PlayerMaterialSavedData.Entry materials,
            String worldIdentity) {
        if (project.anchor() == null || !project.siteSnapshotHash().matches("[0-9a-f]{64}")) {
            return SyncResult.failure("INDUSTRIAL_ORDER_BASELINE_MISSING");
        }
        // The player can select a live-registry-derived target from search. Re-resolve
        // against that same catalog when binding the durable envelope instead of silently
        // shrinking authority back to the reviewed eleven.
        var goal = SingleMachineGoalResolver.resolve(player.serverLevel(), project.target())
                .orElse(null);
        if (goal == null) return SyncResult.failure("INDUSTRIAL_ORDER_TARGET_UNKNOWN");
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel());
        Optional<IndustrialPlayerOrderV1> existing = data.order(project.projectId());
        if (existing.isPresent()) return SyncResult.ok();
        long now = Instant.now().toEpochMilli();
        ResourceId orderType = ResourceId.parse("steve_industrial:player_" + goal.capability().path());
        IndustrialPlayerOrderV1 order = new IndustrialPlayerOrderV1(
                project.projectId(), project.projectId(), project.playerId(), worldIdentity,
                project.dimension(), orderType, project.target(), goal.recipe(), project.anchor(),
                materials.planHash(), materials.runtimeFingerprint(), project.siteSnapshotHash(),
                IndustrialLifecyclePhase.PLANNED, "MATERIAL_SOURCE_SELECTION", Set.of(),
                project.createdAt(), now, 0, Optional.empty());
        data.put(order);
        return SyncResult.ok();
    }

    /** Binds a verified Composite/FE/fluid plan to the same durable player-order identity. */
    public static SyncResult bindPlan(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            IndustrialPlayerOrderPlanV1 plan,
            String worldIdentity) {
        return bindPlan(player, new OrderIdentity(project.projectId(), project.playerId(),
                project.dimension(), project.anchor(), project.siteSnapshotHash(),
                project.createdAt()), plan, worldIdentity);
    }

    /**
     * Binds a verified plan for an order that does not come from the frozen ordinary
     * workflow.  Composite and IE orders own their own durable state but must still
     * land in this one envelope, so the identity a caller must prove is stated
     * explicitly instead of being borrowed from a workflow project entry.
     */
    public static SyncResult bindPlan(
            ServerPlayer player,
            OrderIdentity identity,
            IndustrialPlayerOrderPlanV1 plan,
            String worldIdentity) {
        if (!identity.ownerId().equals(player.getUUID())
                || !ResourceId.parse("player_project:"
                        + identity.projectId().toString().replace("-", ""))
                                .equals(plan.projectId())) {
            return SyncResult.failure("INDUSTRIAL_ORDER_OWNER_OR_PROJECT_MISMATCH");
        }
        if (identity.anchor() == null || !identity.siteSnapshotHash().matches("[0-9a-f]{64}")) {
            return SyncResult.failure("INDUSTRIAL_ORDER_BASELINE_MISSING");
        }
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel());
        IndustrialPlayerOrderV1 existing = data.order(identity.projectId()).orElse(null);
        if (existing != null) return existing.orderType().equals(plan.orderType())
                ? SyncResult.ok() : SyncResult.failure("INDUSTRIAL_ORDER_TYPE_FROZEN");
        long now = Instant.now().toEpochMilli();
        IndustrialPlayerOrderV1 order = new IndustrialPlayerOrderV1(
                identity.projectId(), identity.projectId(), player.getUUID(), worldIdentity,
                identity.dimension(), plan.orderType(), plan.target(),
                ResourceId.parse("steve_industrial:composite/" + plan.orderType().path()),
                identity.anchor(), plan.planSha256(), plan.fingerprint(), identity.siteSnapshotHash(),
                IndustrialLifecyclePhase.PLANNED, "PLAN_BOUND", Set.of(), identity.createdAt(), now,
                0, Optional.empty());
        data.put(order);
        return SyncResult.ok();
    }

    /**
     * Binds one reviewed machine recipe without fabricating a Composite plan. The
     * caller still has to prove the same owner, project, site baseline and runtime
     * identities; only a versioned adapter may execute the resulting order.
     */
    public static SyncResult bindReviewedOrder(
            ServerPlayer player,
            OrderIdentity identity,
            ResourceId orderType,
            ResourceId target,
            ResourceId recipeId,
            String planHash,
            String runtimeFingerprint,
            String worldIdentity) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recipeId, "recipeId");
        if (!identity.ownerId().equals(player.getUUID())
                || !identity.dimension().equals(ResourceId.parse(
                        player.serverLevel().dimension().location().toString()))) {
            return SyncResult.failure("INDUSTRIAL_ORDER_OWNER_OR_DIMENSION_MISMATCH");
        }
        if (identity.anchor() == null || !identity.siteSnapshotHash().matches("[0-9a-f]{64}")) {
            return SyncResult.failure("INDUSTRIAL_ORDER_BASELINE_MISSING");
        }
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(
                player.serverLevel());
        IndustrialPlayerOrderV1 existing = data.order(identity.projectId()).orElse(null);
        if (existing != null) {
            boolean same = existing.ownerId().equals(identity.ownerId())
                    && existing.dimension().equals(identity.dimension())
                    && existing.orderType().equals(orderType)
                    && existing.target().equals(target)
                    && existing.recipeId().equals(recipeId)
                    && existing.anchor().equals(identity.anchor())
                    && existing.planHash().equals(planHash)
                    && existing.runtimeFingerprint().equals(runtimeFingerprint)
                    && existing.baselineHash().equals(identity.siteSnapshotHash())
                    && existing.worldIdentity().equals(worldIdentity);
            return same ? SyncResult.ok()
                    : SyncResult.failure("INDUSTRIAL_ORDER_BINDING_FROZEN");
        }
        long now = Instant.now().toEpochMilli();
        data.put(new IndustrialPlayerOrderV1(
                identity.projectId(), identity.projectId(), identity.ownerId(), worldIdentity,
                identity.dimension(), orderType, target, recipeId, identity.anchor(), planHash,
                runtimeFingerprint, identity.siteSnapshotHash(), IndustrialLifecyclePhase.PLANNED,
                "MATERIALS_RESERVED", Set.of(ResourceId.parse(
                        "steve_industrial:materials_reserved")), identity.createdAt(), now, 0,
                Optional.empty()));
        return SyncResult.ok();
    }

    /** The identity any order source must prove before it can claim this envelope. */
    public record OrderIdentity(
            UUID projectId,
            UUID ownerId,
            ResourceId dimension,
            dev.stevecreate.agent.core.model.BlockPos3i anchor,
            String siteSnapshotHash,
            long createdAt) {
        public OrderIdentity {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(siteSnapshotHash, "siteSnapshotHash");
        }
    }

    public static SyncResult checkpoint(
            ServerPlayer player,
            UUID projectId,
            IndustrialLifecyclePhase phase,
            String stage,
            Set<ResourceId> effects) {
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel());
        IndustrialPlayerOrderV1 current = data.order(projectId).orElse(null);
        if (current == null) return SyncResult.failure("INDUSTRIAL_ORDER_NOT_FOUND");
        if (current.report().isPresent()) return SyncResult.ok();
        if (current.phase() == phase && current.stage().equals(stage)
                && current.durableEffects().equals(effects)) return SyncResult.ok();
        data.put(current.checkpoint(phase, stage, effects, Instant.now().toEpochMilli()));
        return SyncResult.ok();
    }

    /** Records a player cancellation without fabricating a completion report. */
    public static SyncResult cancel(ServerPlayer player, UUID projectId, boolean returned) {
        return checkpoint(player, projectId, IndustrialLifecyclePhase.CANCELLED,
                returned ? "CANCELLED_MATERIALS_RETURNED" : "CANCELLED_RETURN_PENDING",
                Set.of(ResourceId.parse(returned
                        ? "steve_industrial:materials_returned"
                        : "steve_industrial:return_pending")));
    }

    /**
     * Records an already settled typed report.  Composite and other non-workflow order
     * sources derive their own evidence from a real wrapper, so they attach the
     * finished report instead of asking this service to project a legacy player one.
     */
    public static SyncResult completeWithReport(
            ServerPlayer player, UUID projectId, IndustrialCompletionReportV1 report) {
        Objects.requireNonNull(report, "report");
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel());
        IndustrialPlayerOrderV1 current = data.order(projectId).orElse(null);
        if (current == null) return SyncResult.failure("INDUSTRIAL_ORDER_NOT_FOUND");
        if (current.report().isPresent()) return SyncResult.ok();
        if (!report.baselineHash().equals(current.baselineHash())) {
            return SyncResult.failure("INDUSTRIAL_ORDER_BASELINE_MISMATCH");
        }
        data.put(current.withReport(report, Instant.now().toEpochMilli()));
        return SyncResult.ok();
    }

    public static SyncResult complete(
            ServerPlayer player,
            PlayerWorkflowSavedData.ProjectEntry project,
            PlayerMaterialSavedData.Entry materials,
            boolean baselineRestored) {
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel());
        IndustrialPlayerOrderV1 current = data.order(project.projectId()).orElse(null);
        if (current == null) return SyncResult.failure("INDUSTRIAL_ORDER_NOT_FOUND");
        if (current.report().isPresent()) return SyncResult.ok();
        PlayerMaterialSavedData.CompletionReport source = materials.report();
        if (source == null) return SyncResult.failure("INDUSTRIAL_ORDER_REPORT_MISSING");
        MaterialEvidence evidence = materialEvidence(materials);
        IndustrialCompletionReportV1 report = IndustrialCompletionReportV1.create(
                materials.requirements(), evidence.withdrawn(), evidence.consumed(), evidence.returned(),
                Map.of(), Map.of(), Map.of(project.target(), source.observedOutput()),
                source.salvageTransferred(), source.duplicateWithdrawals(), source.duplicateReturns(),
                0, 0, source.unaccountedItems(), source.privateItemsTouched(), baselineRestored,
                current.baselineHash());
        data.put(current.withReport(report, Instant.now().toEpochMilli()));
        return SyncResult.ok();
    }

    /** Delegates to the one tested reading of the ledger. */
    private static MaterialEvidence materialEvidence(PlayerMaterialSavedData.Entry entry) {
        MaterialLedgerProjection.Evidence evidence = MaterialLedgerProjection.project(entry);
        return new MaterialEvidence(
                evidence.withdrawn(), evidence.consumed(), evidence.returned());
    }

    private record MaterialEvidence(
            Map<ResourceId, Long> withdrawn,
            Map<ResourceId, Long> consumed,
            Map<ResourceId, Long> returned) {
        private MaterialEvidence {
            withdrawn = Map.copyOf(Objects.requireNonNull(withdrawn));
            consumed = Map.copyOf(Objects.requireNonNull(consumed));
            returned = Map.copyOf(Objects.requireNonNull(returned));
        }
    }

    /** On a fresh JVM, leave every non-terminal envelope paused until its adapter rescans. */
    public static void recoverServer(MinecraftServer server) {
        ServerLevel level = server.overworld();
        IndustrialPlayerOrderSavedData data = IndustrialPlayerOrderSavedData.forLevel(level);
        for (IndustrialPlayerOrderV1 order : data.orders().values()) {
            if (order.phase() == IndustrialLifecyclePhase.COMPLETED
                    || order.phase() == IndustrialLifecyclePhase.RECOVERED
                    || order.phase() == IndustrialLifecyclePhase.CANCELLED
                    || order.phase() == IndustrialLifecyclePhase.FAILED) continue;
            try {
                data.put(order.checkpoint(IndustrialLifecyclePhase.PAUSED,
                        "RELOAD_RECONCILIATION_REQUIRED", order.durableEffects(),
                        Math.max(order.updatedAt(), System.currentTimeMillis())));
            } catch (IllegalArgumentException ignored) {
                // A stale/malformed row remains in SavedData for explicit inspection and cannot run.
            }
        }
    }

    public static void sendStatus(ServerPlayer player) {
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(player.serverLevel())
                .orders().values().stream().filter(value -> value.ownerId().equals(player.getUUID()))
                .sorted(java.util.Comparator.comparing(IndustrialPlayerOrderV1::updatedAt).reversed())
                .findFirst().orElse(null);
        if (order == null) {
            player.sendSystemMessage(Component.literal("industrial order: none"));
            return;
        }
        player.sendSystemMessage(Component.literal("industrial order " + order.orderId()
                + " type=" + order.orderType() + " target=" + order.target()
                + " phase=" + order.phase() + " stage=" + order.stage()
                + " generation=" + order.generation()
                + " report=" + order.report().isPresent()
                + " recovery=server-authoritative"));
    }

    public record SyncResult(boolean success, String code) {
        public SyncResult { if (code == null || code.isBlank()) throw new IllegalArgumentException("order sync code invalid"); }
        static SyncResult ok() { return new SyncResult(true, "OK"); }
        static SyncResult failure(String code) { return new SyncResult(false, code); }
    }
}
