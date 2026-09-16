package dev.stevecreate.agent.core.execution.composite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Separating a slow route from a stuck one.
 *
 * <p>The distinction is the whole content: today a route that cannot finish reports
 * progress until the session times out, so the two look identical from outside. Calling
 * a slow route blocked would be worse than the timeout it replaces — it would stop runs
 * that were about to succeed.
 */
class RouteBackpressureTest {

    @Test
    void aRouteThatIsStillDeliveringIsMoving() {
        assertThat(RouteBackpressure.judge(5, 3, 10_000, false))
                .isEqualTo(RouteBackpressure.Verdict.MOVING);
    }

    /** Room means the next hopper transfer can land, however long the last one took. */
    @Test
    void aStillRouteWithRoomLeftIsMoving() {
        assertThat(RouteBackpressure.judge(3, 3, 10_000, true))
                .isEqualTo(RouteBackpressure.Verdict.MOVING);
    }

    /**
     * A hopper moves an item every eight ticks, so a route that has been at the same
     * count for two hundred has not merely been unlucky.
     */
    @Test
    void aStillFullRouteIsBlockedOnceItHasBeenStillLongEnough() {
        assertThat(RouteBackpressure.judge(3, 3, RouteBackpressure.STALL_TICKS, false))
                .isEqualTo(RouteBackpressure.Verdict.BLOCKED);
    }

    /**
     * The case that makes patience necessary: a machine consuming from the destination
     * frees a slot a moment later, and a route condemned at the first full reading would
     * have killed a run that was about to continue.
     */
    @Test
    void aBrieflyFullRouteIsNotYetBlocked() {
        assertThat(RouteBackpressure.judge(3, 3, RouteBackpressure.STALL_TICKS - 1, false))
                .isEqualTo(RouteBackpressure.Verdict.MOVING);
        assertThat(RouteBackpressure.judge(3, 3, 0, false))
                .isEqualTo(RouteBackpressure.Verdict.MOVING);
    }

    /** Delivery going backwards is still movement, and not this check's business. */
    @Test
    void aDestinationThatLostMaterialIsNotBlocked() {
        assertThat(RouteBackpressure.judge(2, 3, 10_000, false))
                .isEqualTo(RouteBackpressure.Verdict.MOVING);
    }
}
