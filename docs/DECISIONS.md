# Decisions

## ADR-050: Block RC1 until reproducibility and final visual clean-room evidence pass

Deterministic ZIP controls, a successful ordinary clean build and prior player
acceptance are necessary but do not substitute for two completed empty-cache
builds or a visual lifecycle of the exact final JAR. A bounded external download
timeout therefore produces a blocked pre-RC state. It may generate local
allowlisted staging for inspection, but cannot produce the RC1 tag or a
publish-ready claim.

## ADR-051: Keep release diagnostics allowlisted, local and manually shared

The diagnostic exporter accepts only fixed public fields, redacts path- and
secret-shaped values, creates exactly one text entry in a deterministic ZIP and
writes only to a fixed instance-relative directory. It has no network client,
automatic uploader, chat/player/world-file reader or arbitrary output path.
Compatibility uses one loader-neutral exact-matrix decision shared by tests and
the Forge command.

## ADR-052: Treat external config validation as partial until Forge consumes it

A resolver script that validates paths is not a player runtime. The writable
pilot remains non-portable while Forge commands require IWP-only JVM properties
and repository backup evidence. RA-02, RA-06 and RA-10 therefore remain blocked;
the public docs must state this and RC1 packaging must refuse by default. The
future bridge must preserve exact markers, verified backup/restore evidence and
the unconditional formal-world hard stop.

## ADR-001: Independent repository and adapter-first integration

Use an independent Forge project rather than copying the entire Steve upstream. Upstream `034afb53...` declares MIT in README/mod metadata but lacks a standalone license file, has no Create integration, and contains partially unconnected plugin/execution paths. We may reuse validated concepts later, with explicit attribution, rather than importing unstable code wholesale.

## ADR-002: Loader-neutral core

Minecraft, Forge, Create and Mekanism types cannot enter `core` or `adapter-api`. Immutable snapshots cross the boundary. This keeps planning and diagnostics fast, deterministic and testable without a game launch.

## ADR-003: Exact installed Create target

Target Create 6.0.6 build 150 because its publish time and dependencies match the installed JAR built from Git `338bfa0...`. Development Forge uses official Recommended 47.4.10 while external runtime compatibility includes installed 47.4.0. Only stable Forge APIs may rely on patch compatibility until a 47.4.0 launch test passes.

## ADR-004: Industrial common layer without false unification

`IndustrialModAdapter` owns identity/snapshot lifecycle. Create retains kinetic semantics; Mekanism retains energy, fluid, chemical, heat and side-configuration semantics. Unified graphs use explicit resource-typed edges.

## ADR-005: Optional Mekanism

The observed pack has no Mekanism. The production mod metadata marks both Create and Mekanism optional during architecture bring-up; adapters fail closed when their target is absent. Development test profiles will later add exact Mekanism dependencies without changing the user's pack.

## ADR-006: Nuclear automation disabled

No reactor, turbine, radiation, polonium, plutonium, or nuclear-waste construction action may be registered until the dedicated safety gate in the requirements is implemented and tested. Future support remains opt-in per concrete plan.

## ADR-007: Persist state, never persisted execution authority

Recovery checkpoints may persist complete bounded session and journal data plus canonical plan/graph identities and fingerprints, but persisted handler/evaluator descriptors never become executable authority. Reload must match an explicitly registered trusted runtime plan and perform an authoritative exact world rescan before exposing a resumable session. Terminal sessions, resource injection, irreversible processing, unknown definitions and any state drift fail closed as typed stale sessions; there is no blind automatic resume.

## ADR-008: Transform trusted plans without changing topology identity

Whole-plan rotation is a loader-neutral value transformation applied before execution. `PlanAnchor` owns the absolute position and one of four Y quarter turns; `PlanTransform` rotates relative geometry, state orientation and port sides together while preserving graph/node/port/edge identities and edge connectivity. Default factories remain zero-rotation compatible. Transform support does not authorize mirroring, obstacle avoidance, machine selection or automatic layout generation; placement feasibility remains a separate fail-before-mutation gate.

## ADR-009: Reject a selected layout before creating an execution session

Basic placement feasibility is a bounded loader-neutral evaluation over explicit final-role targets and exact read-only site observations. It must report every known unloaded, non-replaceable, protected and internal-role conflict deterministically before the adapter creates an executor/session or mutates the world. Forge owns authoritative server-thread observation and receives protected positions from an explicit policy input; core does not import game or claims APIs. An infeasible result is a typed refusal, not permission to search, repair, overwrite or silently drop conflicts.

## ADR-010: Resume production handlers only from explicit pre-resource safe boundaries

A production adapter may expose the optional `RecoverableExecutionSession` contract only where it can reconstruct all private handler state without repeating a world mutation or resource operation. C-03 accepts exactly the BUILD-complete `POWER/READY` cursor with ten block changes and no injected or irreversible resource history. The loader persists the physical anchor separately, rebuilds the trusted runtime plan, and may construct the v606 handler only from a `Resumable` returned after exact authoritative rescan. Resume does not re-run selected-layout feasibility because those planned blocks already exist, but all runtime/thread/chunk guards remain. Resource-bearing or any other cursor is a typed refusal, not a best-effort replay. This adds an adapter opt-in; it does not change the established core session, runner, verification, journal or checkpoint interfaces.

## ADR-011: Canonicalize only version-proven transient adapter state

Core recovery equality remains exact. An exact version adapter may canonicalize a field only when the target mod's local implementation proves that the field is transient, is not read as persisted authority and is bounded to an identified runtime type. Create 6.0.6 constructs every `KineticBlockEntity` with `updateSpeed=true`, emits `NeedsSpeedUpdate:1b` from `write`, ignores that key in `read`, and clears the flag in `attachKinetics`. The C-03 and C-04 v606 adapters may therefore remove only that byte after the authoritative world lookup confirms a live `KineticBlockEntity` and the complete scan positions match the trusted physical plan; they may not ignore arbitrary SNBT, block identity, properties, inventory, controller, network, speed or recipe state. Acceptance must show the raw snapshot fails closed, the canonical snapshot resumes, and unrelated world drift still returns typed stale-session.

## ADR-012: Prove deterministic read-only planning before runtime discovery

Goal-driven planning starts with bounded loader-neutral goals, deterministic recipe/capability fixture catalogs, dependency graphs, typed failures, coordinate-free candidates, explicit scoring and read-only verification. No planning milestone may call Minecraft, Forge, Create, an LLM, an execution handler or a world. Equal inputs must produce equal ordered graphs, candidates, scores, failure traces and selected results. Runtime recipe discovery is a later adapter responsibility that must preserve actual pack modifications and runtime fingerprints; it cannot be substituted by hard-coded default recipes as production truth. The frozen `generic-foundation-v1` execution interfaces remain unchanged unless a separately reported fundamental conflict requires explicit review.

## ADR-013: Add a logical graph layer before implementation binding and physical materialization

P-09 does not weaken the frozen physical `MachineNode`, `MachinePort`, `MachineEdge` or `UnifiedMachineGraph` contracts. `MachineNode` deliberately requires a concrete implementation ID, relative position and orientation because it represents a graph that is ready for a trusted physical plan. A planning candidate has none of those facts. Supplying `(0,0,0)`, a placeholder implementation or optionalizing the physical record would fabricate authority or damage the accepted C-03/C-04 execution semantics. The selected architecture is therefore `ProcessDependencyGraph -> CandidatePlan -> LogicalMachineGraph -> ImplementationBoundMachineGraph -> UnifiedMachineGraph`.

