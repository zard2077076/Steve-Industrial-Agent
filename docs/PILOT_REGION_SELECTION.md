# Pilot region selection

`region here X Z Y` uses the player's block position as center. X/Z dimensions
are exact; the Y range begins two blocks below the player and extends upward for
the remaining height. The maximum is 64 by 64 by 32 and the complete box must be
inside the current dimension's build height.

`pos1` and `pos2` record the player's current block position. When both exist,
their coordinate order is canonicalized into one inclusive box. No command
accepts a world path or cross-dimension corner.

Selection binds exact writable WorldIdentity, dimension, bounds, region hash,
player, server-session identity, 15-minute expiry and policy version. Preview is
required before confirmation. Clear removes only in-memory selection/preview
state and never changes the world.
