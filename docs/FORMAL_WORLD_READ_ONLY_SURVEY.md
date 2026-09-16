# Formal player-world read-only survey

## Authority and current status

The user authorized a bounded offline survey of the formal DeceasedCraft player world under `D:\PCL2`. This authority permits save discovery, necessary `level.dat` and region/chunk reads, infrastructure presence/topology approximation, candidate-zone scoring and dry-run previews. It does not permit starting Minecraft, writing the formal instance, reading player or container contents, taking resources, creating an execution session, choosing a deployment zone or approving/executing deployment.

FS-01 through FS-11 are complete. Guarded real save discovery found exactly one readable 1.20.1 world and therefore did not trigger the ambiguity stop. Only the direct `saves` entry and `level.dat` were observed; no formal region or private data has been enumerated or opened.

## Read boundary

`FormalWorldReadOnlyGuard` requires an exact ordinary formal-instance root, approved save or direct `saves` root and repository-owned ignored audit root. It alone owns bounded non-recursive direct save enumeration, exact metadata lookup and content opens. `FormalSaveDiscovery` contains no filesystem API. The guard rejects symbolic links, junctions and reparse-like ancestors; paths outside the approved boundary; player-linked private trees; every non-READ filesystem intent; and Java/Forge/Minecraft process starts targeting the formal boundary. The only successful content API is a callback-scoped `FileChannel` opened with `StandardOpenOption.READ`.

Each enumeration, metadata observation and content open appends JSONL audit evidence outside the formal instance. If the audit destination cannot be proven ordinary and external before creation, the read is refused.

Before live discovery, the guarded enumeration hardening passed 334 JVM/Adapter tests and a 23-task build: `work/logs/core-test-20260720-091520.log`, `work/logs/build-20260720-091700.log`. A nested region fixture was absent from discovery audit, proving the pre-selection path does not recurse.

## Fingerprint gate

Pre/post evidence includes the exact sorted relative-path manifest, file count, total bytes, every file's mtime, the manifest SHA-256 and SHA-256 for an explicit bounded key-file set. Any difference is an external-mutation stop condition. `session.lock` is never created; if present, its metadata can be included as fingerprint evidence without locking it.

Current accepted evidence is `work/logs/core-test-20260719-124837.log` and `work/logs/build-20260719-125225.log`: 318 tests, 69 suites, zero failures/errors/skips, followed by a clean 23-task build and artifact-isolation pass. This is fixture evidence only and makes no claim about the live formal world.

## Loader-neutral report boundary

FS-03 report values preserve exact world/dimension/region/chunk/position, real resource IDs, finding categories, one of five confidence levels, relative evidence source, file fingerprint, parse generation and explicit limitations. No Minecraft/Forge/NBT/RegionFile/BlockEntity type crosses into core. Storage findings cannot contain item contents; power and logistics findings cannot claim observed live RPM, stress or connectivity. Candidate zones are bounded, deterministically ranked and always `PENDING_USER_SELECTION`.

FS-03 evidence is `work/logs/core-test-20260719-130026.log` and `work/logs/build-20260719-130230.log`: 322 tests and a clean 23-task build. It is still fixture/model evidence, not a completed formal survey.

## Bounded offline parser boundary

FS-04 parses only through guard-owned READ channels. It supports bounded gzip `level.dat` plus gzip/zlib/raw Anvil chunk payloads for the exact verified Minecraft 1.20.1 DataVersion 3465. Region files are streamed one header and one bounded chunk at a time; sector offsets, overlaps, lengths, decompressed bytes, recursion depth, arrays, lists, compound entries and coordinates are all capped and validated. Palette storage uses Minecraft's non-crossing values-per-long rule for every 4–12 bit width and refuses out-of-palette indices. A failed chunk remains typed partial evidence and does not suppress valid siblings.

The internal NBT tree is package-private. Public values contain world identity, dimensions, palette/block states, sections and BlockEntity type/location only. Player data, entities, UUIDs, inventories, item stacks and container contents are skipped during parse and cannot enter the formal report model. No parser path repairs data, obtains a lock, starts Java/Minecraft/Forge, or infers live power/connectivity.