1. **Logical layer responsibility and boundary.** `LogicalMachineGraph` is a bounded immutable loader-neutral resource-flow graph. A `PROCESS` node corresponds one-to-one with a candidate processing step and carries only the step ID, required capability IDs and declared power/resource requirements. `RAW_RESOURCE_SOURCE`, `OWNED_RESOURCE_SOURCE` and `TARGET_SINK` boundary nodes make external ITEM flow explicit without pretending that they are installed machines. Logical ports retain the exact loader-neutral resource ID, generic resource type, direction and planned amount; they have no side, capacity claim or Adapter port identity. Logical edges retain the same exact resource identity and distinguish raw, owned, intermediate and target flow. No logical type may contain a block position, orientation, implementation ID, game class, executable handler or world reference.

2. **Relationship to existing planning data.** `ProcessDependencyGraph` remains the recipe-expansion truth, including scaled steps, raw/owned quantities and producer-to-consumer dependencies. `CandidatePlan` remains the scored alternative and supplies selected recipes, quantity conversions, unbound capability nodes and planning evidence. `CandidateLogicalGraphMapper` validates those two views are internally consistent, resolves every capability strictly through `MachineCapabilityCatalog`, and emits the logical graph. It may derive only logical resource requirements declared by the candidate and capability catalog. Existing `UnifiedMachineGraph` remains the final physical topology consumed by trusted execution plans; it is not returned by P-09 mapping.

3. **Implementation-binding stage.** A later Adapter-owned `MachineImplementationCatalog` selects a real implementation for every process and boundary node and maps each logical port requirement to an actually declared physical port. The result is an `ImplementationBoundMachineGraph`: implementation and Adapter/version identity are known, but positions, orientations and port sides are still absent. Binding must prove the chosen implementation supplies every required capability, resource type, action and evidence contract. It must never infer a Java class, block ID or port from a logical name. P-09 defines the boundary but does not add runtime Create discovery or choose a real implementation.

4. **Layout and physical materialization stage.** A later layout step supplies a complete `PhysicalLayoutBinding` for every implementation-bound node and port: unique relative positions, orientations, physical sides, capacities and connection/routing data. Only after exact coverage and conflict validation may `UnifiedMachineGraphMaterializer` call the unchanged physical constructors. The materialized graph preserves the logical graph, node, port and edge identities; layout data changes definitions but not identity. Basic placement feasibility and authoritative world checks still occur after a physical layout exists and before execution. P-09 performs no layout search or world access.

5. **Typed failures.** Expected mapping problems return `LogicalGraphMappingFailure`, never an empty graph or placeholder data. Initial codes cover inconsistent candidate structure, missing/incompatible capability declarations, unavailable capability Adapter, unsupported resource type, unresolved input allocation, missing/ambiguous target producer and quantity overflow. The failure carries stage, candidate ID, optional step/node/resource subjects, a bounded trace and user-readable detail. Future implementation binding and physical materialization use their own typed result branches for missing implementation/version/port binding and missing/conflicting layout/orientation/routing/capacity respectively; they do not reuse exceptions as normal absence.

6. **Impact on existing interfaces and tests.** P-09 is additive in `core`: no signature or validation change to the four frozen physical graph types, `PlanTransform`, recovery fingerprints, C-03/C-04 plans, execution sessions, runner or verification model. Existing physical graph tests remain authoritative. New pure JVM tests must prove one process node per candidate step, raw/owned/intermediate/target ITEM edges, explicit power requirements, deterministic identity/order, capability-catalog enforcement, typed failure and the complete absence of position/orientation/implementation fields. Fresh C-02/C-03/C-04 regressions are still required because P-09 is a mandated protection point even though production paths are unchanged.

7. **Minimal migration.** First add only the logical values, mapping result/failure and `CandidateLogicalGraphMapper`; keep P-07 candidate and frozen physical records unchanged. Then pass focused JVM tests, the full pure suite, clean build and fresh physical regressions in one P-09 implementation commit. Future work may add implementation binding, layout binding and the materializer as separate milestones, then opt generated plans into them. Existing C-03/C-04 fixed physical graphs are not migrated, redesigned or generalized during P-09.

## ADR-014: Snapshot runtime recipes at an exact versioned boundary

The runtime catalog is owned by the exact Forge 1.20.1/Create 6.0.6 implementation package, not by `core`, `adapter-api` or the frozen physical graphs. It reads `RecipeManager` only on the authoritative server thread, orders recipe IDs/types before fingerprinting, and may cache only a loader-neutral immutable result for one concrete `ServerLevel`, runtime fingerprint and reload generation. Minecraft `Recipe`, `Ingredient`, registry and world objects never cross the boundary or remain cached. Server shutdown drops all world-scoped owners.

Datapack reload is an explicit lifecycle state. The Forge reload listener marks existing catalogs stale before apply, advances the generation, clears the snapshot and exposes `DATAPACK_RELOAD_IN_PROGRESS` until apply completes; the next read rebuilds from the current manager. The catalog fingerprint includes exact Minecraft/Forge/Create attribution, world identity, reload generation and the ordered runtime recipe ID/type inventory, so equal state is deterministic and invalidation is observable.

Normal discovery and mapping absence use a sealed nonempty-success versus complete typed-failure contract. A success cannot contain an empty `RuntimeRecipeCatalog`; a single unrepresentable recipe becomes a traceable limitation while other mapped recipes survive. R-01 first proved a real 2,609-recipe discovery with `RECIPE_MAPPING_FAILED` rather than pretending the fixed C-03/C-04 IDs were a runtime catalog. R-02 now maps milling/pressing entries and retains typed limitations/warnings without changing `LogicalMachineGraph`, `VerifiedLogicalPlan`, `UnifiedMachineGraph` or execution authority.

## ADR-015: Keep unresolved runtime ingredients beside the frozen resolved catalog

The real Create 6.0.6 iron pressing recipe uses `forge:ingots/iron`, so mapping it directly to P-03 `CatalogRecipe.inputs` would require either choosing one tag member during discovery or changing the accepted exact resolved-recipe contract. Both are wrong: the former erases tag identity and depends on registry order; the latter would destabilize the completed deterministic planner. Runtime discovery therefore publishes a separate loader-neutral `RuntimeRecipeCatalogEntry` with sealed exact, any-of, tag-reference and unsupported-complex ingredient identity. Tag entries retain the tag ID, sorted runtime candidate snapshot and runtime fingerprint. Unsupported-complex values are evidence for a typed limitation and are never admitted as usable catalog entries.

R-03 performs deterministic request-specific resolution into unchanged `CatalogRecipe` inputs. It prefers positive goal-owned quantities, uses canonical ordering when quantities tie or no candidate is owned, filters typed forbidden resources and retains a resolution trace back to the original runtime entry and ingredient identity. Empty candidate snapshots and runtime-fingerprint drift return typed failures; no tag is permanently collapsed during catalog construction. This additive layer changes none of `LogicalMachineGraph`, `VerifiedLogicalPlan`, `UnifiedMachineGraph`, C-03/C-04 execution or the `generic-foundation-v1` tag.

