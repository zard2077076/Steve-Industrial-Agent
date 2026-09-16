# Architecture

This is a component/reference document, with dated foundation designs below.
Current implementation/evidence is summarized in `PROJECT_STATE.md`; priorities in
`MASTER_PLAN.md`. Historical words such as "frozen", "only" and "pending" describe
that component/version, not a ban on evolving it. Preserve compatibility or provide
explicit migration; do not copy an old capability whitelist into a new player path.

## Direction

Steve Industrial Agent is a typed planning and execution system, not a blueprint paste tool. An LLM may interpret intent and propose candidates; deterministic code owns runtime knowledge, schema validation, resource/space/network checks, execution, verification, recovery, and rollback.

### Phase IV-C player interaction boundary

`SteveEngineerTerminal -> GoalPickerScreen -> PlacementController ->
PlayerPreviewService -> PlayerRelocationService -> PlayerApprovalService ->
SitePreparationCommand` is a presentation/adapter chain over the existing
typed contracts. Client packets contain bounded intent and server-issued
nonces. The server owns world scans, token issuance, terrain tasks, mutations,
salvage and rescan evidence. `ConstructionProgressHud` consumes throttled
status packets and grants no authority.

Player project SavedData is progress, not execution authority. An in-memory
clearing session may pause across disconnect while the server remains alive.
After process restart, the persisted project transitions to a typed paused
reapproval/replan state; it never reconstructs an executor from UI data.

The clean-site output now enters a server-authoritative player material-source
boundary. A client may point at a container, but `PlayerMaterialService`
rechecks the exact world, dimension, position, face, BlockEntity type,
authorization, distance, inventory state and slot/component identity. Only
explicitly selected isolated-world vanilla chests/barrels are admitted; unknown
or private storage networks fail closed.

`MaterialRequirementPlan`, `MaterialSourceBinding` and
`ProjectMaterialLedger` form the loader-neutral transaction contract.
Reservations are project/container/slot/item/quantity scoped and exclude
simultaneous claims by another project. Every item follows an append-only
journal transition (`PREPARED -> WITHDRAWN -> DELIVERED -> CONSUMED`, or the
release/return branches). SavedData preserves bindings, reservations,
transactions, journal evidence and logistics coordinates. Restart never
reconstructs an executor from UI state: unfinished material is paused for exact
reconciliation and return, and construction requires renewed planning and
authorization.

Salvage remains a separate ledger. It can enter a project material ledger only
after an explicit player toggle and a resource/quantity-bounded transfer with
project-salvage evidence. No nearby container or clearing drop is selected
implicitly.

Phase IV keeps C-05 through C-10 behind one mode-neutral construction boundary.
Direct, Bots and Hybrid consume the same `VerifiedPhysicalPlan`,
`ConstructionTaskGraph`, exact material manifest and versioned v606 process
handler. Mode selection changes assignment ownership and which verified tasks a
physical worker performs; it never regenerates topology or grants a Bot generic
interaction authority. In particular, C-10 Bots may transport/build only the
typed plan while the Deployer handler remains the sole owner of the bounded
item-application operation.

The player-visible boundary uses the same path. Exact allowlisted Phase IV
targets first resolve a deterministic plan anchor inside the confirmed pilot
region, then require a same-world `PreparedConstructionSite` whose anchor and
facing match. An authoritative server preview plus
`PreparedSiteExecutionGate` binds site, selection, clean snapshot, plan,
runtime footprint and expiry before the unchanged Direct/Bots/Hybrid entry is
called. Direct performs exact ledger-backed transfer; Bots visibly path to the
selected source, withdraw only reserved slots, carry to staging and deliver;
Hybrid records the shared executor identity for each transaction. The existing
construction graph remains the sole placement authority. Completion UI is
created only from observed executor output plus a balanced ledger. Arbitrary
targets, quantities, coordinates, player inventory, unselected containers and
prepared-site recovery without renewed authorization fail typed.

### Automated real-client acceptance boundary

The CLI-Anything integration is an external test module, not production Mod
authority. `acceptance-harness` launches the real Forge development client only
through `new-player-acceptance-client.sh`; the launcher retains the exact external
game-root/Git-ancestor guard and exact world allowlist. A separate
`clientAcceptanceHarness` source set loads as a second dev-only Forge mod and is
scanned out of the production JAR.

The bridge binds `127.0.0.1`, accepts one bounded authenticated JSON request per
connection and publishes a token-free endpoint inside the disposable game root. A
random 256-bit token travels through a mode-0600 one-time file that the bridge
deletes immediately after reading, so it is absent from endpoint JSON and process
arguments. Every mutating Screen/player command rechecks the exact integrated-world
name. Session close removes only disposable harness authority.

Screen automation resolves semantic IDs over the current real widget collection.
Buttons execute their actual `onPress`, text uses the actual `EditBox` responder,
terminal opening uses the real main-hand interaction, and Screen close follows the
native close path. These actions therefore retain the production client packet and
server-handler route; no CLI command may call `createOrder()` or a material service
directly. Screenshots come from Minecraft's native renderer and are published as
verified immutable `preview-bundle/v1` evidence.

This boundary can establish machine-checkable Mac client acceptance but cannot
declare human readability, aesthetics or animation quality. Full construction
scenarios and visual oracles must add evidence above this transport rather than
expanding its arbitrary-command surface.

### Shared graph-neutral fleet coordination

`GraphNeutralFleetCoordinator<G,T>` is the single assignment/lease/recovery
kernel for two through five controlled workers. A domain supplies a typed
`FleetTaskAdapter<G,T>` (identity, canonical graph fingerprint, DAG edges,
capabilities, leases, retry and reconciliation policy) and a matching
`FleetTaskDispatcher<G,T>` (the sole execution boundary). The generic kernel
does not know `ConstructionTaskGraph` or `TerrainPreparationTaskGraph`, cannot
cast between them and cannot manufacture either domain's authority.

`BotFleetCoordinator` remains the compatibility facade for construction and
maps its exact `TaskAssignment`, `TaskExecutionResult`, work-position leases and
evidence into the generic state machine. Site Preparation must implement a
separate typed adapter/dispatcher over its own approved terrain graph while
reusing `FleetWorker`/`BotWorker`, `BotFleetExecutor` policy and the existing
`ConstructionBotEntity` Steve/Alex identities. Approved terrain removal and
salvage are explicit capabilities; arbitrary block use, protected blocks,
entities, containers, player inventories and unknown NBT remain outside the
interface.

Every reload snapshot persists a SHA-256 over the entire canonical domain
authority envelope plus exact dispatcher, assignment, worker, work-lease and
completed-worker identities. Pending work restores only as reconciliation
required. Reassignment requires bound authoritative evidence and an adapter-
approved typed failure code. Worker continuity may be declared for a physical
carried-reservation predecessor; an explicit domain hand-off edge such as
construction `MATERIAL_DELIVERY` ends that continuity without silently moving
inventory between workers.

The shared pre-resource recovery boundary accepts only a trusted plan snapshot,
a running BUILD prefix or BUILD-complete POWER/READY cursor, the exact
session-bound block-only journal prefix and an authoritative rescan of every
modified position. Recovery reconstructs the handler placement cursor from
that prefix and cannot replay completed placements. Resource-bearing or
irreversible histories remain fail-closed. Cancellation first sends the exact
assignment-bound `CANCEL`, then returns only provably conserved carried or
delivered inputs and removes physical workers. Failure cleanup reverses only
exact current states owned by the session journal; external drift is preserved
and reported rather than overwritten.

PW-01 begins the separate player-world deployment-safety layer. `WorldEnvironmentEvidence` is loader-neutral Adapter input containing exact world, gameDir, world-root, Minecraft/loader/mod/runtime/save/server and provenance evidence. `WorldEnvironmentPolicy` supplies explicit allowed and forbidden roots; `WorldEnvironmentClassifier` canonicalizes real paths, follows symlinks, applies independently verified alias identities, rejects traversal and gameDir/save-root mismatch, and emits one immutable `WorldEnvironmentDescriptor`. Directory names never grant authority. Forbidden, formal and unknown results cannot be writable or executable by constructor invariant. This classification is evidence for later policy/guard gates only; it neither reads a save nor creates a session or mutates a world.

PW-02 adds policy as a separate value from classification. `DeploymentPolicy` explicitly caps affected blocks, bounding volume, route length, material cost, rotational stress and mutation count and allowlists environments, canonical roots, adapters, implementations, recipe types, dimensions and an optional bounded time window. Backup, dry-run, human approval and rollback requirements cannot be inferred. Block replacement, player-built blocks, block entities, claims, resource sources and existing-machine reuse use typed fail-closed enums. The formal default has zero mutation authority and no execution permission; constructing an executable formal/unknown/forbidden policy is invalid.

PW-11 is the final deployment-evidence aggregation boundary, not an executor. Its independent verifier evaluates twenty-five exact checks over current physical plan, graph, preview, dry-run, freshness, policy, write guards, risks, budgets, region, approval, backup/restore, permission, rollback, journal and server-thread/chunk evidence. Only a complete isolated result creates `DeploymentReadyPlan`, which is immutable audit data and deliberately has no `ExecutionReadyPlan`, session, handler, world, inventory or mutation capability. Each refusal carries a typed stage/code and complete bounded context; no individual passing check can be reused as authority.

PW-03 makes formal-world exclusion defense-in-depth rather than a planner convention. The pure guard applies the same typed decision to seven mutation-capable entry classes and exposes a non-publicly constructible permit only for a policy-allowed isolated descriptor with current approval, backup, snapshot, region and mutation-budget evidence. The exact v606 boundary still performs its independent real-path proof at each handler invocation, ITEM-route mutation, resource-buffer withdrawal/insertion/output collection and conservative rollback. A session or recovery object therefore cannot cache filesystem authority past a property/marker/root change. Adapter refusal maps back to the existing root execution formal-world code without changing generic graph, physical-plan or session contracts.

