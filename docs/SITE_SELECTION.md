# Site selection

Phase IV-S keeps site selection as immutable loader-neutral evidence. A
`PlacementAnchor` is captured from the player's feet or authoritative look hit
and binds the exact writable-world identity, dimension, player, position,
timestamp and stable SHA-256. Moving later does not move the anchor.

Two-corner selection rejects mixed worlds/dimensions and regions above 131,072
cells. Confirmation binds anchor, region, facing, session and expiry. Footprint
projection rotates only validated relative positions and refuses any projected
cell outside the confirmed region.

Player commands:

- `/industrialagent site anchor here|look|status|clear`
- `/industrialagent site pos1|pos2`
- `/industrialagent site region preview|confirm|clear`
- `/industrialagent site facing north|south|east|west|rotate-clockwise|status`

Selection evidence is not `RegionAuthorization` and grants no write authority.
