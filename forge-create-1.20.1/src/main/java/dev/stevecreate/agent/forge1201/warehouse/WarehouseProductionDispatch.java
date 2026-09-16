package dev.stevecreate.agent.forge1201.warehouse;

import com.mojang.authlib.GameProfile;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.UnattendedProductionOrderScheduler;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.command.PlayerCompositeOrderService;
import dev.stevecreate.agent.forge1201.command.WarehouseDiscovery;
import dev.stevecreate.agent.forge1201.command.WarehouseMaterialCarry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Turns a scheduler authorisation into a real production order.
 *
 * <p>This is the step that separates an agent which plans from one which operates.
 * Everything upstream existed and was verified — the scheduler decides, the expander
 * derives a graph, the order service reserves and builds — and the authorisation stopped
 * at a runtime that only recorded it. Nothing pressed the button.
 *
 * <p>The warehouse supplies the material and the authorisation supplies the site and the
 * graph, which is the natural division: a warehouse is where the stock is, and an order
 * needs somewhere to build. Sources come from {@link WarehouseDiscovery}, so the order
 * draws on exactly the containers the warehouse is defined by, and on nothing else.
 *
 * <p>It goes through {@link PlayerCompositeOrderService}, the same path a player's own
 * order takes, rather than through any acceptance fixture. The fixtures know how to set
 * up a site in one call and using one here would put test scaffolding in the production
 * path — which the runtime guard would refuse anyway, and rightly.
 *
 * <p>The actor is a {@link FakePlayer}. Unattended means there is no player to attribute
 * the order to, and the order envelope carries its owner independently; this is the same
 * mechanism the courier and builder bots already run on.
 */
