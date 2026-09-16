# Bot clearing UX (Mac development WIP)

The player workflow now enters the existing Site Preparation executor after an
exact project approval. It does not add a UI-only executor.

## Player flow

1. Approve the one-page light-clearing summary.
2. Choose **Select Salvage**, look at a dedicated chest or barrel inside the
   bounded work envelope, and right-click it.
3. The server revalidates the project nonce, token expiry, world, dimension,
   region hash, plan/snapshot hashes, obstacle states and container location.
4. Choose **Start Bot Clearing** during the short pre-start undo window.
5. Existing Site Preparation Bots reserve work positions, path without
   teleporting, use typed tools, break only exact approved blocks, collect real
   drop entities, deliver them to the selected container and run the mandatory
   post-clearance rescan.

The container selection persists position, block-state fingerprint and an
opaque destination identity only. It stores no item, slot, inventory, account
or path data. Selecting it is explicit authorization for salvage delivery; no
other container is opened.

## Fail-closed behavior

- Protected, container/data, unknown and hazardous obstacles never enter the
  light-clearing token.
- Any project, placement, execution-mode, region, plan, snapshot, block-state,
  player, world, dimension, token-state or expiry drift refuses start.
- A changed/unavailable salvage container pauses the live session.
- Capacity is simulated for the complete pending delivery before insertion. A
  full container causes zero partial insertion, preserves the session and can
  resume after the player makes room.
- Cancellation is refused while an actual drop is pending or carried; it is
  allowed only when all collected salvage is already delivered.

## Current boundary

This checkpoint remains WIP rather than `PHASE_IV_BOT_CLEARING_UX_COMPLETE`.
The safe dedicated-container path is implemented, but automatic Agent-owned
temporary salvage-container placement/return is not. A packet-throttled HUD
and live-session pause/resume/safe-cancel controls now exist. A process restart
fails closed to reapproval instead of claiming recovery, but persistent
carried-salvage recovery is not implemented. Construction handoff and Mac
visible-client acceptance remain subsequent WIP. Windows/PCL2 final acceptance
remains separate and pending.