## ADR-016: Attribute machine capability at runtime without binding an implementation

R-04 wraps the existing `MachineCapability` rather than adding runtime or physical fields to the frozen logical graph. The exact v606 adapter publishes canonical milling and pressing declarations only after matching Minecraft 1.20.1, Forge 47.4.x, Create 6.0.6, Adapter ID and the recipe snapshot fingerprint. ITEM ports, continuous ROTATIONAL_POWER, action/evidence/diagnostic declarations and accepted version limits remain loader-neutral.

`physicalExecutionAvailable` means that this repository has real physical evidence for at least one named recipe, not that planning has selected a concrete executor. Therefore each true claim must carry a nonempty stable set of accepted recipe IDs (`create:milling/cobblestone` or `create:pressing/iron_ingot` at this checkpoint), while implementation ID, coordinates, orientation, ports, layout and sessions remain absent. Runtime reload replaces the declaration snapshot with the new fingerprint; it never reuses a capability claim against stale recipes.

## ADR-017: Join runtime facts only through the existing verified logical pipeline

R-05 does not add a parallel Create planner. One loader-neutral orchestration service requires matching recipe/capability snapshots and then invokes the accepted R-03/P-05 through P-10 chain in order: ingredient resolution, dependency expansion, candidate generation, fixed auditable scoring, logical mapping and complete verification. Runtime provenance wraps the resulting `VerifiedLogicalPlan`; it does not enter or weaken the logical graph contract.

All recipes that are actually present in the runtime snapshot may participate in upstream expansion. Consequently the standard profile's gravel goal legitimately resolves to andesite→cobblestone→gravel rather than assuming cobblestone must remain a raw leaf. Batch counts use tested ceiling division, so requested output may exceed the target only by the final deterministic recipe batch. Physical proof on a capability is not implementation selection: R-05 still emits no implementation ID, placement, direction, port binding, unified graph, session or executor call.

## ADR-018: Keep the runtime planning command typed, server-authoritative and presentation-only

R-06 uses Brigadier's `ResourceLocationArgument` and bounded `LongArgumentType`; malformed identifiers and nonpositive quantities cannot enter the planning service. The command checks server thread, world and Create availability before reading the world-scoped catalog, then delegates all facts and decisions to the R-05 loader-neutral service. It does not accept coordinates, material scripts or natural-language plan text.

Command presentation is a bounded loader-neutral value. A successful command returns one while emitting a concise three-line result and a complete deterministic structured log; a typed failure returns zero and includes stage, optional recipe/target/Ingredient, Adapter, fingerprint, trace and explanation. Presentation has no authority to transform `VerifiedLogicalPlan` into an execution graph or to call an existing C-03/C-04 executor.

## ADR-019: Treat pack files as compatibility evidence, not runtime recipe truth

R-08 may read version manifests, mod filenames, configs, default configs, KubeJS and generated datapacks from the formal DeceasedCraft installation, but it may not launch the installation, inspect saves/worlds or modify any external file. A stable before/after metadata fingerprint is required. This audit can identify likely differences and recipe producers; only an authoritative `RecipeManager` read from a disposable isolated profile can establish actual recipe IDs, tag contents, quantities, limitations and runtime fingerprint.

The formal pack uses the target Minecraft/Create versions but a different Forge patch, hundreds of additional server mods and KubeJS Create recipes. Consequently standard-profile counts remain standard-profile evidence. Planned R-09 must keep its run directory outside `D:\PCL2`, copy only into ignored disposable storage, use a fresh world and never write the project JAR or generated state back to the client, server or player data.

## ADR-020: Gate the production-pack probe with exact filesystem identity

R-09 must exercise the clean production JAR rather than a userdev-only fixture, but an accidentally enabled probe in a normal server would be unsafe. The probe is therefore default-off and requires all of: the explicit R-09 property, an exact canonical expected gameDir match, a fixed marker created by the ignored profile builder, a world root below that gameDir, exclusion of the formal `D:\PCL2` root and production-loader mode. Failure logs a typed code and requests shutdown; success also requests immediate normal shutdown.

R-09B's probe reports only runtime versions, loaded mod-container count and the path/world safety result. It does not read recipes, plan, create an execution session, call an executor or mutate the world. R-09C may add RecipeManager reads behind the same gate, but cannot weaken it or turn static KubeJS inspection into runtime truth.

## ADR-021: Discover sequenced assembly without flattening it

DeceasedCraft's live manager exposes ordered sequenced assemblies with transitional items and loop counts. The existing deterministic `CatalogRecipe` is a single-process description and cannot retain those semantics. R-09C therefore records each parent/step ID, ordered inputs/outputs, loop count and transitional item through the Create 6.0.6 versioned internal boundary, but returns `RECIPE_TYPE_UNSUPPORTED` for planning.

This evidence does not enter `RecipeCatalog`, `ProcessDependencyGraph`, `LogicalMachineGraph` or `VerifiedLogicalPlan`. R-09 does not change generic-foundation-v1, flatten sequence steps into pressing or discard probabilities/order. Any future support needs a separate architecture stage after R-09.

## ADR-022: Bind verified logical requirements to real implementations before layout

Implementation binding is a separate loader-neutral, deterministic and read-only stage: `VerifiedLogicalPlan -> MachineImplementationCatalog -> candidate filtering/scoring -> ImplementationBoundMachineGraph -> BindingVerifier -> VerifiedImplementationBoundPlan`. It adds implementation facts but no spatial or execution authority. Layout/physicalization remains a later separately approved stage and only that later stage may resolve physical ports, geometry and the unchanged `UnifiedMachineGraph`.

1. **Catalog responsibility.** `MachineCapabilityCatalog` answers what a process requires and what an Adapter says it can support: recipe types, typed resource boundaries, resource/power needs, actions, evidence and diagnostics. `MachineImplementationCatalog` answers which concrete, stable implementation can supply that capability in one attributed runtime. It owns `MachineImplementationDescriptor` values and deterministic indexes/filters by capability, recipe type, Adapter, mod/version/fingerprint, verification evidence and execution-support level. Recipe presence, capability presence, descriptor publication, physical execution proof and current bind permission remain five distinct claims. Reload or runtime-fingerprint replacement produces a new catalog snapshot; a descriptor is never reused against a mismatched runtime.

2. **Descriptor responsibility and identity.** A descriptor uses a real stable `ResourceId` such as `create:mechanical_millstone` or `create:mechanical_press`, never a Java class, block-entity instance, world object, object address, random UUID or test placeholder. It records implementation family, supported capabilities/recipe types, typed inputs/outputs, logical implementation ports, power contract, processing mode, evidence/diagnostic support, physical-execution support and proven recipe evidence, Minecraft/mod/Adapter version attribution, runtime fingerprint, provenance, limitations and explicit deterministic selection inputs. It is descriptive data and cannot invoke a handler.

3. **Logical graph versus bound graph.** `LogicalMachineGraph` remains unchanged and implementation-free. `ImplementationBoundMachineGraph` retains its graph/node/port/edge identities and exact raw/owned/intermediate/target flows, plus the verified candidate's recipe, resolved Ingredient provenance and quantity conversions. Each PROCESS node receives exactly one real implementation binding with its capability, Adapter, runtime fingerprint, logical-port mapping, power contract, selection score/reasons, limitations and warnings. Resource-boundary nodes and logical edges remain boundaries/flows rather than fabricated installed machines. The bound graph cannot be directly executed or materialized as a `UnifiedMachineGraph`.

