# Obstacle classification

The deterministic classifier has five outcomes:

- `SAFE_NATURAL_CLEARABLE`: exact allowlisted natural block and known-safe
  protection state.
- `CONFIRM_EACH_OR_GROUP`: ordinary block requiring precise approval.
- `PROTECTED_NO_AUTOMATIC_REMOVAL`: any container, inventory, BlockEntity,
  machine, running Create component, known/unknown claim risk, important block
  or out-of-region position.
- `ENVIRONMENTAL_HAZARD`: fluid, lava, TNT or other unsafe terrain condition.
- `UNKNOWN`: default for all unrecognized or placement-uncertain blocks.

Only the first two classes are approvable. `UNKNOWN` never degrades to safe.