PW-04 projects the complete verified chain into a separate non-executable `DeploymentPreview`. `DeploymentPreviewService` accepts only `VerifiedPhysicalPlan` plus bounded loader-neutral observations; it reaches the original production goal, recipes, implementation bindings and `UnifiedMachineGraph` through existing immutable references. It canonically orders placements, replacements, item/power routes, BOM, input/output, protected/block-entity observations, assumptions, approvals, risks and policy violations; binds independent runtime and world-snapshot fingerprints; emits stable JSON; and computes SHA-256 over every field except the hash itself. Versioned adapters must explicitly declare route/power construction materials rather than letting core invent them. No world/server/session/runner/resource handle exists in the preview types, so preview generation cannot load a chunk, consume an item or mutate a save.

PW-05 replaces the preview's preliminary strings with a separately typed `DeploymentRiskAssessment`. `DeploymentRiskContext` contains only bounded read-only facts; `DeploymentRiskAssessor` applies one fixed branch per required category in enum order. Placement/removal, unknown/player blocks, BlockEntities/containers, fluids/fire/lava/explosion/drops, chunk/dimension, stress/logistics/resources, rollback/reload/history, permissions, backup, snapshot and runtime drift are all explicit. Severity is code-owned INFO/LOW/MEDIUM/HIGH/CRITICAL data. `DeploymentRiskFinding` makes `blocksApproval` equivalent to CRITICAL as a constructor invariant, so no caller, Adapter or LLM can downgrade or approve a critical finding.

PW-06 adds a pure arithmetic `DeploymentBudgetService`. It projects preview inputs, output, construction BOM, stress, runtime and affected-block estimate together with bounded read-only intermediate/power/logistics/capacity/storage observations into an immutable `DeploymentBudget`. The budget distinguishes required quantities from policy maxima, computes power margin and journal space, and returns canonically ordered typed violations. It cannot reserve or withdraw an item, access inventory/network/world state or create approval/readiness authority. Formal-world modeling accepts only no-withdrawal or explicitly read-only source semantics.

PW-07 represents region scope as immutable evidence rather than a boolean or capability. `RegionAuthorization` binds an authorizer and optional non-invented owner to exact environment/world/dimension/bounds, the complete 13-operation vocabulary, mutation ceiling, expiration, preview/snapshot/runtime identities, one-time/use state, approval, revocation and provenance. `RegionAuthorizationService` performs only deterministic comparisons and returns ordered typed failures; it cannot consume the authorization or construct a permit. Formal, unknown and forbidden records can model only read-only scan/dry-run with zero mutations and cannot be APPROVED; formal checks additionally always return `FORMAL_WORLD_EXECUTION_FORBIDDEN`.

PW-08 makes approval an externally supplied structured `HumanApprovalToken`, never a boolean. It binds the one-time token hash and explicit authorizer/decision to exact environment, preview, world, snapshot, runtime, reload generation, region, target, quantity, mutation budget, complete `DeploymentPolicy`, expiry and provenance. `HumanApprovalGate` has no issue/approve path: it compares the current request and atomically consumes a matching token once, including under concurrent attempts. Test approval requires the literal `TEST_ONLY` authorizer and an isolated environment; copying it to formal scope is both structurally invalid and a typed gate failure.

PW-09 makes backup a verified isolated filesystem transaction. `BackupPlanner` can enumerate files only after canonical source/backup/forbidden-root checks prove an isolated source and an isolated backup target. `BackupManifest` canonically binds every relative path, size and SHA-256; the plan also records size/capacity, staging-plus-atomic-rename, quiesced consistency, restore drill, retention, failure cleanup, explicit test approval and journal identity. `BackupVerifier` rejects non-isolated environments before touching paths. `IsolatedBackupService` rechecks live space and source/backup manifests, copies through verified staging, atomically publishes, and verifies exact restore fingerprint; it cannot operate on formal, forbidden or out-of-policy roots.

PW-10 splits permission facts from mod integration. Core `PermissionQuery` binds operation, actor, region, world/environment/dimension, source, timestamp, generation and fingerprint; `PermissionEvidence` binds the complete query to UNKNOWN/ALLOWED/DENIED/NOT_INSTALLED, Adapter, verification state and provenance. Only exact current VERIFIED ALLOWED evidence can report construction permission, and formal/unknown/forbidden environments remain false even if an Adapter claims ALLOWED. The non-sealed `ClaimAdapter` lives in `adapter-api`; generic isolated-allow, explicit-deny, unknown and not-installed implementations plus a test-source third-party implementation prove no hardcoded mod dependency.

FS-01/FS-02 begin a separately authorized formal read boundary without changing PW execution contracts. `FormalWorldReadOnlyGuard` owns bounded non-recursive enumeration of the exact direct `saves` root, candidate metadata lookup and every formal file open; `FormalSaveDiscovery` itself has no filesystem API. Discovery reads only `level.dat`/`level.dat_old` through a guard-backed parser and never resolves ambiguity from time or ranking alone. The guard rejects all write/lock/process intents, private player-linked paths and every reparse-like ancestor, and opens callback-scoped channels with READ as the sole option. Audit output is proven outside the formal instance before creation. A deterministic metadata manifest plus configured key-file hashes supplies exact pre/post fingerprint evidence; neither a fingerprint nor a successful read can create region, approval or execution authority.

FS-03 keeps survey output loader-neutral. All Create/storage/power/logistics findings share exact `SurveyLocation` and `SurveyEvidence` provenance but contain no NBT, RegionFile, BlockEntity, Minecraft or Forge object. Offline power/logistics records make live RPM, stress and connectivity unrepresentable; storage records make content capture invalid. `FormalWorldSurvey` and `CandidateIndustrialZone` are deterministic evidence only, permanently non-executable and pending user selection.

FS-04 keeps NBT and Anvil representation behind an internal offline boundary. Every `level.dat` or region open passes through the read-only guard; the region reader streams the fixed header and one bounded compressed chunk at a time. Sector locations, payload lengths, compression, decompressed size, recursion depth, collection sizes, coordinates and the exact supported DataVersion are validated before public values are produced. Section storage follows Minecraft 1.20.1's non-crossing `floor(64 / bits)` values-per-long layout and rejects out-of-palette indices. Internal NBT nodes never leave the survey package. Public records contain only loader-neutral world identity, palette states, packed-section results and location/type-only BlockEntity evidence; player, entity, UUID and inventory/container trees are discarded during parsing. A malformed chunk is typed partial evidence, never a repair attempt or a reason to hide valid sibling evidence.

FS-05 separates scheduling limits from file access and parsing. `SurveyBudgetTracker` is a single-owner-thread, monotonic, all-or-nothing ledger over region, chunk, read-byte, elapsed-time, retained-memory-estimate, candidate-count and deep-radius dimensions. `BoundedSurveyPlanner` consumes metadata only: stable heat ordering drives a small sample, bounded Chebyshev ordering drives neighbor expansion, and only explicit candidate seeds can produce candidate-deep work. One region coordinate is scheduled at most once. Exhaustion freezes additional work and emits immutable partial evidence; it cannot widen a limit, open a path, create a session or convert incomplete coverage into a complete survey.

FS-06 consumes only decoded `OfflineChunkSnapshot` values. `CreateInfrastructureClassifier` maps exact persisted block positions into power, transmission, processing, ITEM/fluid logistics, moving, generic Create and presence-only storage evidence. Known storage is observed; a modded BlockEntity name hint is explicitly low-confidence and runtime-required. BlockEntity input has already been reduced to ResourceId plus position, storage contents remain structurally false/authorization-gated, and offline power/logistics records cannot claim live RPM, stress or connectivity. Classification has no path, NBT, world, process or filesystem authority.

FS-07 consumes only immutable classified findings. `OfflineTopologyApproximator` deterministically considers same-dimension Manhattan-adjacent positions and emits only ROTATIONAL_POWER, ITEM or FLUID approximation edges. Like-to-like persisted axis compatibility can raise an edge to derived high confidence; machine, processing and storage attachments remain derived low confidence. Cross-region edges retain evidence from both persisted source files, incomplete coverage requires explicit unscanned-boundary limitations, and `runtimeConnectivityKnown` is structurally false. The result has no network discovery, path, world, session, process or mutation authority.

FS-08 turns bounded `CandidateZoneObservation` facts into ranked but never selected `CandidateIndustrialZone` values. The scorer owns fixed code-defined weights for clear space, explored coverage, exact nearby-infrastructure distance, BlockEntity/player-risk counts, chunk boundaries, claim status, dry-run feasibility and completeness. It derives infrastructure distance only from retained exact finding positions, canonicalizes all order-sensitive inputs and binds geometry to a SHA-256 zone ID. UNKNOWN/DENIED permission and partial/protected evidence remain fail-closed future-authorization requirements; every output is structurally `PENDING_USER_SELECTION`. Ranking supplies review order only and creates no approval, claim, session or world authority.

FS-09 is a pure allowlisted metadata boundary, not a claim integration. `FormalClaimMetadataDiscovery` consumes only pre-sanitized relative mod-descriptor/config metadata with seven permitted keys, bounded values and explicit rejection of credentials, URLs, private databases and paths outside `mods/...!/META-INF/mods.toml`, `config/` or `serverconfig/`. Known claim-mod identity/version and possible relative data/API hints produce deterministic discovery records and versioned Adapter backlog items. Detection can never set permission to ALLOWED, claim Adapter support, access a database, modify a claim or enable formal execution; result permission is structurally UNKNOWN even when no known mod is detected.

FS-10 validates a four-member batch of existing verified dry-run artifacts rather than reimplementing planning. Each member binds a pending surveyed zone, exact dimension and in-bounds preview anchor to one formal `DeploymentDryRunReport`; standard gravel x3 and iron-sheet x2 plus distinct pack milling/pressing recipe classes are mandatory. Preview hashes must be valid and unique, runtime/snapshot identities equal, claims UNKNOWN and `FORMAL_WORLD_EXECUTION_FORBIDDEN` explicit. Inventory reads, resource operations, sessions, approvals, mutation and execution are structurally false.

FS-11 parses only five fixed token sequences against bounded registered world and preview-target sets. It rejects drive/path syntax, traversal, unknown identities, extra tokens and unbounded quantities with typed survey failures. Successful plans contain only a deterministic relative key below the ignored `work/formal-survey` root; they have no filesystem handle and fix arbitrary-path acceptance, process/session creation, formal writes, inventory reads, approval and execution to false.