FS-04 final evidence is `work/logs/core-test-20260720-092449.log` and `work/logs/build-20260720-092548.log`: 335 JVM/Adapter tests and a clean 23-task build, with all 338 build-included tests green. It remains synthetic fixture evidence; the packing correction completed before any formal region read.

## Deterministic survey budget boundary

FS-05 makes the maximum region count, chunk count, source bytes, monotonic duration, retained-memory estimate, candidate-zone count and candidate deep-scan radius explicit. Parallelism is structurally fixed to one. Reservations are atomic: an exceeded dimension does not change any counter, freezes subsequent scheduling and returns both `SURVEY_BUDGET_EXHAUSTED` and `SURVEY_PARTIAL` with exact usage and safe next steps.

Metadata is ordered by a stable activity/heat index before a bounded hot sample. Neighbor expansion is finite and distance ordered. Deep-scan work can originate only from an explicit candidate seed, and a region coordinate is scheduled at most once. Candidate input and region metadata are independently hard-bounded before ordering. None of these types can open a file, parse NBT, start a process, create a session or approve a zone.

FS-05 evidence is `work/logs/core-test-20260720-090130.log` and `work/logs/build-20260720-090333.log`: 333 JVM/Adapter tests and a clean 23-task build, with all 336 build-included tests green. It remains synthetic scheduling evidence. Guarded real save discovery followed only after the additional enumeration and BitStorage hardening passed.

## Unique guarded world identity

The exact formal instance contains one direct readable save: `新的世界`, identity `world:ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3`, DataVersion 3465, Minecraft 1.20.1 and six dimensions. Discovery audit is `work/formal-survey/discovery-20260720-092142/formal-read-audit.jsonl`. It records no nested region, private path or lock observation and left no Java process. This is identity evidence only; region scanning still requires the pre-fingerprint and bounded orchestration gates.

## Offline classification boundary

FS-06 maps decoded palette positions into exact Create power/transmission/processing/ITEM/fluid/moving/generic infrastructure and presence-only storage findings. Known vanilla/Create storage is OBSERVED; other storage-like BlockEntity types are DERIVED_LOW_CONFIDENCE and runtime-confirmation-required. Every storage record fixes contents to unread and future authorization required. Power/logistics remain offline and cannot claim live RPM, stress or connectivity.

FS-06 evidence is `work/logs/core-test-20260720-093235.log` and `work/logs/build-20260720-093334.log`: 339 JVM/Adapter tests and a clean 23-task build, with all 342 build-included tests green. No formal region was used for this fixture acceptance.

## Offline topology boundary

FS-07 deterministically approximates ROTATIONAL_POWER, ITEM and FLUID edges only between same-dimension, unit-adjacent classified positions. Persisted compatible axis data raises like-to-like adjacency to DERIVED_HIGH_CONFIDENCE; processing, machine and storage attachments remain DERIVED_LOW_CONFIDENCE. Cross-region edges bind both persisted file sources, incomplete inputs require explicit unscanned-boundary limitations, and every edge fixes runtime connectivity to unknown. No topology value can read a live network, inspect contents, open a path, create a session or authorize execution.

FS-07 evidence is `work/logs/core-test-20260720-100242.log` and `work/logs/build-20260720-100340.log`: 344 JVM/Adapter tests and a clean 23-task build, with all 347 build-included tests green and artifact isolation verified. The milestone used synthetic findings only; no formal region was enumerated or read.

## Candidate-zone scoring boundary

FS-08 applies fixed code-owned weights to clear space, exploration, exact nearby-infrastructure distance, BlockEntity/player-risk counts, chunk boundaries, claim permission, dry-run feasibility and survey completeness. Infrastructure distance is derived from retained exact finding positions rather than accepted as an unattested score. Inputs and outputs are bounded and canonical, ties use stable SHA-256 zone identity, and every candidate remains `PENDING_USER_SELECTION` regardless of rank.

UNKNOWN or denied permission, incomplete coverage, failed dry-run feasibility and protected/unknown limitations lower confidence and add exact future-authorization requirements. A score cannot read a claim database, select a zone, create approval, inspect inventory contents, start a session or authorize execution.