4. **Logical implementation ports versus physical ports.** Binding-layer ports describe only stable functional identity and contract: role, generic resource type, input/output mode, bounded quantity or throughput semantics, required/multiplexable/continuous-or-pulsed behavior, compatibility and evidence capability. ITEM input/output, FLUID input/output, ROTATIONAL_POWER input, redstone control, signal input/output and explicitly unsupported special ports are representable. A physical port is layout output and may add local side, world-relative direction, adjacency, exact position, capacity and connection geometry. Orientation-dependent machines carry a limitation requiring layout resolution; binding never invents NORTH/SOUTH/EAST/WEST, a side, an adjacent block or a coordinate.

5. **Power and spatial boundary.** Binding may require and verify a typed logical power contract, including minimum amount, continuous versus pulsed demand and compatible resource type. It may not select a power-source position, shaft/gearing/belt route, connection length, throughput topology, machine facing, footprint, collision volume, placement order or world anchor. Those facts, plus physical port resolution and routing constraints, belong only to Layout and Physicalization Foundation.

6. **Deterministic selection.** Candidate generation filters complete descriptors against the logical node, selected recipe, runtime/Adapter/mod availability, allowed/forbidden implementation constraints, typed input/output and power compatibility, required evidence and current execution-support policy. Explicit lower-is-better scoring prefers physically accepted/current-Adapter/evidence-complete implementations and fewer special ports. All candidate/index inputs are canonically ordered; ties use stable implementation ID. Equal verified plan, implementation snapshot, runtime fingerprint and binding constraints must produce equal candidates, scores, trace and bound graph identity. The framework remains generic and is tested with a non-Create fake Adapter, while production publication initially contains only the C-03-backed millstone and C-04-backed press.

7. **Typed binding failure.** Expected absence or refusal returns a bounded `BindingFailure`, never null, false, an empty-success graph, a normal `RuntimeException` or silent fallback. It records a stable code, binding stage, logical-node/capability/recipe subjects, sorted candidate implementation IDs, Adapter, runtime fingerprint, failed constraint, nonempty trace, user-readable detail, whether user intervention is required and a safe next step. Catalog missing/not found, mismatches, unavailable Adapter/mod, invalid port/power, unverified/forbidden/ambiguous implementation, unbound/invalid graph, reload, wrong-thread and explicit layout/physical-port-not-ready boundaries remain distinguishable.

8. **Verifier and safe result.** Selection cannot construct `VerifiedImplementationBoundPlan`. `BindingVerifier` independently proves one binding per logical PROCESS node, current-catalog identity, capability/recipe/Adapter/mod/fingerprint compatibility, complete logical ports/power/evidence/support level, exact logical graph/candidate/quantity/provenance retention, acyclic semantics and deterministic identity. Only its package-private gate constructs the verified result. The verified result is still read-only and must not be accepted by `GenericExecutionSession`, `BoundedStepRunner`, a world executor or `UnifiedMachineGraph` materializer.

9. **Frozen physical contracts.** This stage is additive. It does not add implementation IDs to `LogicalMachineGraph`, optionalize or otherwise change `MachineNode`, `MachinePort`, `MachineEdge` or `UnifiedMachineGraph`, use `(0,0,0)` placeholders, copy the fixed C-03/C-04 topology as a generic binding, or change recovery/execution fingerprints. Existing physical graphs stay authoritative for their already accepted templates. Later materialization must supply complete layout/orientation/physical-port/routing data before calling those unchanged constructors.

10. **Runtime and thread boundary.** Core binding and verification consume immutable loader-neutral snapshots and may run offline. Exact Minecraft/Forge/Create reads remain in the versioned Adapter on the authoritative server thread and publish no retained runtime object. Catalog construction is bounded and reload-generation/fingerprint scoped; it is not rebuilt per tick and cannot cross worlds. A pure binding request needs no world coordinate context, while a game-facing command still checks server-thread/runtime availability before snapshot capture.

11. **Initial support and deferral.** The only initially bindable production implementations are Create 6.0.6 Mechanical Millstone for `create:milling` using accepted C-03 execution evidence and Mechanical Press for `create:pressing` using accepted C-04 evidence. Other Create recipe types, including sequenced assembly, remain explicitly unsupported or implementation-not-found. Completion of this foundation authorizes only the next Layout and Physicalization Foundation review; it does not authorize orientation, routing, construction, new Create types, Mekanism, cross-mod execution, natural language or LLM-directed actions.

## ADR-023: Physicalize only verified bindings through bounded observed geometry

Layout/physicalization is the separate typed transition `VerifiedImplementationBoundPlan -> exact geometry catalog + LayoutConstraints + PlacementSnapshot -> bounded deterministic candidates/routes -> unchanged UnifiedMachineGraph -> PhysicalizationVerifier -> VerifiedPhysicalPlan`. It adds physical authority but never execution authority.

1. Geometry is Adapter-owned immutable data keyed by the exact implementation ID and runtime fingerprint. Footprint must equal unique composable component positions; clearance, supported orientations, physical implementation-port rules, bounded power topology, capacity/stress demand and a human-auditable topology contract are explicit. Initial v606 rules are limited to the accepted millstone and press and remain consistent with C-03/C-04 without invoking or copying their fixed plan classes as the solver.
2. Constraints must provide a real non-placeholder anchor, finite ordered orientations, candidate/radius/route/search-operation bounds, capacities and a complete observed snapshot. Missing or unloaded cells fail closed. Candidate and neighbor iteration are canonical; no randomness, LLM, free text, unbounded loop or implicit empty world is permitted.
3. Binding logical ports retain identity. Layout rotates only descriptor-relative position/side, creates explicit external boundary endpoints and routes each ITEM logical edge. ROTATIONAL_POWER topology is separately descriptor-derived. Capacity, stress, component collision, cross-module clearance and route reachability are verified before graph construction.
4. Only complete placement/routing may call the unchanged strict `UnifiedMachineGraph` constructors. The independent verifier repeats thirteen identity/runtime/orientation/footprint/clearance/port/route/capacity/stress/graph/budget/authority checks; `VerifiedPhysicalPlan` has no public constructor.
5. Expected failures use the exact bounded fifteen-code `LayoutFailureCode` set. Validation never places a block. Standard acceptance observes loaded Create-only cells; the disposable R-09 exception may explicitly read at most 49 chunks because ModernFix unloads spawn chunks, but never touches formal `D:\PCL2` or a player save.
6. This decision does not authorize a session, construction, material reservation, handler call, new Create type, sequenced assembly, Mekanism, cross-mod work, natural language or LLM coordinates. Execution requires a later independent readiness gate and all existing journal/recovery/rollback safeguards.

## ADR-024: Execute only independently ready physical plans through existing generic sessions

`VerifiedPhysicalPlan` is necessary but not sufficient execution authority. The independent readiness verifier repeats sixteen current-world, resource, runtime, graph, journal and safety checks and is the only constructor of the non-public `ExecutionReadyPlan`. The exact twenty-code refusal set distinguishes stale layout, changed target, unloaded chunks, missing input/power/stress, blocked construction/route, duplicate session, unavailable journal, unsafe reload, processing/evidence/output failures, unsafe rollback, cancellation and forbidden formal worlds.

