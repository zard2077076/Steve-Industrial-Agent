package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.MaterialExecutorKind;
import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A visible bot carrying material from a warehouse to a site, before any order exists.
 *
 * <p>This is the shape C5-A settled on after three worse ones. The obvious approach —
 * carry material <em>inside</em> the order, between reservation and withdrawal — runs
 * into the fact that {@code extract} pulls from an exact slot, and a carry destroys that
 * correspondence. Doing the carry <em>before</em> the order makes the problem vanish:
 * there is no reservation yet for a carry to invalidate, and {@code distribute} and
 * {@code extract} are not touched at all.
 *
 * <p>It is also simply what a person does. Bring the materials to the site, then start
 * work.
 *
 * <p>The carry has its own material entry and its own reservations, entirely separate
 * from the order that follows. Both ledgers stay complete and neither knows about the
 * other. The cost, stated plainly: between the bot setting the crate down and the order
 * reserving against it, the material is unreserved — which is true of any chest that has
 * not been ordered against yet.
 *
 * <p>Reuses {@code MaterialCourier} unchanged. That class is package-private inside
 * {@link PlayerConstructionService}, which is why this lives in the same package rather
 * than beside the warehouse code that uses it.
 */
public final class WarehouseMaterialCarry {
    private final UUID carryProjectId;
    private final PlayerConstructionService.MaterialCourier courier;
    private final BlockPos destination;
    private State state = State.CARRYING;

    private WarehouseMaterialCarry(
            UUID carryProjectId,
            PlayerConstructionService.MaterialCourier courier,
            BlockPos destination) {
        this.carryProjectId = carryProjectId;
        this.courier = courier;
        this.destination = destination;
    }

    public enum State { CARRYING, DELIVERED, FAILED }

    /**
     * Either a started carry or the reason there is none, following the same shape as
     * every other result in this package so a caller cannot forget to check.
     */
    public record Result(boolean success, String code, WarehouseMaterialCarry carry) {
        static Result refused(String code) {
            return new Result(false, code, null);
        }
    }

    /**
     * Starts a carry, or returns why it could not start.
     *
     * @param requirements what the order that follows will need; the carry moves exactly
     *     this and nothing else
     * @param sources warehouse containers to draw from, each of which the actor must be
     *     standing near
     * @param destination the chest at the site that the order will later use as its source
     * @param start where the bot appears, which must be standable
     */
    public static Result begin(
            ServerPlayer actor,
            Map<ResourceId, Long> requirements,
            List<BlockPos> sources,
            BlockPos destination,
            BlockPos start,
            String runtime,
            String worldIdentity) {
        Objects.requireNonNull(actor, "actor");
        if (sources.isEmpty()) return Result.refused("CARRY_HAS_NO_SOURCE");
        ServerLevel level = actor.serverLevel();
        UUID carryProjectId = UUID.randomUUID();

        var opened = PlayerMaterialService.openStandalonePlan(actor, carryProjectId,
                requirements, sha256Of(requirements), runtime);
        if (!opened.success()) return Result.refused("CARRY_PLAN_REFUSED:" + opened.statusCode());
        for (BlockPos source : sources) {
            var selected = PlayerMaterialService.selectStandaloneSource(actor, carryProjectId,
                    cell(source), Direction.UP);
            if (!selected.success()) {
                return Result.refused("CARRY_SOURCE_REFUSED:" + selected.statusCode());
            }
        }
        var reserved = PlayerMaterialService.confirmStandalone(actor, carryProjectId, worldIdentity);
        if (!reserved.success()) {
            return Result.refused("CARRY_RESERVATION_REFUSED:" + reserved.statusCode());
        }
        var prepared = PlayerConstructionService.prepareWithdrawal(level, reserved.entry(),
                MaterialExecutorKind.BOT);
        DeploymentBoundingBox bounds = new DeploymentBoundingBox(
                cell(new BlockPos(
                        Math.min(destination.getX(), start.getX()) - 8,
                        Math.min(destination.getY(), start.getY()) - 4,
                        Math.min(destination.getZ(), start.getZ()) - 8)),
                cell(new BlockPos(
                        Math.max(destination.getX(), start.getX()) + 8,
                        Math.max(destination.getY(), start.getY()) + 4,
                        Math.max(destination.getZ(), start.getZ()) + 8)));
        try {
            var courier = PlayerConstructionService.MaterialCourier.spawnForOwner(level,
                    carryProjectId, prepared, cell(destination), cell(start), bounds, actor);
            return new Result(true, "CARRY_STARTED",
                    new WarehouseMaterialCarry(carryProjectId, courier, destination));
        } catch (RuntimeException failure) {
            return Result.refused("CARRY_COURIER_REFUSED:" + failure.getMessage());
        }
    }

    /** Advances the bot one tick. Terminal states stay terminal. */
    public State tick() {
        if (state != State.CARRYING) return state;
        PlayerConstructionService.CourierTick result;
        try {
            result = courier.tick();
        } catch (RuntimeException failure) {
            state = State.FAILED;
            return state;
        }
        state = switch (result) {
            case COMPLETED -> State.DELIVERED;
            case FAILED -> State.FAILED;
            case PROGRESS -> State.CARRYING;
        };
        return state;
    }

    public State state() {
        return state;
    }

    /**
     * Why the carry stopped, when it stopped badly.
     *
     * <p>A failed carry used to report only that it had failed, and its half-dozen causes
     * — no path, the source snapshot changed, the container vanished, the material gone —
     * need completely different responses. Working out which one cost a full physical run
     * every time.</p>
     */
    public String failureCode() {
        return courier.failureCode();
    }

    public UUID carryProjectId() {
        return carryProjectId;
    }

    /** Where the material is being taken, which is the order's source once it lands. */
    public BlockPos destination() {
        return destination;
    }

    /** Exact read-only courier progress for the warehouse diagnostic surface. */
    public PlayerConstructionService.LogisticsDiagnosticSnapshot diagnosticSnapshot() {
        return courier.diagnosticSnapshot();
    }

    private static BlockPos3i cell(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static String sha256Of(Map<ResourceId, Long> requirements) {
        StringBuilder canonical = new StringBuilder("carry");
        requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> canonical.append('|').append(entry.getKey())
                        .append('=').append(entry.getValue()));
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
