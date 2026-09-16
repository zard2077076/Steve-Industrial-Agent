package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.execution.construction.MaterialIdentity;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Slot;
import dev.stevecreate.agent.forge1201.player.PlayerMaterialSavedData.Source;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tidying your own chest is not a reason to lose an order.
 *
 * <p>The comparison used to be an inventory hash and then a slot-by-slot list, and both
 * are fingerprints of an arrangement rather than of contents. A player who reserved
 * material, went away, organised their storage and came back to approve was told
 * MATERIAL_SOURCE_CHANGED — for an operation that is entirely their business and that
 * took nothing away from the order.
 */
class PlayerMaterialSnapshotTest {
    private static final MaterialIdentity IRON = identity("minecraft:iron_ingot");
    private static final MaterialIdentity GOLD = identity("minecraft:gold_ingot");

    /** The whole point: same items, different slots, still the same source. */
    @Test
    void acceptsAContainerWhoseContentsWereRearranged() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8), new Slot(4, GOLD, 3)));
        Source tidied = source(List.of(new Slot(1, GOLD, 3), new Slot(9, IRON, 8)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, tidied)).isTrue();
    }

    /** Merging two partial stacks of the same thing is tidying too. */
    @Test
    void acceptsPartialStacksMergedIntoOne() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 5), new Slot(7, IRON, 3)));
        Source merged = source(List.of(new Slot(2, IRON, 8)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, merged)).isTrue();
    }

    /** Topping the chest up is not a change the order needs to care about. */
    @Test
    void acceptsMoreOfTheSameMaterial() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8)));
        Source fuller = source(List.of(new Slot(0, IRON, 8), new Slot(1, IRON, 64)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, fuller)).isTrue();
    }

    /**
     * The guarantee that actually mattered, unchanged.
     *
     * <p>Without this the relaxation would be worthless: an order whose material has
     * been spent must still refuse, and it does, because the sum drops.</p>
     */
    @Test
    void stillRefusesAContainerSomethingWasTakenFrom() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8), new Slot(4, GOLD, 3)));
        Source raided = source(List.of(new Slot(0, IRON, 7), new Slot(4, GOLD, 3)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, raided)).isFalse();
    }

    /** An identity carries its payload, so a re-enchanted stack is a different stack. */
    @Test
    void stillRefusesTheSameItemWithDifferentComponents() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8)));
        Source swapped = source(List.of(
                new Slot(0, new MaterialIdentity(ResourceId.parse("minecraft:iron_ingot"),
                        "f".repeat(64)), 8)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, swapped)).isFalse();
    }

    /** Emptying it entirely is the same refusal, not a special case. */
    @Test
    void stillRefusesAnEmptiedContainer() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, source(List.of()))).isFalse();
    }

    /** A different chest in the same place is a different chest. */
    @Test
    void stillRefusesADifferentContainerAtTheSamePosition() throws Exception {
        Source bound = source(List.of(new Slot(0, IRON, 8)));
        Source replaced = new Source(UUID.randomUUID(), new BlockPos3i(1, 2, 3), "north",
                "minecraft:barrel", "a".repeat(64), "b".repeat(64), 1, 64, 1L, 2L, false,
                List.of(new Slot(0, IRON, 8)));

        assertThat(PlayerMaterialService.sameSnapshot(bound, replaced)).isFalse();
    }

    private static Source source(List<Slot> slots) throws Exception {
        // The inventory hash is derived from the arrangement so that it genuinely differs
        // between a bound source and a rearranged one. A comparison that still read it
        // would pass none of the accepting tests, which is the point.
        String inventory = String.format("%064x",
                new java.math.BigInteger(1, java.security.MessageDigest.getInstance("SHA-256")
                        .digest(slots.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return new Source(UUID.randomUUID(), new BlockPos3i(1, 2, 3), "north",
                "minecraft:chest", "a".repeat(64), inventory, 1, 64, 1L, 2L, false, slots);
    }

    private static MaterialIdentity identity(String item) {
        return new MaterialIdentity(ResourceId.parse(item),
                MaterialIdentity.EMPTY_PAYLOAD_SHA256);
    }
}