FS-12 preflight keeps every formal metadata observation inside `FormalWorldReadOnlyGuard`. Full-tree fingerprints may hash all non-private ordinary files while playerdata, stats and advancements contribute only path/size/mtime and are never opened. This preserves mutation sensitivity for survey inputs without crossing the private-content boundary; equal pre/post fingerprints remain mandatory.

FS-12's real runner is single-threaded and owns no report path, process, session, inventory-content or execution API. It first publishes the complete pre-fingerprint, ranks a bounded metadata sample, reserves region/chunk/read-byte/duration/retained-memory/candidate limits atomically, parses selected Anvil files through guard-owned channels and only then constructs loader-neutral survey/topology/candidate evidence. Sub-header regions and bounded-classification chunks become typed partial evidence without repair. Natural Create geology is excluded from infrastructure, and only machine/power/logistics/fluid/moving categories can seed a candidate. Post-fingerprint equality is a constructor gate for an accepted run.

FB-01 adds a formal-backup destination policy without weakening the frozen isolated-only backup contract. It resolves only existing ordinary platform/instance/source/repository/backup roots, requires the exact repository `work/formal-backups` shape outside the complete formal platform, rejects arbitrary/reparse/existing targets and binds world/source/runtime/time/policy/tool/Git/capacity evidence into a deterministic plan identity. The computed target and staging sibling are non-existent immutable names. The plan structurally forbids overwrite, cloud upload, backup creation and formal writes; FB-02 must independently recheck space, source identity and source fingerprint before copying.

FB-02 uses a separate package-private `FormalBackupSourceGuard`; the survey guard and isolated-only backup service remain unchanged. The service requires complete empty Java/process and formal-source-handle observations, re-resolves exact roots and capacity, recaptures the privacy-preserving survey fingerprint, then creates only the computed external staging tree. Ordinary and player-linked files are transported with READ-only source channels; private bytes flow only to SHA-256 and the verified staging channel and are never exposed to a parser or caller. `session.lock` and fixed temporary suffixes are excluded with size/mtime evidence. The canonical manifest binds every copied path, size, mtime, SHA-256 and privacy mode plus every exclusion. Staging and published trees must exactly match before same-filesystem atomic rename and a backup-only completion marker. Source fingerprints are rechecked before publication and again after the marker; drift removes the marker or staging and never touches the formal source.

FB-03 persists that canonical manifest inside staging before atomic publication and writes a strictly canonical aggregate-only JSON completion marker afterward. `FormalBackupVerifier` can reconstruct `FormalWorldBackupIdentity` from those two ignored control files, independently rehash the exact completed tree and validate world/source/runtime/time/manifest/count/bytes/policy/tool/Git/completion bindings without resolving the formal source. A restore drill requires identical fresh verification, computes a new target only below repository `work/formal-restore-drills`, copies manifest files into a disposable `saves/<save>` tree, rehashes it before and after atomic publication, then uses the existing bounded offline reader to rederive exact WorldIdentity from `level.dat`. Private files are copied opaquely and never parsed; no Minecraft/process or formal-source API exists in the drill.

FB-04 turns candidate ranking into review packages, never selection authority. An available package must bind a valid formal `DeploymentDryRunReport`, exact preview hash, physical-plan identity, runtime/world snapshot, verified backup, risk, material/power/mutation budget, bounds, target/quantity, UNKNOWN claim, operations, rollback, expiration and summary. A real FS-12-style candidate with no valid formal preview uses typed absent optionals plus `FORMAL_PREVIEW_UNAVAILABLE` and `VERIFIED_PHYSICAL_PLAN_UNAVAILABLE`; no placeholder hash or physical identity is manufactured. Batches sort by score and stable identity but structurally require no selected candidate, no approval decision and no execution.

FB-05 separates waiting for selection from requesting approval. A real blocked candidate produces only `AWAITING_USER_SELECTION` with no `ApprovalScope`. A future explicit selection of an available candidate can produce a one-time scope binding exact world/package/zone/selected ID/preview/target/quantity/backup/source/world snapshot/runtime/mutation/material/power/operations/expiry evidence, but its `FormalDeploymentApprovalDecision` remains `ABSENT`. There is no formal-allowed decision state. `TEST_ONLY_ALLOWED` is constructible only for a restore-drill copy, never a formal source, and `ApprovalUseRecord` forbids formal use. The independent hard stop returns `FORMAL_WORLD_EXECUTION_FORBIDDEN` for every formal request/decision combination.

FB-06 parses only the seven fixed `formal backup ...` and `formal deployment ...` commands from B-10. Registries map exact WorldIdentity, BackupIdentity and candidate-package identity; path syntax, traversal, unknown IDs, extra tokens and unregistered verbs fail typed. A command plan contains only identities and a deterministic relative audit key below future ignored formal-management output, with every session/write/approval/inventory/execution flag false. Inventory access remains a separate bounded future authorization request naming exact in-zone positions, generic resource categories, purpose, minimization and expiry; it cannot execute or return contents. UNKNOWN claim readiness is structurally false and may only carry an Adapter backlog.

FB-07/FB-08 apply those boundaries once to the real formal snapshot without introducing a general formal operator. `FormalBackupAcceptanceMain` accepts fixed registered roots and identities, captures quiescence and source fingerprint evidence, invokes the guarded backup once, reconstructs its identity through an independent verifier, and invokes the existing disposable restore drill. The repository-only wrapper performs pre/post process, crash and source-lock checks. Aggregate JSON records identities, counts, bytes and booleans only; canonical per-file evidence stays inside ignored backup storage and private files remain opaque.

The same acceptance binds all eight FS-12 candidates to the completed backup, but missing formal preview and physical evidence keep every package pending and every approval request scopeless. Report generation is therefore a terminal audit projection, not a transition to `DeploymentReadyPlan` or `ExecutionReadyPlan`. There is still no formal decision state, session, handler, resource access or mutation path.

```text
intent -> candidate machine graph -> deterministic validation -> step plan
       -> server-authoritative execution -> real telemetry/output verification
       -> structured diagnosis -> bounded repair/replan -> verification
```

## Module boundaries

- `core`: Java 17 domain values, geometry, typed graphs, planning, diagnostics, recovery policies. No game imports.
- `adapter-api`: immutable snapshots, runtime fingerprints, bounded requests, failure taxonomy, `IndustrialModAdapter`, and mod-specific extension interfaces. No game imports.
- `forge-create-1.20.1`: Forge lifecycle, commands, game access, and versioned adapters. Despite the compatibility name, its registry adapter already supports optional Mekanism namespace detection. A neutral game-module rename is deferred to avoid churn before the first stable JAR.

The current production release structure is one reobfuscated JAR: it merges `core`, `adapter-api`, the Forge host and the versioned Create adapter implementation, but does not embed Create, Mekanism or their runtime classes. Both target mods are optional in the shipped `mods.toml`; versioned Create classes are reached only after loaded-mod/version/thread/world guards. `scripts/Test-PackagedNeitherServer.ps1` validates this exact final JAR as the sole mod in an official Forge 47.4.10 server with neither target mod installed. This proves safe host loading and typed absence, not Create capability without Create. There is currently no separate Create-adapter JAR, so a missing-mandatory-Create dependency diagnostic for such a split artifact is not an applicable release row.

C-03 establishes the first executable typed plan: `WaterWheelMillstonePlan` fixes ten roles, relative coordinates, block IDs, axes, placement order, a bounded preflight volume and the expected milling recipe without any loader imports. C-04 adds a separate `BeltPressPlan` whose typed roles include temporary pulley shafts, two drives, a three-segment belt, press, insertion funnel, chest and exact iron recipe. `CreatePlanAdapter` and `CreateBeltPressPlanAdapter` expose typed progress or immutable physical evidence. Exact Create inventory, recipe, belt-connection, pressing-behaviour and block-state calls remain inside `forge1201.adapter.create.internal.v606`.

Dependency direction is `game module -> adapter-api -> core`.

## Factory diagnostic boundary

`FactoryHealthSnapshot` and `FactoryHealthReport` ask exactly six questions and retain every
immutable observation, including healthy, not-applicable and unknown evidence. A report is
structurally read-only: construction rejects nonzero world mutations or automatic-repair
authority. `FactoryLiveProbeEvaluator` accepts only bounded loader-neutral numbers for output
space, logistics progress, rotational power, Forge Energy and structure/orientation. It owns no
world, inventory, executor, network or order handle.

The Forge projection resolves those measurements only from authority already owned by a plan:
the selected delivery container; the active material courier or three-mode task snapshot;
`VerifiedPhysicalPlan` component cells and rotational route; the exact IE Metal Press order
origin; or exact endpoints published by a registered active warehouse runtime. It does not
discover nearby containers or inspect player inventory. Create calls remain in the v606 adapter,
IE calls remain in the v1020 adapter, and optional-mod/lifecycle/unloaded evidence becomes
`LIVE_WORLD_UNAVAILABLE` rather than healthy.

The live session snapshots are value projections only: task identity/class, bounded counts,
phase, progress ticks and worker activity. They expose neither a tick method nor a mutation
callback. Slow logistics remains healthy through its bounded threshold; a stopped route, wrong
block/orientation, absent power endpoint, overstress, FE connection mismatch or full exact
destination yields a typed fault. No exact observer means `UNKNOWN`; it does not authorize a
neighbour scan, repair, reroute or order transition.

## Public Alpha release boundary

The release configuration utility is launcher-neutral and resolves explicit CLI
values, environment, a local config file, safe discovery and typed failure in
that order. Canonical paths and reparse ancestors are checked. It is not yet the
Forge pilot runtime authority: the accepted IWP still injects exact instance,
formal-world and backup identities as JVM properties, including repository-work
backup evidence. Replacing that bootstrap without weakening the hard stop is an
open release gate.

Release diagnostics live in `core` as a fixed allowlist and deterministic
single-entry archive. The Forge command layer supplies only public runtime
versions and dimension identity; paths, chat, player data, seeds, coordinates,
container contents and world files never enter the payload. There is no network
or upload API. The shipped unit remains one reobfuscated project JAR; all loader,
pack and mod dependencies are user-installed and excluded from the artifact.

