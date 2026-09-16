# Phase IV exact recovery

## Accepted boundary

C-05 through C-10 recover only before resource injection. A recovery candidate
must contain:

- the exact trusted generic plan snapshot;
- a running BUILD step with a journal prefix, or BUILD-complete POWER/READY;
- the exact session-bound, block-only world-change journal;
- journal positions in the same order as the trusted physical placement prefix;
- an authoritative rescan of exactly every modified position;
- no injected-resource or irreversible-processing history.

`Create606PreResourceRecovery` validates this common boundary. The versioned
handler reconstructs its placement cursor from the validated prefix, so already
completed placements are never replayed. Any plan, graph, session, journal,
position, block state or provenance drift fails closed.

## Cancellation and cleanup

Three-mode cancellation sends `CANCEL` to the exact active assignment, cancels
the bounded process session, returns only exact provably conserved carried or
delivered inputs, empties delivery storage, removes physical workers and makes
the session terminal. Later ticks are refused.

Failure cleanup replays only session-owned reversible block journals in reverse.
It restores a position only when its complete current state still matches the
journaled after-state. External drift is preserved and reported. Cleanup never
creates replacement processed resources and cannot claim recovery after an
uncertain resource history.

## Automated evidence

- `phase-iv-recovery-gametest-20260724-132623.log`: five checkpoint-codec
  round-trips with exact rescan and zero repeated placement/input/output, plus
  five Direct/Bots/Hybrid cancellation matrices (10/10).
- `phase-iv-fault-gametest-20260724-132902.log`: five ownership-aware cleanup
  matrices covering material, formal-world, obstruction, navigation,
  orientation/transmission and power failures (5/5).

These are automated WIP gates. Cross-Composite/fleet recovery and the single
integrated player-visible save/exit/re-entry acceptance remain pending.
