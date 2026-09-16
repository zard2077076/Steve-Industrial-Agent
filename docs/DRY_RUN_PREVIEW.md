# Dry-run deployment preview

`DeploymentPreviewService` is a pure loader-neutral projection over an already verified chain:

```text
ProductionGoal
  -> VerifiedLogicalPlan
  -> VerifiedImplementationBoundPlan
  -> VerifiedPhysicalPlan
  -> UnifiedMachineGraph
  -> DeploymentPreview
```

It does not construct an `ExecutionReadyPlan`, execution session, runner, action handler, resource buffer or world handle. The only additional input is `DeploymentPreviewContext`: an immutable environment/policy value, independent world-snapshot fingerprint, bounded read-only block observations, explicit route/power construction material declarations, rollback classification and power-source assumptions. An Adapter must obtain those observations without loading unauthorized chunks; core never reads a save.

## Artifact contract

The preview records target and quantity; recipe and implementation identities; anchor, orientations and affected bounds; placements, removals and replacements; protected blocks and block entities; item and rotational-power routes; total and machine-only construction BOM; required inputs and expected output; ticks, stress and power assumptions; journal estimate and rollback classification; environment, runtime and snapshot fingerprints; risk findings, required approvals and policy violations; and a lowercase SHA-256 preview hash.

Canonical JSON has a fixed field order. Lists and maps are canonically ordered before hashing. The hash covers every semantic field except itself. `hasValidHash()` recomputes it. Changing block observations, materials, runtime/snapshot identity, policy results or any physical-plan-derived content changes the hash. The preview is audit data only; a valid hash does not make it current, approved, backed up, authorized or executable.

## Safety boundary

- Dry-run does not create a session or reserve/withdraw resources.
- Preview generation has no Minecraft, Forge, Create, Mekanism, filesystem or network type.
- Formal/unknown/forbidden classifications retain zero write authority; a formal preview contains explicit preview-only/policy violations.
- Protected blocks, BlockEntities and non-empty containers remain visible risk evidence rather than being treated as replaceable.
- Missing route/power construction material declarations are explicit policy violations; core never invents an implementation material.
- PW-05 supplies the separate complete typed 22-category INFO-through-CRITICAL risk assessment; its CRITICAL findings always block approval. The preview's original strings remain hash-bound audit hints only. PW-06 now supplies the separate read-only resource/power budget and typed policy violations; it neither reserves nor withdraws resources. Later authorization, approval, backup, permission and readiness gates remain mandatory.

PW-11 revalidates the preview hash and requires explicit dry-run completion plus exact current snapshot/runtime/resource generations before aggregation. Even an all-check success produces only a data-only `DeploymentReadyPlan`; it never creates a session, consumes an item, starts a machine or mutates the world.

PW-04 acceptance used repository-owned Create-only and disposable DeceasedCraft worlds only. Formal `D:\PCL2` was never used as a game directory and no formal save was read. See `docs/PROJECT_STATE.md` and `docs/TEST_MATRIX.md` for exact logs.

PW-12 exposes this projection through four server-authoritative read-only views: `preview`, `risks`, `budget` and `readiness`. Each retains the same preview hash, environment, target, quantity, orientation and hard safety flags. The structured record always contains bounds, material bill, power demand/margin, highest risk, missing authorization, missing backup and BLOCKED readiness. Invalid resource IDs and non-positive quantities fail at parsing; off-thread calls fail typed before RecipeManager access. Command success never means deployment approval.
