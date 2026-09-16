# Smart relocation

The advisor evaluates exactly 216 deterministic candidates: nine nearby
cardinal positions, two elevations, four orientations and three layout
variants. Work is capped at six candidates per server tick.

Protected blocks, containers or block entities, unknown observations, hazards
and authority conflicts dominate the score. Only after those are zero do Bot
access, demolition count, material/layout cost and expansion preference rank a
site. Selecting a result performs a fresh snapshot comparison; a changed site
returns `CANDIDATE_WORLD_CHANGED` and grants no authority.

The advisor may recommend a position but never moves the player, loads chunks
or modifies the world.