The Create 6.0.6 controller adapts each ready process node to the existing C-03/C-04 generic plan and handler rather than adding a second execution engine. Verified route interiors are constructed incrementally and journaled; real chest inventory supplies and receives resources; every process keeps live recipe, input-consumption, kinetic, processing and output evidence. At most one handler action runs per server tick. Cancellation and terminal calls are idempotent, and any live target/power/route/output mismatch is a typed failure.

Recovery remains conservative: an exact single-node BUILD checkpoint may reconcile and resume without replaying completed construction, while any resource-bearing or irreversible history returns `RELOAD_RECOVERY_UNSAFE`. This decision authorizes the standard Create millstone/press pilot and an isolated DeceasedCraft pilot only. It does not authorize formal saves, arbitrary topologies, new recipe types, cross-mod transport, survival collection, LLM-generated execution or Mekanism nuclear/radiation automation.

## ADR-025: Prove the end-to-end chain only in bounded repository-owned worlds

The controlled pilot reuses the completed typed chain and cannot introduce a command shortcut, fixed success template or second execution engine. Standard success must prove gravel x3 in three orientations and iron sheet x2. Pack success must prove one custom milling and one custom pressing target from the live DeceasedCraft RecipeManager, including quantity greater than one, KubeJS recipe identity, tag selection, runtime fingerprint, all verifier evidence, physical routes, journals, trace, cleanup and exact output readback.

An empty-server compatibility bridge may invoke the real Create 6.0.6 block-entity lifecycle once per bounded server tick only behind the R-09 production/profile/property/resource-buffer gate. It may request Create to recompute a water wheel from live fluid vectors and tick the actual millstone, motors, belts, press and funnel; it may not assign synthetic speed, consume/insert fabricated results, bypass a verifier or mark a plan complete. Normal GameTests remain independently tick-driven and must pass with the bridge disabled.

ITEM routing may open the incident source/target clearance needed to reach physical ports, but all implementation component cells remain blocked route interiors. A route endpoint may coincide only with its explicit port. Formal `D:\PCL2`, player saves and formal mods/config/KubeJS/datapacks remain read-only and cannot be a gameDir. Completion requires external fingerprint equality, zero new crash reports/residual processes, full standard regressions, a clean worktree and the unchanged `generic-foundation-v1` target.

## ADR-026: Classify world authority from canonical identity and explicit evidence

World safety cannot be inferred from a folder name, command flag or requested environment enum. The loader-neutral classifier consumes explicit world/game/save/server/runtime fingerprints and provenance plus an allow/forbid root policy. It resolves normalized real paths, follows symlinks, supports only independently verified alias mappings, and requires the world root to be a descendant of its gameDir. A forbidden-root match has priority over every claimed isolated intent; outside-policy or mismatched identities become `UNKNOWN_WORLD`.

The descriptor strips write and execution authority from `FORMAL_PLAYER_WORLD`, `UNKNOWN_WORLD` and `FORBIDDEN_WORLD` as a construction invariant. Only an explicitly allowed canonical root with consistent identities can retain isolated/development write authority, and later deployment policy, approval, backup and formal-write gates remain separately mandatory. PW-01 performs no save enumeration, world scan, session creation, Adapter handler call or mutation.

## ADR-027: Make deployment policy explicit and formal execution unrepresentable

Classification never implies permission. Every deployment must carry one immutable policy with finite environment/root/area/route/material/power/mutation and capability allowlists plus explicit dry-run, backup, approval, rollback, protection, claim, resource-source and existing-machine decisions. Empty or missing policy is not a permissive default. Formal-player, unknown and forbidden environments cannot appear in an execution-enabled policy; the formal default permits only preview modeling, requires dry-run/backup/human approval, has zero mutation budget and forbids automatic resources, unknown replacement and base reuse.

## ADR-028: Recheck formal-world authority at every actual write boundary

A successful planner/readiness/session decision is not a durable filesystem capability. Command, session, executor, handler, recovery, replay and rollback paths use the same typed guard contract, while exact Forge writers independently re-resolve the current gameDir, forbidden root, world root and isolated marker immediately before handler, route, inventory or rollback mutation. Missing or changed proof returns `FORMAL_WORLD_EXECUTION_FORBIDDEN`; it never degrades to a warning. This additive defense does not modify `VerifiedPhysicalPlan`, `UnifiedMachineGraph` or `GenericExecutionSession`.

## ADR-029: Make preview a canonical projection, never an execution precursor

PW-04 previewing is a deterministic projection of the complete verified goal/logical/binding/physical/graph chain plus explicitly bounded read-only observations. It is not a partial execution session and cannot reserve materials, load a chunk, invoke a handler or construct readiness authority. Canonical JSON and SHA-256 bind all semantic content, including separate runtime and world-snapshot fingerprints, Adapter-declared route/power materials, risks, approvals and policy violations; the hash excludes only itself and is independently recomputable.

Core must not infer version-specific infrastructure materials from a generic route. The versioned Adapter supplies those immutable declarations, and their absence is a visible violation. Likewise, a formal-world preview may explain why a plan is blocked but cannot remove formal classification, zero mutation authority or rollback violations. Risk strings in PW-04 are preliminary audit entries only; PW-05 owns typed deterministic severity and the CRITICAL approval gate.

## ADR-030: Assign deployment risk severity only through fixed typed rules

The risk assessor owns a closed 22-category enum and five ordered severities. Inputs are immutable observations and quantities, never model-generated scores. One canonical finding may exist per category, and output order follows the enum rather than discovery/map order. Player-building overlap, BlockEntities/containers, fire/lava, explosion, disallowed dimensions, stress overload, resource shortage, non-recoverable rollback, non-ALLOWED permission, missing backup, stale snapshot and runtime drift are CRITICAL. The finding constructor enforces `blocksApproval == (severity == CRITICAL)` and the assessment recomputes its highest severity and blocking summary.

PW-05 does not prove that resources, claims, backup, snapshot or runtime facts are authentic/current. Their later owners must produce typed verified evidence. Risk assessment is read-only and cannot create approval, readiness or execution authority.

## ADR-031: Treat a deployment budget as accounting, never resource authority

PW-06 calculates requirements and limits from an immutable preview, policy and bounded read-only observations. Raw and intermediate inputs remain distinct, while input inventory is their deterministic sum; construction BOM is kept separate from its explicit power and logistics subsets. Power margin, journal storage and policy-limit violations use exact bounded arithmetic. The result records requirements and typed failures only: it cannot reserve, withdraw, insert, scan an inventory/network, load a chunk or create approval/readiness authority.

Formal-world budget modeling accepts only `AUTO_WITHDRAW_FORBIDDEN`, `PLAYER_PROVIDED_READ_ONLY_SNAPSHOT` or `EXISTING_NETWORK_READ_ONLY` with read-only evidence. This is not permission to read a formal save or storage network; an Adapter must later supply separately authorized current evidence, and PW-11 must verify it. `DESIGNATED_CONTAINER` is not automatically safe in a formal world because designation alone neither proves scope nor prevents withdrawal.

## ADR-032: Model region authorization as exact evidence, never a cached capability

