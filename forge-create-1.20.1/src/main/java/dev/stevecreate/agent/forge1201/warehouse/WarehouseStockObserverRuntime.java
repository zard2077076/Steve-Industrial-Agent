package dev.stevecreate.agent.forge1201.warehouse;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.warehouse.UnattendedProductionOrderScheduler;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import dev.stevecreate.agent.forge1201.command.WarehouseDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A warehouse runtime that reads real containers and authorises nothing it cannot see.
 *
 * <p>Everything needed for unattended production was already written and none of it could
 * run: {@code WarehouseOrderService} ticks every twenty ticks, reads persisted orders and
 * asks the scheduler what to do, but nothing anywhere called {@code registerRuntime}, so
 * the lookup returned null and every order was skipped forever. This is the missing seam.
 *
 * <p>{@link #observedStock} is the part worth getting right, and the part that can be
 * quietly wrong: it decides <em>when</em> the agent produces. It counts through
 * {@link WarehouseDiscovery}, so it sees exactly the containers a player would be shown,
 * and it counts nothing else.
 *
 * <p>{@link #dispatch} deliberately does not produce. It records the authorisation the
 * scheduler issued and reports acceptance, which is what makes the decision observable
 * without pretending a batch was made. Anything claiming this runtime produces goods
 * would be claiming something no code here does — the log line the gate emits says
 * {@code producedNothing=true} for that reason.
 */
public final class WarehouseStockObserverRuntime implements WarehouseOrderService.WarehouseRuntime {
    private final ServerLevel level;
    private final BlockPos centre;
    private final int radius;
    private final List<UnattendedProductionOrderScheduler.BatchAuthorization> authorizations =
            new ArrayList<>();

    public WarehouseStockObserverRuntime(ServerLevel level, BlockPos centre, int radius) {
        this.level = Objects.requireNonNull(level, "level");
        this.centre = Objects.requireNonNull(centre, "centre").immutable();
        this.radius = radius;
    }

    /**
     * How much of {@code target} the discovered containers hold together.
     *
     * <p>A resource with component data is not the same resource as one without, so a key
     * carrying a payload hash is answered with zero rather than with a count that ignores
     * the distinction — over-counting here makes the agent decide it has enough and stop
     * producing, which is the failure that is hardest to notice.</p>
     */
    @Override
    public long observedStock(WarehouseResourceKey target) {
        Objects.requireNonNull(target, "target");
        if (!WarehouseResourceKey.EMPTY_COMPONENT_SHA256.equals(target.componentSha256())) {
            return 0;
        }
        ResourceId resource = target.resourceId();
        return WarehouseDiscovery.candidates(level, centre, radius, List.of()).stream()
                .mapToLong(candidate -> candidate.contents().getOrDefault(resource, 0L))
                .sum();
    }

    /** Records what the scheduler authorised. It produces nothing and says so. */
    @Override
    public WarehouseOrderService.DispatchResult dispatch(
            UnattendedProductionOrderScheduler.BatchAuthorization authorization) {
        authorizations.add(Objects.requireNonNull(authorization, "authorization"));
        return new WarehouseOrderService.DispatchResult(true, "AUTHORIZATION_OBSERVED");
    }

    /** Every authorisation this runtime has been handed, oldest first. */
    public List<UnattendedProductionOrderScheduler.BatchAuthorization> authorizations() {
        return List.copyOf(authorizations);
    }

    public Optional<UnattendedProductionOrderScheduler.BatchAuthorization> latest() {
        return authorizations.isEmpty() ? Optional.empty()
                : Optional.of(authorizations.get(authorizations.size() - 1));
    }

    /** Where this runtime looks, for a gate that needs to place stock somewhere it sees. */
    public BlockPos3i centre() {
        return new BlockPos3i(centre.getX(), centre.getY(), centre.getZ());
    }
}