## Industrial adapter model

`IndustrialModAdapter` exposes common identity, runtime, and snapshot operations. Create and Mekanism keep separate specialized contracts so the common layer does not pretend rotational stress, Forge Energy, fluids, chemicals, and heat are interchangeable.

`IndustrialPowerRequest` and `IndustrialPowerRequirement` apply the same rule inside production
planning. `ELECTRICAL_NETWORK` requires positive FE and the exact verified network identity;
`ITEM_FUEL` requires exact bounded item/quantity rows and burn ticks, carries zero FE and cannot
name an electrical graph. The planner scales fuel into ordinary `FUEL/CONSUME` material lines, so
reload and completion accounting can use the same item ledger rather than an invented energy
unit. A fuel-powered descriptor must expose both the item-input port and HEAT resource
capability; an electrical descriptor must expose its electrical port before either power shape is
accepted.

The IE 10.2.0 Alloy Smelter keeps its state, recipe and inventory code in
`ImmersiveEngineeringV1020AlloySmelterProduction`; only template placement/formation is shared
with other IE machines. That adapter observes live slot deltas and extracts real stacks, but has
no player-container, ledger, order or output-construction authority. Those remain responsibilities
of the server-authoritative order layer.

The reviewed Alloy Smelter order follows the same separation at runtime. `AlloySmelterOrderSavedData`
stores the immutable physical binding (owner, exact source and staging positions, live recipe/input/
fuel/output identities, plan/runtime/baseline hashes and baseline cells), while
`IndustrialPlayerOrderSavedData` owns lifecycle/effects/report and `PlayerMaterialSavedData` owns
reservations and transaction journal. The order service may ask the versioned adapter to observe or
perform one reviewed machine action, but it must move every player-owned item through the frozen
material courier. Item fuel settles as `CONSUMED` material with an empty FE evidence map. Until the
common report commits, the claimed output carries an order UUID on both stack and entity; this is
temporary exactly-once evidence against vanilla entity merging, not a new item identity, and is
removed before pickup. Reload begins paused, re-observes durable effects against machine/world/
ledger evidence, and resumes only the first uncommitted stage. Cancellation is accepted only before
batch admission and must return `WITHDRAWN`/`DELIVERED` rows and release `PREPARED` rows before the
order can become `CANCELLED_MATERIALS_RETURNED`.

G-12 adds a deliberately test-source-only counterexample to Create-shaped design. `TestOnlyVirtualIndustrialAdapter` implements only `IndustrialModAdapter`, normalizes one virtual processor and builds a three-node ITEM graph with separate input/output ports and a virtual processing capability. Its success and typed-jam handlers are registered against the unchanged generic plan, step, session, runner and verification rule. This proves those contracts can carry a non-Create-shaped fixture; it does not add a production adapter, real third-party dependency or physical processing evidence.

`GenericResourceType` is the first implemented generic graph primitive. Its stable loader-neutral values are `ITEM`, `FLUID`, `ROTATIONAL_POWER`, `ELECTRICAL_ENERGY`, `CHEMICAL`, and `HEAT`. This is a compatibility vocabulary, not a claim that all six transports have runtime implementations; only existing item and Create rotational behavior currently has physical acceptance evidence.

The minimal implemented `UnifiedMachineGraph` is immutable, loader-neutral and bounded to 256 nodes, 1,024 ports and 1,024 edges. A `MachineNode` owns a unique role and implementation identity, relative position, optional facing/axis, required capabilities and validated configuration. A `MachinePort` belongs to one known node and declares a resource type, input/output/bidirectional mode, optional side/capacity and constraints. A `MachineEdge` joins known compatible ports with a directed or bidirectional resource flow, optional throughput and connection requirements. Construction rejects duplicate identities, orphan ports, dangling endpoints, resource mismatches and incompatible direction modes before a graph can be observed.

The graph vocabulary supports typed ports and edges for:

- kinetic power (speed, direction, stress);
- Forge Energy;
- items;
- fluids;
- Mekanism chemicals;
- heat;
- processing dependencies.

Cross-mod connections are allowed only where resource types and capabilities match, often through explicit buffers.

`WaterWheelMillstoneGenericExecutionPlan` derives the production C-03 graph from `WaterWheelMillstonePlan`: six relative nodes, ten ports and five directed power/item edges. `BeltPressGenericExecutionPlan` likewise derives the production C-04 graph from `BeltPressPlan`: nine relative nodes, twenty ports and ten directed power/item edges. Both graphs are carried by their generic execution plans rather than test-only fixtures, but each still describes one fixed layout and does not route or synthesize anything. Neither case claims an implemented transport beyond the physically accepted item/Create-rotation paths.

G-13 adds a separate immutable `PlanAnchor` containing one absolute `BlockPos3i` and one `QuarterTurn`, plus `PlanTransform` for whole-plan Y rotations. A transform resolves relative coordinates with exact-overflow arithmetic and rotates `Direction6`, plan and machine axes, plan facing, `MachineOrientation` and optional `MachinePort.side`. Transforming a `UnifiedMachineGraph` rebuilds only its geometric/orientation values: graph, node, port and edge IDs, edge endpoints, resource modes, capabilities and configuration remain unchanged. `WaterWheelMillstonePlan` and `BeltPressPlan` now derive every placement, build state, belt endpoint and bounded preflight position through the same transform; the generic graphs derived from those plans match transforming their zero-rotation graphs directly. The legacy `at(origin)` factory is exactly `QuarterTurn.ZERO`.

Plan rotation does not imply planning. G-13 supports only the four whole Y quarter turns for already trusted templates; it does not mirror, search alternative layouts, choose machines or avoid obstacles.

G-14 adds a separate loader-neutral, read-only fail-before-mutation gate. Each trusted plan exposes its final role ownership as immutable `PlacementTarget` values. `BasicPlacementFeasibility` requires an exact observation for every unique target position, is bounded to 256 targets and 1,024 conflicts, and returns one deterministic complete `PlacementFeasibilityReport` containing `UNLOADED`, `NOT_REPLACEABLE`, `PROTECTED` and `INTERNAL_ROLE_CONFLICT` entries plus every conflicting position. The Forge bridge collects only loaded server-thread block state, uses actual replaceability, and accepts an explicitly supplied bounded protected-position policy. It runs before either v606 executor or generic session is created; exclusively unloaded results retain `CHUNK_NOT_LOADED`, while all other infeasible reports return `PLAN_REJECTED`. The broader transformed C-03/C-04 preflight volumes still run afterward for their existing loaded-area checks.

Basic feasibility also does not imply layout generation. It evaluates the selected final layout and returns typed failure; it does not mirror, search another anchor, route around obstacles, connect existing power or choose a machine/recipe. C-04 temporary pulley shafts are deliberate construction transitions and therefore are not competing final-role ownership targets.

## Goal-driven planning boundary

The post-`generic-foundation-v1` planning phase is loader-neutral and read-only. A validated typed production goal is resolved only against deterministic `RecipeCatalog` and `MachineCapabilityCatalog` inputs. Core may construct a bounded process dependency graph, return a typed planning failure, generate and explicitly score coordinate-free candidates, map a selected candidate to a `LogicalMachineGraph`, and verify dependency/quantity/capability/resource consistency. It cannot read a Minecraft world, call a game recipe manager, execute a handler, invent a coordinate, or accept LLM/free-text output as authority. Catalog ordering, graph expansion, failure traces, candidate identities, scoring and tie-breaking must be deterministic for equal inputs.

The initial catalogs are pure JVM fixtures rather than production facts. Their interfaces must later permit a versioned `CreateRuntimeRecipeCatalog` adapter to translate recipes from the actual runtime recipe manager, retain runtime fingerprints and pack modifications, and refuse recipes it cannot explain. That adapter, runtime discovery, layout solving and execution binding are deliberately outside P-02 through P-10, so existing C-03/C-04 live recipe evidence remains separate and unchanged.

`MachineCapabilityCatalog` is likewise declarative: each Adapter-owned capability names supported recipe types, typed input/output resource boundaries, bounded power/resource requirements, actions, evidence kinds, diagnostics and explicit version/fingerprint limits. Core checks typed recipe compatibility but deliberately treats Adapter version strings as opaque; a future Adapter must apply its own version semantics and publish only capabilities it can actually support. Catalog declarations never grant execution authority and cannot fabricate ports, actions or runtime availability.

`ProcessDependencyGraphBuilder` expands a typed ITEM goal against only those two supplied catalogs and an explicit read-only availability context. Each alternative is a coordinate-free topologically ordered list of scaled recipe steps, intermediate ITEM dependency edges, consolidated raw leaves, owned-resource allocations and byproducts. Expansion copies owned inventory per branch, caps depth/steps/edges/candidates, rejects cycles with a stable resource trace, treats optional byproducts as evidence rather than guaranteed target production and returns `PlanningResult` success or typed failure instead of an empty-list convention. It does not call execution handlers or inspect a world.

Planning failure is part of the public data contract, not an exception side channel. The exact eleven `PlanningFailureCode` values carry a bounded failure location, nonempty ordered dependency trace, user-readable reason and canonical optional recipe alternatives. Invalid model construction still fails fast, while normal unsatisfied planning returns `PlanningFailureResult`; `PlanningSuccess` cannot contain zero graphs.

`CandidatePlanGenerator` converts each satisfiable dependency alternative into an immutable read-only candidate. It records selected recipes, canonical capability requirements, raw/intermediate/owned resources, topological step order, every scaled quantity conversion, dependency edges, estimated processing time and derivation evidence. Each step receives an `UnboundMachineNode` containing only logical step/capability IDs, while `UnboundSpatialLayout` contains node identities and always reports unbound. Candidate SHA-256 identity is derived from logical goal/recipe/resource/dependency content only; future layout coordinates cannot change or contaminate this phase because no coordinate field exists.

