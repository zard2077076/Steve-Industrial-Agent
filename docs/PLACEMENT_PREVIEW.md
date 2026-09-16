# Placement preview

Placement is a client renderer over a fresh server snapshot. It renders at
most 512 exact geometry/clearance cells and never places a block or entity.
Four quarter-turns and Compact, Standard and Expandable layouts use the same
reviewed v6.0.6 geometry catalog as later planning.

Colors and nested outlines distinguish place, clear, reuse, shared
infrastructure, protected, outside-authority, unknown and hazard cells. The
anchor and facing indicator remain visible. Right-click finalizes only after a
fresh server response; Left-click/Esc cancels the authoritative preview
project. A resumed survey uses the persisted anchor/orientation/layout and
requests another server preview instead of trusting cached render data.
