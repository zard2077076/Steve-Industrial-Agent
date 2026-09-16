package dev.stevecreate.agent.core.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A route builds every cell but its two ends.
 *
 * <p>The ends are the ports of the machines being joined; they exist already and belong
 * to those machines. This lived as a loop inside the executor and nowhere else, so the
 * layer that prices a build had no way to know how many cells it would lay or what they
 * cost — and the player path refused any plan containing a route rather than guess.
 */
class PhysicalRouteInteriorTest {
    /** Two machines a few cells apart: the span between the ports is what gets built. */
    @Test
    void countsEveryCellExceptTheTwoPorts() {
        assertThat(route(cell(0), cell(1), cell(2), cell(3)).interiorPositions())
                .containsExactly(cell(1), cell(2));
    }

    /** Adjacent ports have nothing between them, and so nothing to pay for. */
    @Test
    void buildsNothingWhenThePortsAreAdjacent() {
        assertThat(route(cell(0), cell(1)).interiorPositions()).isEmpty();
    }

    /**
     * A route with fewer than two cells cannot exist, so this never has to handle one.
     *
     * <p>Asserted rather than assumed: the guard belongs to the record, and a later
     * relaxation there would otherwise silently reach code that has never seen it.</p>
     */
    @Test
    void cannotBeConstructedWithoutTwoEndpoints() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> route(cell(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoints");
    }

    private static PhysicalRoute route(BlockPos3i... positions) {
        return new PhysicalRoute(ResourceId.parse("layout:route_1"),
                ResourceId.parse("layout:port_a"), ResourceId.parse("layout:port_b"),
                GenericResourceType.ITEM, 1, 64, List.of(positions));
    }

    private static BlockPos3i cell(int x) {
        return new BlockPos3i(x, 64, 0);
    }
}