FS-08 evidence is `work/logs/core-test-20260720-101700.log` and `work/logs/build-20260720-101758.log`: 349 JVM/Adapter tests and a clean 23-task build, with all 352 build-included tests green and artifact isolation verified. Fixtures only were scored; no formal candidate result exists yet and no formal region was read.

## Claim metadata boundary

FS-09 accepts only pre-sanitized, relative, bounded metadata from an exact mod-descriptor/config/serverconfig allowlist. Seven non-secret keys can describe mod identity/version/display name, relative config/data hints, an API hint and protection-enabled metadata. Unknown keys, credentials or credential-shaped values, absolute/escaping/remote paths, private database names and non-allowlisted sources fail closed.

Known claim metadata creates only a deterministic discovery record and versioned read-only Adapter backlog request. It never claims an Adapter exists or permission was verified; both detected and absent cases remain `PermissionDecision.UNKNOWN`. Result invariants fix credential/database/remote access, claim modification and formal execution to false.

FS-09 evidence is `work/logs/core-test-20260720-102659.log` and `work/logs/build-20260720-102802.log`: 354 JVM/Adapter tests and a clean 23-task build, with all 357 build-included tests green and artifact isolation verified. No live formal metadata, claim data or private database was read in this fixture milestone.

## Formal dry-run batch boundary

FS-10 requires exactly four already-verified formal dry-run reports: gravel x3 milling, iron sheet x2 pressing, and distinct pack-custom milling and pressing recipes. Each binds a pending surveyed zone, dimension and in-bounds anchor; all hashes are valid/unique and share one runtime and snapshot. Claim permission remains UNKNOWN and every report includes `FORMAL_WORLD_EXECUTION_FORBIDDEN`. Inventory, resources, sessions, approvals, mutation and execution remain false.

Evidence: `work/logs/core-test-20260720-104630.log` (358 tests) and `work/logs/build-20260720-104725.log` (361 tests, 23 tasks, artifact isolation). These are synthetic contract fixtures; real reports remain FS-12 work and no formal region was read.

## Registered command boundary

FS-11 supports only `list-worlds`, `inspect`, `infrastructure`, `candidates` and `preview` token forms. Non-list commands require a registered WorldIdentity; preview also requires a registered target and bounded quantity. Drives, slashes, traversal, unknown identities/targets and extra arguments fail typed. Output is a deterministic relative report key for later resolution below ignored `work/formal-survey`; the plan itself cannot read/write files or start a process/session.

Evidence: `work/logs/core-test-20260720-105255.log` (363 tests) and `work/logs/build-20260720-105407.log` (366 tests, 23 tasks, artifact isolation). No formal region was read.

Before FS-12 live use, fingerprint metadata access was moved fully behind the guard. Hash-all mode opens every non-private ordinary file through READ-only callbacks; playerdata/stats/advancements are never opened and retain metadata-only entries. Regression/build evidence: `work/logs/core-test-20260720-105957.log`, `work/logs/build-20260720-110101.log`.

## FS-12 accepted formal run

The final run used a 16-region/16,384-chunk/8-GiB-read/30-minute/8-GiB-retained/8-candidate/single-thread budget. It enumerated 59 region files, selected 16 and parsed 9,733 chunks. The 216-file, 214,122,809-byte pre/post fingerprints are exactly `00571b934b91477a3bbc58b30933c121ea09a4848ade76c89ea4ad636a834a34`; the separate non-save instance metadata fingerprint also remained equal. There were no formal writes, session-lock changes, private-content opens, process starts, crashes or residual Java processes.

The survey is deliberately `SURVEY_PARTIAL`: 29 undersized region files are retained as `REGION_HEADER_INVALID`, 40 dense chunks reached the per-chunk classification bound, and unscanned boundaries remain unknown. These are coverage/data-quality limitations, not evidence of a survey-caused mutation. Eight actual machine-seeded candidates were generated after excluding natural Create geology; all remain negative-scored `UNKNOWN`/`PENDING_USER_SELECTION`. Full ignored evidence is under `work/formal-survey/ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3/fs12-2026-07-20T03-50-59.119599200Z/` and the retained log is `work/logs/formal-world-survey-20260720-115048.log`.