`DeterministicPlanScorer` is an explicit lower-is-better policy, not an AI judgment. `PlanScoringWeights` records every bounded factor and goal-preference multiplier; `PlanScoringContext` separately records currently available capabilities and an ordered preferred-mod list. `PlanScore` exposes each BigInteger contribution, integer owned-resource utilization in basis points and the total. Ranking compares total then logical candidate ID, so equal inputs and weights always select the same alternative without numeric overflow or implicit tie behavior.

`CandidateLogicalGraphMapper` validates the selected recipe/order/conversion/unbound-node views and resolves every required capability through the supplied `MachineCapabilityCatalog` and `PlanningContext`. It then creates exactly one `PROCESS` node per candidate step plus explicit raw, owned and target boundaries. Logical ports and edges retain exact loader-neutral resource IDs, generic types and planned quantities; edge kinds distinguish raw, owned, intermediate and target flow, while capability resource requirements make power needs explicit. Equal inputs produce identical IDs and ordered maps. Expected candidate, capability, Adapter, resource, allocation, target and quantity problems return `LogicalGraphMappingFailure` with a stable stage and bounded trace.

`LogicalMachineGraph` deliberately has no implementation ID, position, orientation, side, physical capacity, handler or world reference. The Adapter-owned implementation catalog now produces an implementation-bound graph from real declarations; a later complete layout binding must provide coordinates, orientations, sides, capacities and routing. Only then may a materializer construct the existing strict `MachineNode`/`MachinePort`/`MachineEdge`/`UnifiedMachineGraph`. None of those frozen physical interfaces or the C-03/C-04 production graphs changed.

IB-02 through IB-04 supply the additive implementation vocabulary under `core.binding`. `MachineImplementationDescriptor` has a stable real `implementationId`, Adapter/capability/family/recipe/resource declarations, intrinsic processing mode, logical port and power contracts, evidence/diagnostic support, explicit physical-proof level, proven recipe IDs, bind permission, Minecraft/mod/runtime attribution, provenance, limitations and deterministic priority. The exact v606 Adapter publishes only `create:mechanical_millstone` and `create:mechanical_press` from matching recipe/capability snapshots and accepted C-03/C-04 evidence. A descriptor cannot execute and cannot carry a game object.

`ImplementationPortContract` covers ITEM input/output, FLUID input/output, ROTATIONAL_POWER input, redstone control, signal input/output and an explicit unsupported-special role. Resource-bearing roles require their exact `GenericResourceType`, typed input/output mode, bounded minimum/optional throughput, required/multiplexable flag, continuous/pulsed semantics, connection compatibility and collectible evidence. Control/signal/special roles stay explicit without pretending to be one of the six transferable resources. No binding model contains a position, side, direction, orientation, adjacency, distance, layout, unified graph, session or executor. `MachineImplementationCatalog` provides canonical identity/capability/type/Adapter/fingerprint/mod/evidence/execution queries over a one-fingerprint reload generation.

`CandidateImplementationGenerator` applies capability, recipe type, Adapter, runtime, mod, logical port, continuous power, evidence, execution-support and safety-policy filters without any implementation-specific branch. Its lower-is-better score exposes descriptor priority, preferred-Adapter penalty and limitation penalty; a permitted equal score uses canonical implementation ID, while strict policy returns `MULTIPLE_IMPLEMENTATIONS_AMBIGUOUS`. `ImplementationBindingService` binds every PROCESS node and preserves the exact verified logical plan, conversion, runtime Ingredient identity/selection and selection trace in `ImplementationBoundMachineGraph`. It never binds boundary nodes or fabricates a physical connection.

LP-01 through LP-12 add the next gate without changing planning, binding or the frozen physical graph types. `MachineGeometryDescriptor` is pure versioned data: an implementation footprint equals its composable component rules, clearance is explicit, accepted orientations are finite, logical implementation-port IDs receive relative positions/sides/capacities, and a bounded rotational-power topology plus stress demand is declared. The exact Create v606 catalog publishes only millstone rules consistent with C-03's water-wheel/gearbox/shaft path and press rules consistent with C-04's belt/press/funnel/chest topology. Physicalization does not call either fixed execution plan or handler.

`LayoutConstraints` requires an explicit non-placeholder anchor, ordered accepted orientations, maximum candidates/search radius/route length/search operations, item/power/stress capacity and a fingerprinted `PlacementSnapshot`. Missing cells and unloaded chunks are not treated as air. Candidate modules use canonical node order and bounded offsets; every position/side is a pure quarter-turn transform. ITEM edges use a deterministic neighbor order and finite breadth-first route search around actual snapshot cells. Physical process, external-boundary and bounded-power-source roles become strict typed `MachineNode`/`MachinePort`/`MachineEdge` values only after placement and routing succeed. `PhysicalizationVerifier` independently rechecks binding identity, snapshot/runtime attribution, orientation, footprint, clearance, physical-port coverage, every item/power route, capacities, stress, strict unified graph, declared search bounds and absence of execution authority before its package-private `VerifiedPhysicalPlan` gate.

The Forge snapshot bridge only observes block state on the authoritative server thread. Normal acceptance refuses unloaded chunks. DeceasedCraft's ModernFix deliberately unloads all spawn chunks; only the disposable R-09 world uses a separate explicit read path capped at 49 chunks before observing actual cells. Neither path places or removes a block. `VerifiedPhysicalPlan` is therefore physical authority but still not execution authority: only the next `ExecutionReadinessVerifier` may authorize a session.

`BindingVerifier` independently rechecks exact process coverage, catalog/descriptor/runtime identity, capability/recipe/Adapter/mod compatibility, logical port mapping, power, accepted evidence/policy, quantities, canonical Ingredient identity, acyclicity and deterministic selection. Only its complete fifteen-check success can construct `VerifiedImplementationBoundPlan`, whose constructor is not public. Normal refusals use the exact bounded `BindingFailureCode` set and complete stage/node/capability/recipe/candidate/Adapter/fingerprint/constraint/trace/intervention/next-step context. The runtime bridge only translates preserved `RuntimeIngredientSelection` values; `/industrialagent bind create` is a typed read-only presentation of the same gate, not a layout or executor.

`PlanningVerifier` is the final read-only gate for this phase. It deterministically remaps the candidate against the supplied capability catalog/context, then independently checks graph identity and exact shape, process capability/resource declarations, recipe-source and capability Adapter availability, ITEM resource identities/types, dependency traceability, process acyclicity, target source/quantity and complete raw/owned/intermediate input allocation. Success contains exactly one immutable evidence record for each of eight `PlanningVerificationCheck` values. Normal rejection returns one of ten bounded typed `PlanningVerificationFailureCode` results and never exposes a plan. `VerifiedLogicalPlan` has no public constructor and carries only the candidate, logical graph and check evidence; it cannot execute, bind a machine, materialize `UnifiedMachineGraph`, choose a layout or access a world.

R-01 adds the first runtime-knowledge boundary without changing that planner. `CreateRuntimeRecipeCatalog` exists only under `forge1201.adapter.create.internal.v606`; it reads one `ServerLevel`'s `RecipeManager` on the server thread, derives a deterministic fingerprint from exact runtime versions, world identity, reload generation and canonically ordered recipe ID/type pairs, then may cache only a loader-neutral `RuntimeRecipeCatalogSnapshot`. `ForgeCreateRuntimeRecipeCatalogs` owns instances per server and by `ServerLevel` identity, invalidates them through a datapack reload listener and removes them at server stop. No Minecraft `Recipe`, `Ingredient`, registry object or world reference is stored in `core`, `adapter-api` or a snapshot.

The adapter boundary returns `RuntimeRecipeCatalogResult`: success requires a nonempty immutable `RuntimeRecipeCatalog`, while normal runtime, thread, reload, recipe, ingredient, capability and planning problems carry a complete `RuntimeKnowledgeFailure` with stage, optional recipe/target/ingredient subjects, Adapter ID, runtime fingerprint, ordered trace and explanation. R-02 enumerates every actual `create:milling` and `create:pressing` recipe, translates the reliably expressible entries, retains non-fatal output warnings, and records one typed limitation for each rejected target entry without aborting the rest. The boundary never calls a Create executor, creates a session, constructs a physical graph or changes the world.

Runtime entries deliberately do not weaken or replace P-03 `CatalogRecipe`. A `RuntimeRecipeCatalogEntry` contains unresolved sealed `RecipeIngredient` identities: exact resource, stable any-of candidates, tag identity with a fingerprinted candidate snapshot, or explicit unsupported-complex evidence. Only entries without unsupported-complex inputs enter a runtime snapshot. R-03 resolves those inputs for one planning request into the unchanged exact `CatalogRecipe` shape: candidates forbidden by typed material constraints are removed; positive owned quantities win by descending amount and stable ResourceId; otherwise the canonical candidate wins. Empty or stale snapshots fail typed. `ResolvedRuntimeRecipe` retains the original entry and one `RuntimeIngredientSelection` per input, so recipe/tag identity is not lost after resolution.

R-04 keeps runtime machine facts additive in the same way. `RuntimeMachineCapabilityDeclaration` wraps the unchanged loader-neutral `MachineCapability` with exact runtime attribution, source, a reload-sensitive fingerprint and recipe-scoped physical proof. The v606 catalog publishes only milling and pressing; both declare ITEM ports, continuous ROTATIONAL_POWER, supported diagnostics/evidence and the already proven C-03/C-04 action boundary. A physical-availability claim is invalid unless it names at least one accepted recipe proof. The declaration contains no Minecraft/Create class, block entity, coordinate, orientation, layout, session or implementation ID, and a reload-built recipe fingerprint must also be the capability fingerprint.

R-05's `RuntimeKnowledgePlanningService` is a pure snapshot consumer. It requires identical recipe/capability runtime attribution, resolves ingredients for one `ProductionGoal`, builds `ProcessDependencyGraph` alternatives, generates and ranks `CandidatePlan` values with explicit fixed weights, maps only the selected candidate to `LogicalMachineGraph`, then admits it through `PlanningVerifier`. `RuntimeVerifiedPlanningResult` retains the exact resolved catalog, ranked score evidence, capability snapshot and fingerprint around the existing non-publicly-constructible `VerifiedLogicalPlan`. Normal dependency, mapping and verification failures are translated into complete runtime-knowledge failures. Neither service nor result imports world/loader classes or contains coordinates, orientation, implementation ID, `UnifiedMachineGraph`, session, action invocation or mutation authority.

