# Steve Engineer Terminal

The craftable `steve_create_agent:engineer_terminal` opens a bilingual client
screen. `/give @s steve_create_agent:engineer_terminal` is the development
shortcut. The client sends only bounded goal, quantity, production-mode and
execution-mode values; the server rechecks the world marker, recipe presence,
catalog entry, project budget, nonce and request rate.

The terminal exposes New Production Line, Continue Unfinished Project, Manage
Active Project and a disabled Remove Agent-owned Project placeholder. Continue
can reconstruct placement, survey, confirmation and clearing-management entry
points from server-supplied project fields. Continuous inventory maintenance
and owned-structure removal remain visibly unavailable.

The normal survey and confirmation pages do not show exact anchor coordinates,
region IDs, preview hashes, mutation budgets or executor internals. Those remain
available through the existing advanced command surface. Goal Picker defaults
to Smart Recommended and offers the bounded execution-mode selector; the wider
expandable engineering GUI and plan export remain pending.

For the reviewed IE Metal Press flow:

1. sneak-use a chest to select the exact material source;
2. sneak-use the target ground to create the order;
3. sneak-use in air to open the order screen;
4. read live material, FE, output, pause and completion evidence, or use the
   two-click safe-cancel button before input admission.

The item tooltip repeats these gestures. The order screen auto-refreshes every
two seconds, remains usable at common 240-pixel scaled GUI height and never
turns client state into execution evidence. The material-source and completion
pages use high-contrast cards with separate available/required, reserved,
consumed/returned, duplicate and private-item rows so missing stacks are
readable before confirmation. `/steveagent order status` is a permissionless
self-service diagnostic for the latest common industrial order envelope.

The frozen packet protocol is `phase-iv-player-workflow-v3`. The IE screen is
additive on `metal-press-order-v1`, so existing player/material message IDs and
SavedData schemas do not drift. Ordinary C-03-C-10 projects also persist the
additive `industrial-player-order-v1` envelope; its reload pause and report
state are server evidence, not a second client executor.
