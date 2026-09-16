# Phase IV-C player workflow (Mac development)

This branch implements the player interaction layer over the existing Site
Preparation contracts. The current safe path is:

1. use the Steve Engineer Terminal in an explicitly marked disposable world;
2. select one runtime-present reviewed goal and a bounded quantity;
3. aim at the ground, rotate with the wheel, change layout with Shift+wheel,
   and right-click to request a server survey;
4. keep the site, run the bounded relocation advisor, or choose another site;
5. review the exact clearing summary and issue a two-minute approval;
6. explicitly select a dedicated salvage chest/barrel and start the real Bot
   clearing session;
7. watch the evidence-backed HUD, pause/resume from Manage Project, and wait
   for the mandatory post-clearance rescan;
8. right-click one or more explicitly authorized material chests/barrels;
9. review missing quantities and confirm exclusive server reservations;
10. start only after the server regenerates the same complete physical-plan
    BOM and refreshes the prepared-site authorization.

The item transaction path is now connected to the existing construction
executor, including process inputs, registered item-form installation
components and exact cancellation return. It no longer stops merely because a
material source has not been modeled.

The current ordinary Create templates still contain roles without safe
survival actions (creative motor, belt, fluid/fire media or a non-item route).
Those projects pause at `SURVIVAL_MATERIAL_BINDING_REQUIRED`. The UI must show
the named blocker and must not claim construction or completion. This replaces
the older `CONSTRUCTION_MATERIAL_SOURCE_REQUIRED` WIP boundary.

## Normal and engineering modes

Normal mode keeps coordinates, region identities, preview hashes, mutation
budgets, task graphs and raw evidence off the survey and approval screens. The
player sees goal, footprint, work counts, conflict classes, material summary,
Bot recommendation, risk and the safety policy. Smart Recommended is the
default execution mode.

The existing `/industrialagent` command family remains the advanced engineering
surface for exact coordinates, region/backup/readiness evidence, hashes,
budgets, executor status and cleanup. Goal Picker currently exposes the bounded
Direct/Bots/Hybrid selector, but a fully expandable advanced GUI and plan export
are not implemented in this WIP. Both surfaces retain the same server safety
gates; the GUI does not own a second execution path.

Project intent is durable SavedData, but execution authority is not. After a
server restart, a clearing project becomes `PAUSED / RECOVERY_REAPPROVAL_REQUIRED`;
a prepared construction project becomes
`PAUSED / CONSTRUCTION_RECOVERY_REPLAN_REQUIRED`. Material transactions are
reconciled and returned from SavedData, but no world mutation resumes from the
persisted UI state alone.

`MAC_DEVELOPMENT_ACCEPTANCE` is not `WINDOWS_PCL2_FINAL_ACCEPTANCE`. No release
or formal-save authorization is created by this workflow.
