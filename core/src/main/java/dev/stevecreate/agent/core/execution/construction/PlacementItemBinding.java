package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The item a player supplies for a block that has no item of its own.
 *
 * <p>Most of a machine is bought and placed: a saw is a saw. A few cells are not. Water
 * behind a fan is washing, fire is smoking, soul fire is haunting, lava is blasting —
 * every one of them a block the world knows and the item registry does not. The material
 * plan asked for {@code minecraft:water} by name, no such item exists, and a reservation
 * can only hold exact stacks, so the whole order was refused before it began. Measured
 * on the live catalog, that was sixty-five of the eighty-seven single-machine goals: 26
 * on water, 16 on soul fire, 11 on lava, 10 on fire and 2 on Create's belt.
 *
 * <p>The refusal was right. What was missing is this: the thing a player actually hands
 * over is a full bucket, or a flint and steel, or a belt connector, and the world ends up
 * with water, fire or a belt. Naming that item is all it takes for the reservation to
 * work, because the physical builder never consulted the item registry — it places these
 * cells from the geometry catalog and always could.
 *
 * <p><strong>These are charged as consumed, including the tools.</strong> Installation
 * material is escrowed and settled in one go, so there is no per-placement return path a
 * flint and steel could come back through, and inventing one to save a durability point
 * would be a lot of machinery for a small refund. A bucket comes back empty in a real
 * player's hands and this does not credit that either. Both errors run in the safe
 * direction — the site is charged slightly more than it strictly costs, never less — and
 * the player is quoted this bill before agreeing to it.
 */
public final class PlacementItemBinding {
    private static final Map<ResourceId, ResourceId> BINDINGS = Map.of(
            ResourceId.parse("minecraft:water"), ResourceId.parse("minecraft:water_bucket"),
            ResourceId.parse("minecraft:lava"), ResourceId.parse("minecraft:lava_bucket"),
            ResourceId.parse("minecraft:fire"), ResourceId.parse("minecraft:flint_and_steel"),
            ResourceId.parse("minecraft:soul_fire"),
            ResourceId.parse("minecraft:flint_and_steel"),
            // Not a medium at all, just a name: the block is create:belt and the item that
            // makes one is create:belt_connector.
            ResourceId.parse("create:belt"), ResourceId.parse("create:belt_connector"));

    private PlacementItemBinding() {}

    /**
     * What a builder must carry in order to leave {@code blockId} behind, when that is not
     * simply the block itself.
     *
     * @return empty for the ordinary case, where the block has an item of the same name
     */
    public static Optional<ResourceId> itemFor(ResourceId blockId) {
        return Optional.ofNullable(BINDINGS.get(Objects.requireNonNull(blockId, "blockId")));
    }

    /** A bucket holds this much, so a recipe wanting less than one still costs one. */
    public static final long BUCKET_MILLIBUCKETS = 1_000L;

    /**
     * How many buckets of a fluid a plan must buy to pour the given amount.
     *
     * <p>The same binding that turns a water block into a water bucket answers this: a
     * recipe asking for 250mB still costs a whole bucket, because a bucket is what a
     * player hands over and there is no way to hand over a quarter of one. Rounding up
     * overcharges by design — the alternative is a plan that reserves nothing and a basin
     * that never fills.</p>
     */
    public static Optional<Bucket> bucketsFor(ResourceId fluidId, long millibuckets) {
        Objects.requireNonNull(fluidId, "fluidId");
        if (millibuckets < 1) return Optional.empty();
        return itemFor(fluidId).map(item -> new Bucket(item,
                (millibuckets + BUCKET_MILLIBUCKETS - 1) / BUCKET_MILLIBUCKETS));
    }

    /** A bucket item and how many of them one batch needs. */
    public record Bucket(ResourceId item, long count) {
        public Bucket {
            Objects.requireNonNull(item, "item");
            if (count < 1) throw new IllegalArgumentException("a pour needs at least one bucket");
        }
    }

    /** The block ids this policy knows how to buy, for tests and for reporting. */
    public static java.util.Set<ResourceId> boundBlocks() {
        return BINDINGS.keySet();
    }
}