public final class WarehouseProductionDispatch
        implements WarehouseOrderService.WarehouseRuntime {
    private final ServerLevel level;
    private final BlockPos warehouseCentre;
    private final int radius;
    private final List<BlockPos> siteOrigins;
    private final ResourceId orderType;
    private final ExecutionMode mode;
    /** A container only opens for someone beside it; vanilla's rule is eight blocks. */
    private static final double CONTAINER_REACH_SQUARED = 64.0;
    /** The order path's own limit on how far a site may be from whoever orders it. */
    private static final double SITE_REACH_SQUARED = 48.0 * 48.0;

    private final List<Attempt> attempts = new ArrayList<>();
    private final BlockPos siteChest;
    private Pending pending;
    private long deliveredOnCarry = -1;
    private BlockPos lastOutputDestination;

    /** A carry in flight, and the authorisation waiting on it. */
    private record Pending(
            WarehouseMaterialCarry carry,
            UnattendedProductionOrderScheduler.BatchAuthorization authorization,
            java.util.Map<ResourceId, Long> requirements) {}

    /**
     * @param warehouseCentre where the stock is looked for
     * @param siteOrigin where an authorised batch is built; supplied at registration
     *     because a site id is a name and an order needs a position, and inventing a
     *     registry to map one to the other would be inventing a fact
     */
    public WarehouseProductionDispatch(
            ServerLevel level,
            BlockPos warehouseCentre,
            int radius,
            BlockPos siteOrigin,
            ResourceId orderType,
            ExecutionMode mode) {
        this(level, warehouseCentre, radius, siteOrigin, orderType, mode, null);
    }

    /**
     * @param siteChest where a carrying bot puts the material down, and what the order
     *     then draws from. Null keeps the direct behaviour: reserve straight out of the
     *     warehouse with no carry, which is what every existing caller wants.
     */
    public WarehouseProductionDispatch(
            ServerLevel level,
            BlockPos warehouseCentre,
            int radius,
            BlockPos siteOrigin,
            ResourceId orderType,
            ExecutionMode mode,
            BlockPos siteChest) {
        this.siteChest = siteChest == null ? null : siteChest.immutable();
        this.level = Objects.requireNonNull(level, "level");
        this.warehouseCentre = Objects.requireNonNull(warehouseCentre, "warehouseCentre").immutable();
        this.radius = radius;
        this.siteOrigins = List.of(Objects.requireNonNull(siteOrigin, "siteOrigin").immutable());
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * A warehouse with more than one place it can build.
     *
     * <p>A site can be refused for reasons that have nothing to do with the warehouse —
     * something was built on it, the ground changed, an earlier order is still standing
     * there. With one site that stops production; with alternatives it is a detour. Sites
     * are tried in the order given, so the first is the preferred one rather than an
     * arbitrary pick.</p>
     */
    public WarehouseProductionDispatch(
            ServerLevel level,
            BlockPos warehouseCentre,
            int radius,
            List<BlockPos> siteOrigins,
            ResourceId orderType,
            ExecutionMode mode,
            BlockPos siteChest) {
        this.level = Objects.requireNonNull(level, "level");
        this.warehouseCentre = Objects.requireNonNull(warehouseCentre, "warehouseCentre").immutable();
        this.radius = radius;
        this.siteOrigins = siteOrigins.stream().map(BlockPos::immutable).toList();
        if (this.siteOrigins.isEmpty()) throw new IllegalArgumentException("no site given");
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.siteChest = siteChest == null ? null : siteChest.immutable();
    }

    /** What the discovered containers hold, which is what the scheduler decides against. */
    @Override
    public long observedStock(WarehouseResourceKey target) {
        Objects.requireNonNull(target, "target");
        if (!WarehouseResourceKey.EMPTY_COMPONENT_SHA256.equals(target.componentSha256())) {
            return 0;
        }
        return WarehouseDiscovery.candidates(level, warehouseCentre, radius, List.of()).stream()
                .mapToLong(candidate -> candidate.contents().getOrDefault(target.resourceId(), 0L))
                .sum();
    }

    /**
     * Places a real order for the authorised batch.
     *
     * <p>A refusal is returned as its own status code rather than swallowed. The
     * scheduler counts consecutive failures and stops retrying, and it can only do that
     * if it is told the truth about why — a dispatch that reported success on a refused
     * order would loop forever against a site that cannot be built on.</p>
     */
    @Override
    public WarehouseOrderService.DispatchResult dispatch(
            UnattendedProductionOrderScheduler.BatchAuthorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        List<BlockPos> sources = WarehouseDiscovery
                .candidates(level, warehouseCentre, radius, List.of()).stream()
                .map(candidate -> new BlockPos(candidate.position().x(),
                        candidate.position().y(), candidate.position().z()))
                .toList();
        if (sources.isEmpty()) {
            attempts.add(new Attempt(authorization.batchId(), false, "NO_WAREHOUSE_SOURCE", null));
            return new WarehouseOrderService.DispatchResult(false, "NO_WAREHOUSE_SOURCE");
        }
        FakePlayer actor = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("steve-unattended-" + authorization.warehouseId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "SteveUnattendedDispatch"));
        // Two range rules apply at once and unattended satisfies neither by default: the
        // site must be within reach of whoever orders, and a container will only open for
        // someone standing next to it. Moving the actor is the honest reading of both —
        // an agent that builds somewhere is an agent that is somewhere — and relaxing
        // either check would remove a guard that stops orders being placed on ground
        // nobody has looked at, or material being taken from a chest nobody is at.
        double x = sources.stream().mapToDouble(BlockPos::getX).average().orElseThrow() + 0.5;
        double y = sources.stream().mapToDouble(BlockPos::getY).average().orElseThrow();
        double z = sources.stream().mapToDouble(BlockPos::getZ).average().orElseThrow() + 0.5;
        actor.moveTo(x, y, z);
        // With a site chest configured, the material travels first and the order is
        // placed against where it lands. The carry happens before any reservation the
        // order makes, so nothing the order relies on has to survive it.
        if (siteChest != null) {
            if (pending != null) {
                return new WarehouseOrderService.DispatchResult(false, "CARRY_ALREADY_IN_FLIGHT");
            }
            var requirements = PlayerCompositeOrderService.preview(level, orderType, siteOrigins.get(0));
            if (!requirements.success()) {
                attempts.add(new Attempt(authorization.batchId(), false,
                        "CARRY_PREVIEW_REFUSED:" + requirements.code(), null));
                return new WarehouseOrderService.DispatchResult(
                        false, "CARRY_PREVIEW_REFUSED");
            }
            var started = WarehouseMaterialCarry.begin(actor, requirements.requirements(),
                    sources, siteChest, standableBeside(sources.get(0)),
                    runtimeIdentity(), worldIdentity());
            if (!started.success()) {
                attempts.add(new Attempt(authorization.batchId(), false, started.code(), null));
                return new WarehouseOrderService.DispatchResult(false, started.code());
            }
            pending = new Pending(started.carry(), authorization, requirements.requirements());
            lastOutputDestination = siteChest;
            attempts.add(new Attempt(authorization.batchId(), true, "CARRY_STARTED", null));
            return new WarehouseOrderService.DispatchResult(true, "CARRY_STARTED");
        }
        // Checked here rather than left to fail deep inside the order path, because
        // MATERIAL_SOURCE_UNAVAILABLE says nothing about which source or why.
        for (BlockPos source : sources) {
            if (actor.distanceToSqr(source.getX() + 0.5, source.getY(), source.getZ() + 0.5)
                    > CONTAINER_REACH_SQUARED) {
                attempts.add(new Attempt(authorization.batchId(), false,
                        "WAREHOUSE_SOURCE_OUT_OF_REACH:" + source.toShortString(), null));
                return new WarehouseOrderService.DispatchResult(
                        false, "WAREHOUSE_SOURCE_OUT_OF_REACH");
            }
        }
        List<BlockPos> reachable = siteOrigins.stream()
                .filter(site -> actor.distanceToSqr(site.getX() + 0.5, site.getY(),
                        site.getZ() + 0.5) <= SITE_REACH_SQUARED)
                .toList();
        if (reachable.isEmpty()) {
            attempts.add(new Attempt(authorization.batchId(), false,
                    "SITE_TOO_FAR_FROM_WAREHOUSE", null));
            return new WarehouseOrderService.DispatchResult(false, "SITE_TOO_FAR_FROM_WAREHOUSE");
        }
        // Each site in turn. A refusal on one is a reason to try the next, not to give
        // up: the site may be occupied by something a player built while the warehouse
        // was deciding. The last refusal is what gets reported if none work, because a
        // generic "no site" would hide which obstacle to go and clear.
        String lastCode = "NO_SITE_ATTEMPTED";
        for (BlockPos site : reachable) {
            PlayerCompositeOrderService.StartResult started;
            try {
                started = PlayerCompositeOrderService.create(actor, sources, site, orderType, mode);
            } catch (RuntimeException failure) {
                lastCode = "DISPATCH_EXCEPTION:" + failure.getClass().getSimpleName();
                continue;
            }
            if (started.success()) {
                lastOutputDestination = sources.get(0).immutable();
                attempts.add(new Attempt(authorization.batchId(), true, started.code(),
                        started.projectId()));
                return new WarehouseOrderService.DispatchResult(true, started.code());
            }
            lastCode = started.code();
        }
        attempts.add(new Attempt(authorization.batchId(), false, lastCode, null));
        return new WarehouseOrderService.DispatchResult(false, lastCode);
    }

    /**
     * Advances a carry, and places the order once the material has landed.
     *
     * <p>The order is placed against the site chest rather than the warehouse, because
     * that is where the material now is. It goes through the same service a player's
     * order uses, with the same reservation and the same slot-exact withdrawal — the
     * carry finished before any of that started.</p>
     */
    @Override
    public void tick(net.minecraft.server.MinecraftServer server) {
        Pending current = pending;
        if (current == null) return;
        WarehouseMaterialCarry.State state = current.carry().tick();
        if (state == WarehouseMaterialCarry.State.CARRYING) return;
        pending = null;
        // Counted here and nowhere else. The order placed a moment later reserves and
        // withdraws from this very chest, so anyone measuring afterwards finds it empty
        // and concludes the bot delivered nothing.
        deliveredOnCarry = countItems(siteChest);
        if (state == WarehouseMaterialCarry.State.FAILED) {
            attempts.add(new Attempt(current.authorization().batchId(), false,
                    "CARRY_FAILED", null));
            return;
        }
        FakePlayer actor = actor(current.authorization());
        actor.moveTo(siteChest.getX() + 0.5, siteChest.getY(), siteChest.getZ() + 0.5);
        PlayerCompositeOrderService.StartResult started;
        try {
            started = PlayerCompositeOrderService.create(
                    actor, List.of(siteChest), siteOrigins.get(0), orderType, mode);
        } catch (RuntimeException failure) {
            attempts.add(new Attempt(current.authorization().batchId(), false,
                    "ORDER_AFTER_CARRY_EXCEPTION:" + failure.getClass().getSimpleName(), null));
            return;
        }
        attempts.add(new Attempt(current.authorization().batchId(), started.success(),
                started.code(), started.projectId()));
    }

    /** How much the bot put down, measured the instant it finished and before the order. */
    public long deliveredOnCarry() {
        return deliveredOnCarry;
    }

    private long countItems(BlockPos position) {
        if (!(level.getBlockEntity(position)
                instanceof net.minecraft.world.Container container)) {
            return 0;
        }
        long total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    /** Whether a carry is still walking. */
    public boolean carrying() {
        return pending != null;
    }

    @Override
    public Optional<WarehouseOrderService.RuntimeDiagnostic> diagnostic() {
        Pending current = pending;
        return Optional.of(new WarehouseOrderService.RuntimeDiagnostic(
                Optional.ofNullable(lastOutputDestination).map(value -> new BlockPos3i(
                        value.getX(), value.getY(), value.getZ())),
                current == null ? Optional.empty()
                        : Optional.of(current.carry().diagnosticSnapshot())));
    }

    /**
     * A cell next to a container that a bot can occupy.
     *
     * <p>Not the block above it: a chest is not a full block, so standing on one is
     * refused. The four neighbours at the same level are floor if the site was prepared
     * at all, which is the same precondition the builder fleet already has.</p>
     */
    private BlockPos standableBeside(BlockPos container) {
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos candidate = container.relative(face);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()
                    && !level.getBlockState(candidate.below()).isAir()) {
                return candidate;
            }
        }
        return container.above();
    }

    private String runtimeIdentity() {
        return "steve_industrial:warehouse_carry/v1";
    }

    private String worldIdentity() {
        return dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe
                .worldIdentity(level)
                .orElseThrow(() -> new IllegalStateException("world is not authorised"));
    }

    private FakePlayer actor(UnattendedProductionOrderScheduler.BatchAuthorization authorization) {
        return FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("steve-unattended-" + authorization.warehouseId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "SteveUnattendedDispatch"));
    }

    /** Every dispatch this runtime has attempted, oldest first. */
    public List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public Optional<Attempt> latest() {
        return attempts.isEmpty() ? Optional.empty()
                : Optional.of(attempts.get(attempts.size() - 1));
    }

    /** The preferred site, which is the first one given. */
    public BlockPos3i site() {
        BlockPos first = siteOrigins.get(0);
        return new BlockPos3i(first.getX(), first.getY(), first.getZ());
    }

    /** One dispatch and what the order path said about it. */
    public record Attempt(ResourceId batchId, boolean accepted, String code, UUID projectId) {}
}