PW-07 binds region evidence to exact environment, world, dimension, inclusive bounds, optional owner, authorizer, operations, mutation ceiling, expiry, preview/snapshot/runtime identities, one-time/use state, approval, revocation and provenance. The owner is never inferred; absent remains absent, and an exact owner mismatch fails. The pure service produces only canonical typed failures and cannot consume a token, write a block, access a container, construct a guard permit or bypass PW-11.

Formal, unknown and forbidden records are structurally limited to `READ_ONLY_SCAN`/`DRY_RUN`, zero mutations and non-APPROVED state. A formal scope check additionally always fails with `FORMAL_WORLD_EXECUTION_FORBIDDEN`, so modeling a read-only operation cannot silently authorize it. Any future actual formal read must begin from a separate explicit user instruction and a later architecture stage; PW-07 performs no such read.

## ADR-033: Consume exact structured approvals once; never auto-approve

PW-08 stores approval as an externally supplied token with an explicit decision and authorizer, not a boolean. The token binds preview, environment/world, snapshot/runtime/reload, exact region, target/quantity, mutation budget, the complete immutable deployment policy, expiry and provenance. The gate exposes only atomic verify-and-consume; it cannot issue a token, change PENDING/REJECTED to APPROVED or construct readiness. Policy equality intentionally invalidates approval when material, block or any other policy limit changes.

One token hash may win exactly once per gate, including under concurrency. Reload generation is an independent exact field so a new runtime generation cannot reuse the old approval merely because in-memory gate state restarted; PW-11 must compare it to authoritative current runtime evidence. Test approval uses only explicit `TEST_ONLY` identity and is structurally isolated-only. No TEST_ONLY or production approval can authorize formal execution under the independent environment/write guards.

## ADR-034: Publish backups only from verified isolated staging

PW-09 permits real filesystem backup/restore only when a canonical path policy proves both source and backup root belong to an isolated root and no path enters a forbidden root. Manifest construction rejects links and binds sorted relative path, size and SHA-256 for every file; its aggregate hash, capacity/headroom, consistency, atomicity, restore, retention, failure, approval and journal identities remain separate verifier checks. A non-isolated environment returns before any path is resolved.

Creation rechecks live source fingerprint and free space, copies to an empty verified staging sibling, rehashes it and requires same-filesystem atomic rename. Restore rehashes the published backup before touching source and verifies the complete source fingerprint afterward. Failure keeps the source and deletes only a staging path that still proves inside policy. This authorizes only repository/test-owned disposable fixtures. Formal backup is intentionally absent and cannot be inferred from the existence of a plan or manifest.

## ADR-035: Normalize claim evidence without hardcoding a claim mod

PW-10 owns only loader-neutral permission query/evidence and an open Adapter interface. Query identity includes operation, actor, region, world/environment/dimension, source, timestamp, generation and fingerprint; evidence must preserve it and attribute decision, verification, Adapter and provenance. UNKNOWN, DENIED and NOT_INSTALLED remain distinct diagnostics but all block construction. Even VERIFIED ALLOWED cannot permit formal/unknown/forbidden construction.

Generic adapters prove isolated allow, explicit deny, unknown and absent behavior, while a test-only third-party class proves the interface is not sealed to known IDs. Disposable evidence shows Open Parties and Claims 0.25.8 is present and initialized, but presence is not an API contract or permission decision. A separately reviewed versioned PW-10A Adapter is required before any integration; no reflection, direct class import or unchecked formal claim query is permitted now.

## ADR-036: Aggregate deployment evidence once without creating execution authority

PW-11 uses one independent twenty-five-check verifier to join the current physical plan and graph with preview/dry-run, freshness, environment/policy/write-guard, risk/budget, region/approval, backup/restore, claim-permission, rollback/journal and authoritative-thread evidence. Every check has a stable identity and typed refusal; failures retain the complete bounded environment, target, preview, region, policy, risk, approval, backup, runtime and trace context needed for a safe next action.

Only all-check success in an isolated environment may construct `DeploymentReadyPlan`. The result is evidence-only data: it cannot construct or expose an execution-ready value, session, handler, resource handle or world mutation. Approval consumption occurs only after all other evidence passes and remains one-time. Formal, unknown and forbidden environments cannot pass regardless of supplied tokens, policy, permission or backup evidence.

## ADR-037: Deployment commands terminate at a read-only physical dry-run

PW-12 accepts only a typed `ResourceId`, positive bounded quantity and an optional typed quarter-turn. The authoritative server thread resolves the live recipe/capability/implementation catalogs, verifies the logical, bound and physical plans, captures bounded already-loaded block observations, and then terminates at deterministic preview, risk and budget reporting. The command surface exposes preview, risks, budget and readiness views; it does not construct `ExecutionReadyPlan`, `DeploymentReadyPlan`, a session, resource buffer, reservation, handler invocation or world write.

A separate marker and expected-game-directory property identify repository-owned or disposable dry-run fixtures without reusing the execution-pilot gate. Every report structurally fixes dry-run true and formal execution, world mutation, session creation, item consumption, machine start, LLM calls and free-text coordinates false. Formal, unknown or forbidden classification can be reported only as non-executable evidence. No approval command is provided; therefore `TEST_ONLY` approval cannot be issued through PW-12 or copied into formal scope.

## ADR-038: Accept bounded formal survey evidence without converting partial coverage into authority

FS-12 publishes the full pre-fingerprint before any region payload read, scans only a single-threaded budgeted hot sample, and requires exact post-fingerprint equality before returning an accepted run. A malformed region header or bounded classifier refusal is retained as typed partial evidence and never repaired. Create natural geology cannot enter infrastructure/candidate evidence; only observed machine, power, transmission, processing, logistics, fluid or moving components may seed a candidate.

Candidate generation is review ordering, not site selection. Real candidates structurally remain `PENDING_USER_SELECTION`; UNKNOWN claim permission, unverified clear space and player-building boundaries block every formal dry-run. The accepted stage may document four unavailable dry-runs, but cannot manufacture preview hashes from FS-10 fixtures. Report writing exists only in a development acceptance harness below the ignored repository survey root; the production runner owns no output path, process, session, private-content or execution API.

## ADR-039: Plan formal backup destinations without relaxing isolated backup authority

The existing backup service remains structurally isolated-only and cannot be pointed at a formal source. FB-01 introduces a separate destination policy that may identify a formal source but performs no source open or copy. It accepts no caller-selected target: the target and staging sibling derive from exact trusted identities below the repository ignored backup root, outside the complete formal platform, after canonical/reparse/collision/capacity checks.

A destination plan is not a `BackupIdentity`, completion state or copy permit. It fixes overwrite, cloud upload, creation and formal-write authority false. Every later FB-02 boundary must recheck the canonical roots, available space, target absence and source fingerprint; a passing FB-01 result cannot bypass those current checks or the formal read-only guard.

## ADR-040: Transport private save files opaquely while preserving a complete backup

A restorable formal-world backup must include playerdata, stats and advancements, but Stage B does not authorize semantic access to their contents. FB-02 therefore introduces a narrow package-private transport guard: it opens the formal source with READ only, streams bytes directly into a SHA-256 digest and a verified external staging file, and returns metadata/hash evidence rather than bytes, NBT or JSON. No private content is parsed, summarized or written to reports. The existing survey guard still refuses private content reads, and all formal source attributes remain untouched.