R-06 adds only a Forge command shell around that service. Brigadier parses a namespaced resource and bounded positive long before code runs; the handler requires the authoritative server thread, current `ServerLevel`, loaded Create Adapter and matching live snapshots. `RuntimePlanningCommandFormatter` remains loader-neutral and converts success into three bounded user lines plus one deterministic structured log containing goal, candidate count, real recipes/types/sources, raw/owned/intermediate resources, capabilities, Adapter, selected Ingredients, conversions, verification checks, fingerprint and trace. Failure carries the complete typed runtime failure. The command never accepts coordinates or free text, and it has no reference to execution plans, action handlers, world writes, sessions or LLM code.

R-08 treats mod/config/KubeJS/datapack inspection only as compatibility input, never as a recipe catalog. Disk files can prove version and customization differences but cannot prove reload conditions, registry/tag contents or the resulting `RecipeManager`. Therefore standard-profile counts and fingerprints remain scoped to that profile. A future isolated-pack-profile must load disposable copies outside the formal instance and pass the same authoritative server-thread discovery/reload/read-only gates before any pack-specific runtime claim is accepted.

R-09's production-pack probe is default-off and entered only when an explicit JVM property is present. It then requires an exact canonical expected gameDir, a fixed profile marker, a world root below that gameDir, a forbidden formal-pack root mismatch and `FMLLoader.isProduction()`. R-09B uses that gate only for startup/version/mod-count evidence and immediate normal shutdown; it has no catalog, planner, session, executor or mutation authority. R-09C may extend the same gated boundary only with authoritative server-thread read-only knowledge export.

R-09C's Create-specific exporter is confined to `adapter/create/internal/v606`. It enumerates the live RecipeManager once, verifies complete type-count conservation, delegates milling/pressing semantics to the unchanged `CreateRuntimeRecipeCatalog`, and serializes only loader-neutral mapped results plus explicit runtime evidence. Sequenced assembly is inspected through v606 APIs solely to retain ordered step/loop/transitional identity in diagnostics; it is marked `RECIPE_TYPE_UNSUPPORTED` and never enters `RecipeCatalog`, dependency planning or logical mapping.

`GenericProcessSpec` is the loader-neutral bounded process description shared by the existing milling and pressing specifications. It owns typed input/output quantities, optional byproducts, recipe identity/type, required machine capabilities, required completion-evidence identities, exact/at-least input consumption and output verification modes, a maximum processing wait, and bounded adapter extension data. `MillingProcessSpec` and `PressingProcessSpec` wrap that common value while retaining compatibility accessors and their separate power-wait budget. Each wrapper's complete requirement set drives its production PROCESS rule: C-03 additionally requires the millstone-inventory observation, while C-04 additionally requires belt input, an observed press cycle and chest output with distinct input/output identities.

`GenericExecutionStep` is a sealed loader-neutral contract with the immutable `BoundedExecutionStep` implementation. Each step has a stable ID and phase, bounded typed precondition/success/failure descriptors, a typed handler/operation action, timeout, allowlisted retry policy, cancellation flag, optional typed rollback action and required evidence IDs. Conditions route through evaluator IDs and actions route through handler IDs; neither carries a callback, reflection target, script or arbitrary code. Parameter values are copied and length/count bounded, but remain untrusted adapter inputs that an action handler must schema-check before any world access. The step descriptor itself cannot read or mutate a world; G-06 separately supplies controlled handler dispatch.

`GenericExecutionPlan` joins one immutable graph, process and unique ordered step list. `GenericExecutionSession` is an immutable in-memory state value over that plan: it fixes the current step/phase, attempt, start/latest ticks, exact completed prefix, complete verification evidence, journal-linked world-change references and explicit terminal detail. Its transition methods enforce monotonic progress and distinguish `CANCELLED`, `TIMED_OUT`, `FAILED` and `COMPLETED`; cancellation also respects the current step policy and retry requires an allowlisted failure plus remaining attempts. The references carry stable change/step/tick identities, while the separate G-10 journal owns the complete records. The live session still owns a trusted in-process plan rather than deserialized executable callbacks; G-11 serializes its data through a separate snapshot.

`WorldChangeJournal` is the loader-neutral immutable G-10 safety record for one session. It is bounded to 4,096 unique monotonic entries and records complete before/after block identities and properties, optional schema-tagged block-entity data, injected typed resources, irreversible recipes and their consumed/produced resources, affected positions, source step and tick. Its pure planner proposes at most 64 reverse-order restores only where a supplied current snapshot exactly equals the recorded after-state. Missing or changed state produces typed warnings. Any injected-resource or irreversible-processing entry disables topology rollback entirely and produces an explicit no-compensation warning, so the model cannot duplicate or invent resources.

G-11 persists recovery state without persisting execution authority. `RecoveryGraphSnapshot` stores the graph ID, sorted node/port/edge IDs and a deterministic SHA-256 over every graph descriptor. `RecoveryPlanSnapshot` adds the plan/recipe identities, ordered step IDs and a SHA-256 over the complete graph, process and step descriptors. `RecoverySessionSnapshot` stores every session state field without a live plan object. Together with the complete `WorldChangeJournal`, exact modified-position list and saved tick, these form a `RecoveryCheckpoint`. `RecoveryCheckpointCodec` is a versioned canonical binary format with `SIAR` magic, a 4 MiB payload bound, strict collection/string/enum/tag validation, canonical UTF-8, CRC32 and trailing-byte rejection.

`SessionRecoveryReconciler` accepts checkpoints only against an explicitly supplied registry capped at 128 trusted runtime plans. It first rejects unknown or definition-drifted plans, terminal sessions and any injected-resource or irreversible-processing history. A valid discovery exposes only the session/plan identities and required positions through `RescanRequired`; it deliberately withholds a live session. `ForgeRecoveryWorldScanner` then performs one authoritative server-thread, loaded-chunk-only scan of registered block state and optional full block-entity SNBT. Only exact equality with every journal after-state creates a `Resumable`; missing or changed state returns `steve_industrial:recovery/stale_session` with a typed reason and never mutates the world. The isolated `RecoveryReloadAcceptanceFixture` proves the generic path through Minecraft `SavedData` and two independent server JVMs using one vanilla-block plan.

Production C-03 and C-04 use the additive `RecoverableExecutionSession` adapter contract without changing `GenericExecutionSession`, `BoundedStepRunner`, verification or checkpoint schemas. Their only accepted checkpoint is after BUILD completed and before POWER began: the cursor is `POWER/READY`, completed steps are exactly BUILD, and the journal contains exactly ten block changes and no resource entries. Acceptance-only `SavedData` retains the typed physical anchor because recovery fingerprints deliberately omit absolute placement. After reload, the trusted relative plan and journal after-states must reconcile before the adapter reconstructs C-03 at placement cursor 10 or C-04 at build cursor 8; resume skips basic placement feasibility because the selected layout already exists, but retains authoritative thread, mod/version, build-height, loaded-chunk and per-tick preflight guards. Create 6.0.6's `KineticBlockEntity` initializes and serializes the transient byte `NeedsSpeedUpdate` before its first attach tick but never reads it. Both v606 plan adapters therefore canonicalize only that byte after confirming the live block entity is the exact Create kinetic type and the requested no-duplicate position set exactly equals the trusted physical plan; block identity, properties and every other SNBT field remain exact. Raw snapshots are proven stale, four C-03 or six C-04 canonical kinetic snapshots reconcile, and an independent dirt fault remains typed stale. Any later resource-bearing checkpoint is still refused by G-11, so these are two bounded production recoveries rather than a general automatic resume facility.

The adapter API exposes a typed `ExecutionCancellationResult` containing the immutable journal, modified positions and `RollbackReport`. The exact v606 bridge captures registered block state plus full block-entity SNBT, never loads a target chunk, rechecks current state after planning, validates restore identity/properties/schema/SNBT before its world write, and verifies the restored state by readback. C-03 and C-04 cancellation runs through `BoundedStepRunner`, becomes terminal before rollback is considered and cannot invoke another handler afterward. This is limited in-memory cancellation safety, not persistence, repair or universal rollback.

`BoundedStepRunner` is the Create-free execution driver. It routes descriptor IDs only through explicitly supplied immutable registries of `StepConditionEvaluator`, `StepActionHandler` and optional `GenericVerificationRule` instances, invokes at most one action handler per session tick, never loops a retry in the same tick, fixes timeout against `currentStepStartedTick`, and records READY/ACTION/VERIFY so a succeeded action is not invoked again while verification waits. An attached rule must declare exactly the step's required evidence before its typed result can advance or terminate the session; the original passing current-step evidence gate remains the compatibility behavior for steps without a rule. Handler implementations are contractually one bounded non-blocking action per call. The runner contains no world object, loader/mod API, sleep, network call or thread wait. Both C-03 and C-04 production use this path through separate strictly versioned Create handlers; G-12 exercises the same path with a test-only virtual handler, but no non-Create production handler exists yet.

`VerificationEvidence` replaces the earlier session-only evidence reference with a full immutable observation. The exact loader-neutral kinds are block present/state match, network connected, power present, input consumed, process started/completed, output produced/stored, no new crash and custom adapter evidence. Every record carries a unique evidence and requirement ID, source step, observing source, target, schema-tagged bounded observed/expected values, observation tick, pass state and optional bounded typed diagnostic. A boolean pass can never omit the compared values. Sessions store these complete records, and the runner's current minimal gate counts only passing evidence from the current step with a matching requirement ID.

`GenericVerificationRule` adds bounded immutable required and optional `EvidenceRequirement` sets, an explicit tick timeout and a permission gate for custom adapter evidence. Evaluation considers only the latest observation tick for each requirement in the current step interval, retains every complete considered record, checks kind and optional source/target constraints, and returns distinct `PASSED`, `PENDING`, `FAILED` or `TIMED_OUT` results with typed failure detail. `forProcess` derives the timeout from `GenericProcessSpec` and refuses a rule that omits any declared completion-evidence ID. Both production plans register one rule per step. C-03 PROCESS requires input consumed, process completed, output produced and the Create-specific millstone inventory observation; C-04 PROCESS requires those common physical outcomes plus actual belt-input observation, press-cycle observation and chest storage. Optional records never replace required proof, and the physical GameTests remain authoritative because value-schema interpretation stays in the adapters.

