package dev.stevecreate.agent.core.execution.composite;

/**
 * Tells a route that is working slowly from one that cannot finish.
 *
 * <p>An inter-stage route waits for the destination to hold what the edge promised, and
 * until then it simply reports progress. That is right while material is still moving and
 * wrong once it cannot: a destination with no room for the resource will never accept the
 * rest, so the run reports progress until the whole session times out, and the operator
 * learns only that something took too long.
 *
 * <p>Two conditions have to hold before this says stop, and neither is sufficient alone.
 * A route that has not moved for a while may simply be slow — a hopper transfers on its
 * own schedule and a stage upstream may still be producing. A destination that is full
 * right now may be emptied by the machine consuming from it a tick later. Only a route
 * that has been still <em>and</em> has nowhere to put anything is stuck, and that is a
 * different fact from being slow.
 *
 * <p>This is the first piece of C5-B. A line that stays up has to be able to say why it
 * is not moving, and "it timed out" is not a reason.
 */
public final class RouteBackpressure {
    /**
     * How long a route may sit at the same count before stillness counts as evidence.
     *
     * <p>Ten seconds of game time. A vanilla hopper moves an item every eight ticks, so
     * a route that has genuinely delivered nothing in two hundred has stopped rather than
     * slowed.</p>
     */
    public static final int STALL_TICKS = 200;

    private RouteBackpressure() {}

    /** What a route's stillness means, once both facts are in. */
    public enum Verdict {
        /** Material is still arriving, or the destination can still take some. */
        MOVING,
        /** Nothing has arrived for a while and nothing more can. */
        BLOCKED
    }

    /**
     * Judges a route from what has arrived and whether more could.
     *
     * @param deliveredNow how much of the edge resource the destination holds
     * @param deliveredBefore what it held when the route was last seen to move
     * @param ticksSinceMoved how long it has held exactly that
     * @param destinationHasRoom whether the destination could accept any more of it
     */
    public static Verdict judge(
            long deliveredNow,
            long deliveredBefore,
            int ticksSinceMoved,
            boolean destinationHasRoom) {
        if (deliveredNow != deliveredBefore) return Verdict.MOVING;
        if (destinationHasRoom) return Verdict.MOVING;
        return ticksSinceMoved >= STALL_TICKS ? Verdict.BLOCKED : Verdict.MOVING;
    }
}