`session.lock` and fixed invalid temporary suffixes are excluded and recorded; every other ordinary file is mandatory. A complete manifest binds path, size, mtime, hash, privacy mode and exclusions. Copy authority requires current empty process/handle evidence and a fresh survey fingerprint matching the destination plan. Publication requires exact staging verification, a same-filesystem atomic directory move, an exact published-tree verification, a backup-only marker and equal source fingerprints before and after completion. Any drift produces typed failure; only the exact disposable staging tree or marker may be removed, never the source or an unproven path.

## ADR-041: Reconstruct backup identity from persisted evidence before disposable restore

An in-memory copy result is insufficient recovery evidence. FB-03 persists a bounded canonical manifest in staging so the manifest and all world files share one atomic publication boundary. The completion marker is written only in the published backup and binds the plan, exact WorldIdentity, source/runtime fingerprints, manifest/count/bytes, completion time, policy/tool versions and Git HEAD into a self-validating `formal-backup:*` identity. A backup is valid only when both control files parse canonically and a new full-tree verification matches every file.

Restore never targets the formal source. It accepts only an identical fresh verification and computes an immutable disposable target below the exact repository `work/formal-restore-drills` root. The restored save is rehashed before and after atomic publication, and only its `level.dat` is parsed through the bounded offline survey reader to rederive the exact WorldIdentity. Player-linked files remain opaque copies. A passing restore result and retention policy are audit evidence only; they explicitly grant no deployment approval or formal execution authority.

## ADR-042: Represent missing formal previews as blockers, not invented candidate evidence

FB-04 supports both a complete synthetic/fixture preview chain and the real FS-12 condition where every dry-run is blocked. Complete packages bind the exact preview, physical plan, risk and budget. Blocked packages carry none of those artifacts and instead require explicit preview/physical-unavailable blockers. A hash, plan identity, risk or budget may never be copied from FS-10 fixture evidence into a real-world candidate.

Candidate batches retain deterministic score ordering solely for human review. Every package remains `PENDING_USER_SELECTION`, claim permission remains UNKNOWN, and `USER_SELECTION`, claim, region, human approval and formal-execution blockers stay present. The batch cannot hold a selected ID, auto-select top-1, create an approval decision or enable execution. A later FB-05 request needs separate user-selection evidence.

## ADR-043: Keep selection evidence, approval request and decision as separate states

FB-05 does not convert a candidate package into approval. Without explicit selection it creates only an awaiting-selection request with no scope. After a future exact candidate ID is supplied, a request scope may be constructed only from an available non-critical package and binds every world/preview/backup/snapshot/runtime/target/quantity/mutation/material/power/operation/expiry field once. Request creation still produces an ABSENT decision and zero execution authority.

No formal-allowed decision exists in the Stage-B vocabulary. TEST_ONLY is structurally restricted to a disposable restore copy, its authorizer is literally `TEST_ONLY`, and it never carries formal-execution authority. A separate unconditional hard stop does not inspect perceived readiness to allow execution; formal source input always returns `FORMAL_WORLD_EXECUTION_FORBIDDEN` and cannot bypass `FormalWorldWriteGuard`.

## ADR-044: Commands route registered identities to audit plans, not filesystem paths

FB-06 implements exactly seven B-10 forms and no generic subcommand or arbitrary argument forwarding. World, backup and candidate operands must already exist in bounded registries. Successful parsing yields a deterministic relative audit key and typed identity references only; it does not resolve a caller path, invoke backup/restore, create a session, approve, inspect inventory or write the formal world. In particular, no `formal deployment approve` command exists.

## ADR-045: Accept one immutable external backup without widening formal-world authority

FB-07 accepts a real backup only when fresh quiescence, capacity, canonical-root, collision and source-identity gates pass; a unique staging copy rehashes exactly; publication is atomic; the canonical persisted manifest and marker independently reconstruct the same backup identity; and the formal source fingerprint is exactly equal before and after. The accepted backup and disposable restore drill remain ignored repository-work artifacts outside the formal platform.

This acceptance authorizes neither restore-to-source nor deployment. Private files remain opaque transport data, the root lock is explicitly excluded, and the public report carries aggregates only. Candidate packages remain pending, approval requests have no scope, formal decisions are absent and the unconditional execution hard stop is unchanged.

Resource inventory remains a future request artifact. It binds only listed positions inside one candidate, generic resource categories, purpose, minimization, expiry and a no-write guarantee, while executed/content-read are fixed false. Claim permission UNKNOWN stays non-ready even when an identified claim mod creates an Adapter backlog; no database parse, claim mutation or permission write is representable.

## ADR-046: Separate writable-world identity and region confirmation from execution authority

IWP uses exact repository-owned instance/world markers only as inputs to canonical
identity discovery; marker names or a friendly save name are never sufficient.
The candidate must also be unique, disposable, beneath the exact isolated
`saves` root, outside formal/backup roots and unequal to the recorded formal
WorldIdentity. The guard rechecks these facts and the current fingerprint at
every boundary, with an unconditional formal hard stop taking priority.

Region selection and confirmation remain evidence-only. Confirmation binds the
exact world, dimension, bounds, deterministic region hash, player, session,
expiry, policy and preview fingerprint but cannot construct a write permit,
ready plan, session or handler. Preview is required, uses particles rather than
blocks and scans across ticks to keep bounded work on the server thread. IWP-04+
must join this evidence to the existing PW-03/PW-11 path; it may not bypass or
replace it.

## ADR-047: Keep the player pilot instance repository-owned and account-free

The dedicated client game directory lives only under ignored
`work/isolated-player`. Client mod JARs and presentation assets may be copied
opaquely from the formal pack while sanitized configuration/data comes from the
existing repository-owned R-09 mirror. Formal saves, account/session caches,
server lists, options, logs, screenshots, chat and inventory data are excluded.
Minecraft libraries, assets and natives may be referenced read-only from PCL2;
all writable saves/config/logs remain independent. Launch uses a fixed offline
test identity and never reads an account or token.

## ADR-048: Reuse the verified execution chain for the visible player pilot

The player pilot does not gain a second executor or a command shortcut. Its
readiness command must assemble the current exact region, accepted backup,
policy, authorization, risks, budgets, resources, power and runtime evidence
through all twenty-five deployment checks and all sixteen execution checks.
Dry-run cannot create a session or consume a resource. Start is separately
acknowledged, limited to gravel x3 or iron sheet x2, and delegates every visible
BUILD/CONNECT/FEED/PROCESS/VERIFY action to `CreateV606GoalDrivenExecution`.
TEST_ONLY materials live in one dedicated world chest; player inventory is out
of scope and formal execution remains structurally false.

## ADR-049: Cleanup follows journal ownership and narrowly models Create belts

Cleanup may remove only a journal-recorded position inside the exact authorized
region or the one dedicated resource buffer. Normal blocks and block entities
must still equal the recorded after-state. For `create:belt`, exact block,
facing, slope and every property except `part` must match because Create changes
that single property while connecting a belt. If clearing one owned segment
causes another planned owned segment to become air, that position is counted as
already clean rather than treated as a failed mutation. No other cascading,
property drift or unknown removal is accepted.

The water-wheel plan likewise owns containment. Its typed 17-placement topology
forms a bounded three-cell falling-water column, and physical acceptance scans a
local 125-cell volume for zero escaped water. These narrow runtime allowances do
not authorize general repair, fluid cleanup or topology inference.

