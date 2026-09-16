# Bot terrain preparation

`TerrainPreparationTaskGraph` is a bounded, acyclic site-preparation graph. It
does not replace `ConstructionTaskGraph` or alter `ConstructionExecutor`.
Its exact task vocabulary is reserve positions, remove safe foliage, mine an
authorized block, collect drops, deliver salvage, fill a minor hole, level the
surface, verify ground and release positions.

`BotClearingExecutor` is a dedicated bounded interface. Runtime implementations
must use adjacent visible movement, never teleport, mutate only token-bound
positions, avoid entities/containers, collect actual drops and deliver them to
an explicitly authorized salvage destination. Cancellation is terminal and
reload requires exact identity/state reconciliation.

The Forge runtime reuses the existing registered `ConstructionBotEntity`,
including its player-shaped renderer, persistent identity, bounded smooth
movement and logistics/builder-inspector roles. Site preparation does not
register an ArmorStand worker or a second entity system. Its terrain graph
remains distinct because it precedes `VerifiedPhysicalPlan`.

`TerrainPreparationFleetTaskAdapter` and
`TerrainPreparationFleetDispatcher` connect that graph to the same
`GraphNeutralFleetCoordinator` used by construction. Terrain tasks retain the
four dedicated capabilities for approved removal, salvage collection,
salvage delivery and prepared-ground verification. Collect and delivery name
their exact worker-continuity predecessor; neither adapter creates or casts a
`ConstructionTask` or claims `VerifiedPhysicalPlan` provenance.

Reload snapshots bind the complete canonical terrain graph fingerprint and
exact assignment/worker/lease identities. Every interrupted assigned task,
including one interrupted before its first dispatcher update, becomes typed
`fleet:reload_interrupted`. Reassignment remains unavailable until the terrain
adapter permits it and the authoritative server rescan records exact bound
`ReconciliationEvidence`.
