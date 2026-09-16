# World safety profile

The player workflow is enabled only in an explicitly marked disposable test
world. The world-local marker is necessary but does not itself grant a region,
demolition, container or construction write capability.

Each mutating clearing action is bounded by the selected dimension, player,
project, exact plan/snapshot, authorized bounds, obstacle list, expiry and
policy. Protected blocks, inventory containers, unknown block entities,
hazards, unloaded chunks and changed states fail closed. World access remains
server-thread authoritative; the client cannot supply free-form commands or
unchecked write coordinates.

Formal/important saves, the Windows PCL2 instance and DeceasedCraft player
world are outside this Mac development authority. Backups and construction
write authority retain their existing independent gates.