## ADR-050: Model opposed crushing wheels honestly and execute only live proof

C-05 uses one immutable physical plan for Direct, Bots and Hybrid. The plan owns
two independent drive/wheel pairs plus hopper/chest logistics while Create owns
the runtime controller in the one-block gap. The generic geometry declares only
the continuous left drive-to-wheel rotational route; it does not fabricate a
kinetic connection through the controller gap. The exact v606 handler must
independently observe both drives/wheels, equal-magnitude opposed rotation,
controller validity/direction and stress before feed.

Crushing completion requires the exact live recipe, a real input entity,
observed input disappearance and actual allowed output in the planned chest.
Guaranteed output and optional byproducts remain distinct. A foreign chest or
controller state fails before feed. BUILD prefixes may recover only after exact
plan/journal/world reconciliation; the final wheel/controller transition is
atomic and resource-bearing history never automatically resumes. This accepted
disposable-world capability does not widen formal-world authority or authorize
arbitrary crushing, mining, container access, repair or LLM coordinates.

## ADR-051: Keep C-09 Basin compacting separate and initially unheated

C-09 owns a Basin plus mechanical-press topology and exact
`create:compacting` recipe contract. It may accept multiple counted ITEM inputs
and one deterministic ITEM output, but it may not reuse the C-04 belt-press
implementation, collapse ingredients to one stack, call a recipe-processing
method or synthesize the declared result. Completion must come from the live
Basin inventory and mechanical-press behavior.

Phase I accepts only Create's `HeatCondition.NONE`. Heated/superheated and
fluid/residue/NBT-sensitive shapes remain typed unsupported until their own
safe physical topology and Bot-exclusion policy are tested. This is a
fail-closed capability boundary, not a claim that heated recipes are
implemented. Three-mode multi-stack logistics and recovery/compensation stay in
the common C-06-through-C-10 acceptance work.

## ADR-053: Heat C-08 only through a real ordinary-fuel Blaze Burner

C-08 models NONE and HEATED explicitly. Its fixed topology includes a Blaze
Burner even for NONE so geometry remains stable and the handler can prove exact
absence of heat. HEATED consumes one exact ordinary coal stack through Create's
Burner fuel-insertion logic and accepts only live `KINDLED`.
SUPERHEATED/SEETHING is a typed refusal; direct block-state/NBT heat mutation is
not an implementation.

Live Ingredient testing authorizes selected counted ITEM inputs, including
tag/any-of matches, but not arbitrary substitutes. Fluid, residue, unknown NBT,
multiple or probabilistic output are rejected. Generic goal materialization
uses verified runtime heat metadata and never guesses heat from a recipe name.

## ADR-054: Carry heat beside the frozen graph and reserve fuel by root session

The logical plan, bound graph, physical plan and ready-plan contracts remain
unchanged. Runtime recipe heat is carried in an additive v606 execution
metadata sidecar, then rebound to the same runtime fingerprint, root session,
step, recipe, capability, implementation and physical Blaze Burner before
materialization. A missing, stale, unsupported or incomplete sidecar fails
closed.

One HEATED execution reserves exactly one ordinary coal from the plan-owned
world resource buffer. Reservations are server-thread-only, exclusive by exact
source/session/step/resource/count, bounded by expiry and never reach player
inventory or arbitrary containers. Cancel releases unused fuel; a safe
pre-resource reload releases and must reverify/reacquire; consumed fuel is not
invented or compensated. Direct, Bots and Hybrid share this same reservation
and physical Burner path.
## ADR-055: Persist player progress but never reconstruct execution authority from it

The Phase IV-C player project stores goal, quantity, world/dimension, stage,
anchor, orientation, layout, execution mode and plan/snapshot hashes. This is
enough to reconstruct a read-only UI and request a fresh server preview. It is
not a demolition token, region capability, fleet snapshot or construction
session. If the server process loses the in-memory Site Preparation authority,
the project becomes paused and requires reapproval/replanning. This avoids both
false HUD resume claims and duplicate world work.

## ADR-056: Stop the player workflow at the unimplemented material-accounting boundary

The clean site may feed only the existing verified construction planners and
Direct/Bots/Hybrid executors. Their current player-command wrapper still uses
isolated-test material fixtures for machine construction and process input.
Starting it automatically from the new terminal would misrepresent material
ownership and cancellation refunds. Phase IV-C therefore reports
`CONSTRUCTION_MATERIAL_SOURCE_REQUIRED` until a dedicated player source and
formal MaterialLedger integration can prove exact debit, transport, return and
recovery. No fourth UI executor is permitted.

## ADR-057: Freeze the Phase IV player and item-material protocol

Phase V adds separate versioned warehouse, electrical, fluid and order
contracts. It does not rename Phase IV stages, transaction states, executor
identities or SavedData schemas. C-01/C-02 remain read-only discovery; C-03
through C-10 remain the reviewed material-bearing Create scope.

## ADR-058: Derive the complete BOM from the verified physical plan

Player requirements are not target recipe inputs alone. The server replans,
snapshots every physical component and assigns purpose plus disposition to
machine blocks, process inputs, retained tools, power/logistics components and
fuel. Registered item-form installation material stays in escrow until real
output succeeds and returns exactly on cancellation. Unknown, creative-only or
semantic non-item roles fail closed instead of becoming free blocks.

## ADR-059: Treat central storage as an authorized logical graph

The first warehouse is a projection over explicitly selected containers and
existing project reservations. Atomic allocation subtracts other projects at
the physical endpoint/resource level. No block is introduced and no nearby or
private storage is scanned. SavedData order intent is now durable, while each
physical warehouse runtime must re-register before dispatch.

## ADR-060: Separate adapter discovery capability from execution authority

An industrial adapter publishes a capability profile containing both
read-only discovery and physical-execution flags. IE v1020 may read recipes,
multiblock templates and wire topology and may feed those facts into the pure
planner, but its action method refuses until every mutation has exact tool,
material, energy, ownership, rollback/recovery and output evidence.

## ADR-061: Keep FE and fluids as typed, separately balanced resources

FE topology is a directional voltage/capacity/load graph, not an item count.
Fluid movement is an exact-millibucket journal with its own return states and
balance. Both can contribute requirements to an industrial plan, but neither
is converted into an item transaction or silently borrowed from an unrelated
network.

## ADR-062: Separate disposable IE physical proof from production authority

The IE physical fixture may preload exactly the recipe FE only inside its
isolated run and must restore the site. It proves IE formation, mold, input,
runtime energy consumption and output behavior, but cannot set the adapter's
production-execution flag. Player execution still requires a live authorized
wire source, complete material transactions, Bot tasks and restart evidence.

## ADR-063: Persist orders, re-register warehouse runtimes

Maintain-stock intent and its complete recipe/adapter/site allowlist survive in
SavedData. Concrete warehouse runtimes do not: they re-register after each
restart before the tick service may observe stock or dispatch. Missing runtime
means no action, never a guessed topology or fallback container.

## ADR-064: Move fluids only between explicit capabilities

The first Forge fluid mutation is a simulate-first transaction between two
authorized endpoints. It journals withdrawal and delivery separately and
attempts exact reclaim/return on delivery drift. This does not imply pipe or
pump discovery, and it never scans nearby storage to find a substitute.