## Threading and safety

World capture and mutation run as short bounded operations on the server thread. Snapshots are immutable and carry runtime/schema identity and game tick. Expensive graph construction, planning, and LLM calls run off-thread. Basic placement observations are collected once on the server thread without loading chunks, before a session exists; execution then revalidates its broader preconditions against fresh state before every mutation.

Create internal APIs are confined to exact implementation packages such as `forge1201.adapter.create.internal.v606`. The optional-mod-safe facade checks authoritative thread, runtime version and loaded-chunk preconditions before dispatching to the Create 6.0.6 reader. That reader performs one O(1) block-entity capture from Create's synchronized network cache; it never creates a network or walks `KineticNetwork` members. Mekanism internal APIs will be confined to `forge1201.adapter.mekanism.internal`. The registry adapter intentionally uses only stable Forge APIs.

C-03 and C-04 are four-step generic sessions (`BUILD`, `POWER`, `FEED_INPUT`, `PROCESS`) with separate exact v606 action handlers that retain all Create block-state, kinetic, recipe, inventory, belt and pressing-behaviour access. Each runner tick invokes at most one handler operation; building places one planned block or performs the one bounded Create-native belt connection, while later operations only poll or perform one bounded feed. Every actual block mutation is journaled around the write, feed records the typed injected input, and physical completion records the irreversible recipe. Cancellation is server-thread-authoritative, marks the runner terminal first and performs only loaded-chunk exact-state restoration. C-03 rechecks its transformed 175-position preflight and completes only after four positive live speeds, the exact live recipe, actual input disappearance and real millstone output. C-04 rechecks its transformed 150-position preflight and completes only after six positive live speeds, plan-derived belt movement, real belt transport, the observed 240-tick press cycle, input disappearance and chest output through the planned funnel. Both physical fixtures pass zero plus two non-default quarter turns and read back transformed block states. Neither path sleeps, performs network access or runs a large graph search on the server thread.

Optional-mod availability that does not require a world is isolated in `ForgeRuntimeAvailabilityAdapter`. It uses Forge lifecycle/runtime metadata and returns `AdapterResult<RuntimeFingerprint>` with the same exact `UNSUPPORTED_RUNTIME` absence semantics as the world-facing adapters. The development-only client profile subscriber waits for a real `ScreenEvent.Render.Post`, runs this adapter on the Minecraft client thread, and asks Minecraft to stop only after the profile contract passes. It never reads or mutates a client world and is enabled only by a repository userdev profile property. Create 6.0.6's client Mixin configuration resolves an optional JourneyMap target even when that mod is absent, so `create-only-client` adds one empty, never-loaded target class from the dedicated `createOnlyClientAcceptance` source set. The clean build asserts this run-only class is absent from both production JARs.

High-risk Mekanism nuclear/radiation systems are outside the automatic action whitelist. They require a later, separate safety architecture with backups, rollback, containment, cooling and waste validation, emergency shutdown, fault injection, and explicit per-plan user confirmation.

## Goal-driven execution integration

Execution is a distinct typed transition. `VerifiedPhysicalPlan` is independently rechecked with exact runtime, target-area, chunk, input, power, stress, placement, route, journal and world-classification evidence. Only the package-gated `ExecutionReadyPlan` may cross into `CreateV606GoalDrivenExecution`; planning, binding and physicalization values remain non-executable.

The v606 Adapter converts each verified physical process node to the already accepted generic C-03 millstone, C-04 belt-press or C-05 opposed-crushing-wheel plan. It compares every materialized component against the verified geometry, constructs only the verified ITEM-route interior at one block action per tick, uses real chest inventories as the resource boundary, and delegates processing and evidence to the existing generic sessions, rules, journals and exact Create handlers. A root controller runs BUILD, CONNECT, FEED, PROCESS and VERIFY in order and invokes at most one handler action per server tick.

Cancellation is idempotent and rolls back only safely reversible construction. Reload is accepted only at the proven BUILD checkpoint for a single process node after exact live-world reconciliation; resource-bearing or irreversible histories fail closed. Target, route, power and output are revalidated live. Formal-world identity, missing marker, external game directory and duplicate active root sessions are typed refusals. No LLM/free text, unchecked coordinate, player save, unverified Create recipe type, Mekanism or nuclear authority enters this boundary.

## C-05 crushing execution boundary

C-05 adds a fixed loader-neutral `CrushingWheelPlan` and four-step generic plan
without changing the shared runner or executor contract. The physical topology
owns chest, down-facing hopper, two creative motors and two crushing wheels.
Create owns the runtime controller created in the one-block gap. The geometry
catalog describes only a truthful continuous drive-to-wheel rotational route;
the exact versioned handler verifies the independent second drive/wheel and the
runtime controller instead of inventing a physical graph edge through the gap.

`Create606CrushingWheelActionHandler` is the only layer that imports the Create
6.0.6 controller/recipe internals. BUILD journals one owned placement per
invocation and records the final wheel/controller transition atomically. POWER
requires four live kinetic captures, non-zero equal-magnitude opposed wheel
rotation, a valid down-facing controller and non-overstressed networks. FEED
requires empty controller/hopper/chest state, validates the exact live
`create:crushing` recipe and introduces one real input entity. PROCESS observes
real input disappearance, controller completion and actual allowed chest
contents, including optional byproduct counts. The goal resource buffer removes
those real contents once and never substitutes a memory flag.

Direct, Bots and Hybrid share the same verified physical plan, task graph,
assignment identities, reservation/journal rules and final evidence comparison.
C-05 recovery is accepted only for an exact BUILD prefix or BUILD-complete,
pre-resource POWER boundary. The final wheel and controller cannot be split
across a checkpoint. Any injected input or irreversible processing remains a
hard recovery refusal. Wrong direction, foreign output-path state, lost power,
changed placement, formal world and cancellation all terminate with typed
evidence before unsafe continuation.

## C-09 compacting execution boundary

C-09 is a separate typed Basin/Press implementation, not an alias for the C-04
belt-press line. Its fixed owned topology contains one creative motor, one
mechanical press, one Basin and one output chest. The loader-neutral process
contract carries bounded counted ITEM inputs, exact optional FLUID inputs, one
deterministic ITEM output, no byproducts, `create:compacting`,
`BasinHeatMode.NONE` and exact Basin/press/output evidence. The generic graph
adds a FLUID boundary only when the verified recipe declares one.

`Create606BasinPressActionHandler` is the only layer that imports the Create
6.0.6 Basin and compacting-recipe internals. It validates the exact live recipe
shape, heat and exact fluid condition, withdraws only reviewed whole-bucket
materials, injects declared mB before Create's live matcher, records each staged
ITEM/FLUID identity, observes the live press behavior and moves only actual
Basin output inventory. It does not invoke
Create's recipe-processing entry points or construct the expected result.
Geometry/materialization compare every verified physical component with the
typed plan before a session can start.

The C-09 wiring enters the existing goal-driven runner and journal. Item-only
and reviewed one-cycle lava compacting pass shared three-mode logistics and
directed physical GameTests. Resource-bearing recovery, multi-cycle fluids and
post-feed compensation remain later gates. HEATED compacting remains unsupported
until a separately tested heat-source and Bot-exclusion policy exists.

## C-08 mixing execution boundary

C-08 owns one creative motor, mechanical mixer, Basin, Blaze Burner and output
chest. Its loader-neutral contract carries counted concrete ITEM selections,
exact optional FLUID inputs, deterministic ITEM output, NONE or HEATED and no
fluid output/byproduct.
Concrete selections remain bound to live Ingredient alternatives; tag/any-of
support never becomes free-form item substitution.

`Create606BasinMixerActionHandler` confines Create 6.0.6 recipe, Mixer and
Burner internals. It supports reviewed one-cycle water buckets and rejects
unreviewed/bulk fluid, fluid output, SUPERHEATED, residue/crafting remainder,
unknown NBT and probabilistic/multiple output before processing. NONE verifies
an unheated Burner. HEATED consumes exact ordinary coal through Create's Burner
insertion path and requires `KINDLED`; no block-state or NBT shortcut
manufactures heat. Completion requires real mixer-cycle, Basin consumption,
heat and exact chest-output evidence.

The runtime catalog retains a loader-neutral `RecipeHeatRequirement` beside
each unchanged recipe entry. An additive v606 sidecar verifies that metadata
against the same planning, binding, physicalization, runtime-fingerprint,
session and step identities before materialization. HEATED currently admits
only one exact Basin/Mixer execution and reserves one ordinary coal from the
plan-owned world buffer. Reservations are exclusive, bounded, cancel-released
and reload-reacquired only after exact pre-resource revalidation; no player or
arbitrary-container resource authority is added. Direct, Bots and Hybrid all
consume the same sidecar and the same physical handler.

## Phase IV composite execution boundary

`CompositeProductionCoordinator` remains loader-neutral and owns only the
typed graph lifecycle: dependency admission, exact buffer reservations,
backpressure and contamination checks, shared-infrastructure leases,
fingerprint-bound restore and terminal node state. Physical transfer and
processing stay inside versioned v606 handlers; the coordinator never inserts
an intermediate, constructs an output or turns a failed observation into a
success flag.

Restoring a wrapper never trusts a persisted running flag. Every running node
becomes `RECOVERY_REQUIRED` and records the exact snapshot generation. Resuming
requires graph ID and SHA-256, node ID, generation, handler reconciliation and
the complete incoming-edge reservation map to match. Cancellation can close a
recovery-required line and restore its reserved buffers, while a mismatched or
partial recovery record leaves the node non-executable. The concurrent v606
wrapper restores its graph and the single-owner shared lease together, verifies
the physical interlock still exists, and only then admits the surviving line.

The v606 branch/merge wrapper gives the merge node ownership before physical
routing begins. A route refusal therefore names that exact active node rather
than a completed sibling. On any branch or merge failure, completed sessions
receive normal cleanup, a terminal failed session receives failure cleanup,
and a thrown/nonterminal session plus any active sibling receive typed
cancellation. Cleanup succeeds only after exact source restoration, empty
delivery buffers, zero warnings and removal of every session-owned route
block. Directed contamination, backpressure and input-loss fixtures execute in
their own GameTest directory so large arenas cannot share transient Create
entities through vanilla template spacing.

## Controlled production pilot boundary

The production pilot adds no new planner or executor. A default-off R-09 JVM property extends the existing exact filesystem gate and starts two typed goals only after the pack's live knowledge, logical planning, implementation binding and physicalization suites pass. The goals enter `CreateV606GoalDrivenExecution` through the same sixteen-check readiness result used by standard acceptance. Output JSON records recipe and Ingredient provenance, verifier counts, physical placements/routes, journal cardinality, phases, trace, quantities, cleanup and the runtime fingerprint.

The no-player dedicated-server fixture explicitly advances real Create block entities once per bounded root tick because the pack's empty-server behavior does not reliably dispatch their normal lazy/tick lifecycle. This bridge is property-, profile- and real-buffer-gated; it invokes Create's own water-flow, motor, millstone, belt, press and funnel logic and never writes a speed, inventory result or success flag. Standard execution remains normal world-tick driven. Physical ITEM-route interiors always block every implementation component even when source/target clearance is opened for port access.

The pilot's authority ends at the disposable repository-owned world. The exact gameDir/marker/production-JAR checks, formal-root exclusion, external before/after fingerprints, cleanup, normal shutdown and residual-process/crash checks are mandatory. Passing this pilot does not authorize a formal player world, existing-base connection, general survival logistics, new Create types, sequenced assembly, cross-mod processing, Mekanism or LLM coordinates.

## PW-12 read-only command boundary

`CreateDeploymentDryRunCommand` parses only a typed resource ID, bounded positive quantity and optional quarter-turn, then calls the exact v606 `CreateV606DeploymentDryRunService` on the authoritative server thread. The service resolves current runtime knowledge and stops after `VerifiedPhysicalPlan`, bounded placement observation, deterministic `DeploymentPreview`, `DeploymentRiskAssessment` and `DeploymentBudget`. `DeploymentDryRunReport` structurally fixes every execution/side-effect flag false; the formatter exposes four views without any path to an execution-ready plan, session, handler, resource operation or world mutation. A distinct isolated dry-run marker prevents command acceptance from inheriting execution-pilot authority.

## Isolated writable player-world pilot boundary

The IWP path adds no parallel executor. Its loader-neutral discovery and region
types sit above PW-01/PW-07 and below PW-11. Discovery produces identity evidence,
not a write permit; region confirmation produces player/session evidence, not a
`RegionAuthorization` or `DeploymentReadyPlan`. Later preview-specific
authorization must still pass PW-03 and all PW-11 checks before an existing
Create handler can run.

The Forge command layer is enabled only when the real game directory equals an
explicit launch property, remains outside `D:\PCL2`, sits above its own `saves`
root, and both the instance and world marker contents match. Runtime recipe,
world/dimension, player/session, region and preview identities are rechecked at
each transition. Formal WorldIdentity or formal-root intersection hard-stops
before particles, block scans, backup or execution.

Region preview is a tick-sliced server-authoritative read. It processes at most
4,096 cells per tick, never loads an absent chunk, never writes a block and
retains only bounded counts plus a digest. The visible outline uses particles;
there are no temporary boundary blocks to journal or clean up.

`PilotDeploymentCommand` joins the exact confirmed context to the already
accepted runtime-knowledge, logical-planning, implementation-binding,
physicalization, twenty-five-check deployment-readiness and sixteen-check
execution-readiness chain. Only gravel x3 and iron sheet x2 are exposed. A
successful dry-run remains zero-authority; a separate acknowledged start uses a
three-second countdown, one bounded handler action per server tick, a dedicated
world chest for exact TEST_ONLY inputs, creative-only isolated power and the
existing `CreateV606GoalDrivenExecution` phases and journals. Player inventory
is never inspected.

Lifecycle commands bind the original world identity and dimension but do not
reuse a pre-execution region-content fingerprint after the owned session changes
blocks. Cleanup is restricted to journal positions, the authorized region and
the dedicated resource buffer. Exact after-state equality is required except
for Create's runtime-managed belt `part` transition; an already-air belt cell is
accepted only when clearing its connected journal neighbor caused the removal.
The C-03 typed structure contains its falling-water column with 17 planned
placements, and GameTest scans the local 125 cells to prove water exists only in
the three intended positions.

Command recovery remains deliberately narrower than arbitrary handler replay.
The server persists a bounded player checkpoint in world `SavedData`, while
trusted executable descriptors still come only from the runtime plan registry.
`recovery-status` exposes the actual persisted stage. `resume` verifies exact
instance/world/dimension/owner/region/preview/backup/runtime/definition/journal
identity and performs a bounded authoritative rescan before rebasing only the
current step timing. BUILD-mid, BUILD-complete and pre-resource CONNECT may
resume; uncertain PROCESS refuses and VERIFY finalization is idempotent. No
recovery path repeats placement, consumption or output.

## Phase IV C-10 bounded Deployer execution

C-10 does not grant a Bot or Create's fake player generic interaction
authority. `DeployerInteractionPolicy` fixes one target
(`OWNED_DEPOT_ITEM`) and one face (`DOWN`) while making entity/combat/block-use,
container/player-inventory/private-storage, unknown-NBT and unknown-side-effect
permissions impossible to construct. `DeployerPlan` materializes only a
plan-owned downward Deployer, its direct motor, an exact Depot and an output
chest.

The version-confined handler supplies the processed stack through
`DepotBlockEntity` and the exact held stack through the Deployer item
capability. It resolves the same live `ItemApplicationRecipe` that Create will
execute and checks its processed Ingredient, held Ingredient, keep/consume
behavior, deterministic output, fluid absence, crafting remainder and NBT
boundary. Completion comes only from the Depot stack changing to the live
recipe result together with the exact Deployer held-item before/after delta.
The output chest and shared resource buffer receive that real stack; neither
the handler nor a test constructs the declared product.

The generic graph models processed and held inputs as separate boundary edges
and the runtime implementation exposes two distinct ITEM input ports. Current
bound-plan materialization safely defaults to `CONSUMED`; retained-tool
semantics require the common recipe-metadata/reservation extension and cannot
be inferred from a recipe name. Resource-bearing recovery remains fail-closed
until the common C-06-through-C-10 recovery gate.

The bounded world-workpiece variant shares one physical backend across modes.
Direct calls it behind `DirectWorldExecutor`. Bots reuse the Phase III
`ConstructionBotEntity`, `BotWorker`, `BotFleetCoordinator` and
`BotFleetExecutor`; the C-10 adapter only performs bounded adjacent-cell
navigation and then delegates the exact typed task. It exposes no scheduler,
generic right-click, item insertion/extraction, player inventory or teleport
primitive. Hybrid uses the existing `HybridExecutor.safeDefaults`: material
readiness routes through Steve/Bots, while the sensitive Create machine task
and authoritative terminal rescan route through Direct. Hybrid evidence is
rebound to the one Hybrid assignment with its physical route preserved.

Phase IV-S keeps terrain preparation as a separate typed domain before
construction provenance exists. `TerrainPreparationFleetTaskAdapter` exposes
only approved terrain tasks, capabilities, predecessors and work cells;
`TerrainPreparationFleetDispatcher` alone translates the bounded
`BotClearingExecutor` result into the shared fleet update vocabulary. Both use
the same graph-neutral 2-5 worker coordinator and registered
`ConstructionBotEntity`, while `ConstructionFleetTaskAdapter` remains the only
construction mapping. No terrain path creates a `ConstructionTask`,
`VerifiedPhysicalPlan`, round-robin scheduler or alternate Bot entity.

Explicit grading is represented by one immutable
`TerrainGradingSpecification`: two X/Z corners, target surface Y, bounded
clearance/fill depth, selected ordinary block state, exact removal/placement
cells and the complete initial-state fingerprint. High terrain and obstructing
volume become approved mining tasks; missing terrain becomes bottom-up
`FILL_MINOR_HOLE` work and the target plane becomes exact `LEVEL_SURFACE`
work. All three use the same typed terrain adapter and graph-neutral fleet.
Runtime mutation remains one bounded action per tick; Bot navigation may use
only the exact work rectangle plus a one-cell access buffer joining the player
and dedicated supply chest. The access buffer grants no mutation authority.
Fill material is consumed from that exact authorized supply/salvage container,
never from player inventory or an unrelated/private container.

Containers, block entities, dangerous media and unbreakable/protected cells
are a separate destructive authority boundary. The first scan only produces a
`ForcedTerrainRemovalAuthorization` candidate and canonical warning hash; it
does not mutate the world. A player-confirmed hash triggers a second complete
scan, and execution requires exact agreement on player, expiry, grading hash,
effective snapshot hash and every `ForcedTerrainRemoval` risk flag. Drift
invalidates the authorization. Confirmed cells are replaced with air without
reading or transferring their block-entity/container data, and evidence counts
destroyed containers, block entities, dangerous media and unbreakable blocks.
This explicit permanent-data-loss path does not widen normal classification,
generic block use, entity interaction, arbitrary coordinates or unknown NBT
mutation outside the exact confirmed cells.

The only cross-domain hand-off is `PreparedSiteExecutionGate`. It consumes a
fresh `PreparedConstructionSite`, its exact `ConfirmedSiteSelection`, the
existing `ExecutionReadyPlan`, and an authoritative post-clearance planning
record. It fails closed unless world, dimension, site/selection identity,
clean-site snapshot, plan/runtime snapshot, anchor, facing, protected cells,
bounded footprint and expiry all agree. Its resulting
`PreparedSiteExecutionAuthorization` has no public constructor and contains no
executor or mutation surface. The existing `CreateV606GoalDrivenExecution`
and `CreateV606ThreeModeExecution` accept that typed authorization and then
delegate to their unchanged construction paths; Site Preparation never
becomes a `ConstructionTaskGraph` and no second Bot scheduler is introduced.
