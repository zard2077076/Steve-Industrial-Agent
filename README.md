# Steve Industrial Agent

> **Experimental Alpha — only for new, disposable test worlds.**
> Do not use this build in an important save. This development branch goes beyond
> the original gravel/iron-sheet pilots; implemented capabilities and the exact
> evidence scope are recorded in `docs/PROJECT_STATE.md`.
>
> **Release status: PRE-RC automated portability passed; visual acceptance pending.**
> Production Forge config, world-local opt-in and portable offline backup are wired.
> Do not call the build RC_READY until the independent client is visually accepted.

Steve Industrial Agent is the new formal name of the project previously called Steve Create Agent. It is an isolated Minecraft 1.20.1 Forge mod and test platform for typed, verifiable AI planning across industrial mods. Create support is the first MVP track; Mekanism is an optional adapter track and must not break Create-only or no-industrial-mod startup.

## Phase IV development status

The development branch includes a bilingual Engineer Terminal, live derived-goal
search, site preview/recommendations, server-authorized clearing, player material
sources and transactional construction. The empty search uses a small reviewed
catalog; it is not an execution whitelist for all other products. Create/IE,
Composite and multiple Bots share verified planning/resource boundaries.

Selected salvage/material containers, bounded Bot work, persistent project state,
safe cancellation/return and real completion reports are implemented. C-03,
Metal Press and Composite/01 have automated Mac client evidence; the complete
interaction/fault/layout matrix and human UX remain unfinished. Development is not
a blanket release, cross-platform compatibility claim or authority for formal saves.

The normal survey/confirmation UI hides exact coordinates, region identities,
preview hashes and executor internals. Existing `/industrialagent` commands
remain the advanced engineering surface; the larger expandable advanced GUI is
still WIP.

C-05 Crushing has completed its automated development checkpoint on the
unreleased Phase IV branch. One verified opposed-wheel/hopper/chest plan
executes real gravel-to-sand processing through Direct, two active player-shaped
Bots and Hybrid, with typed failure injection and exact pre-resource recovery.
The disposable DeceasedCraft profile also identifies a safe runtime raw-copper
crushing recipe. This does not expand the published Alpha pilot targets or
authorize an important/formal world. See
`docs/C05_CRUSHING_EXECUTION.md`.

The project has a frozen, tested `generic-foundation-v1`, a completed deterministic goal-planning foundation and the completed R-01 through R-08 Create Runtime Knowledge Foundation. Current code provides loader-neutral industrial graphs, process/step/session models, bounded execution and verification, conservative journaling/recovery, typed placement feasibility, whole-plan quarter-turn transforms and an exact v606 server-thread catalog that enumerates/fingerprints the actual runtime `RecipeManager`. R-02 maps supported runtime milling, pressing and crushing recipes while preserving exact/alternative/tag input identity and returning per-recipe typed limitations instead of guessing. Runtime-attributed capabilities and implementation binding feed verified logical and physical plans. The production Create 6.0.6 development paths physically verify three bounded templates: water-wheel millstone processing, belt-fed mechanical pressing and opposed crushing wheels with hopper/chest output. Additional Phase IV capabilities, arbitrary layout generation, obstacle avoidance, a real Mekanism adapter and natural-language execution remain unavailable.

## Baseline

- Java 17
- Minecraft 1.20.1
- Forge development baseline 47.4.10; external compatibility target 47.4.0
- Create 6.0.6 Maven build 150 (matches the installed JAR's Git hash and embedded dependency versions)
- Mekanism optional; not present in the observed DeceasedCraft instance

## Build

Install a Java 17 JDK or set `JAVA_HOME`. Copy `.env.example` values into environment variables, or create an ignored `local.properties` for this machine.

```powershell
.\scripts\Test-Core.ps1
.\scripts\Build.ps1
```

Build output is under `forge-create-1.20.1/build/libs/`. Development worlds stay under `forge-create-1.20.1/run/`.

The candidate binary is `steve-industrial-agent-0.1.0-alpha.1.jar`.
Third-party loaders, mods, packs and world files are never included.

## Deployment safety status

PW-01 through PW-12 are complete: canonical environment classification, fail-closed policy/write guards, deterministic preview/risk/budget evidence, exact region/approval gates, isolated-only verified backup/restore, a generic claim-permission Adapter boundary, an independent twenty-five-check readiness aggregator and typed server-authoritative read-only deploy commands. `/industrialagent deploy preview|risks|budget|readiness <resource_id> <quantity> [orientation]` reports the current verified physical projection but never creates a ready execution session, reserves/consumes resources, starts a machine, accepts free-text coordinates or mutates a world. Every configured important/formal root remains non-executable. FS-01 through FS-12 completed the explicitly authorized guarded offline read-only survey, and FB-01 through FB-08 completed a verified external backup plus disposable restore drill. Exact formal-source pre/post fingerprints match. Eight candidates and eight approval requests remain UNKNOWN/awaiting user selection, with unavailable previews and no formal approval or execution authority. See `docs/FORMAL_WORLD_BACKUP.md`, `docs/FORMAL_WORLD_RESTORE_DRILL.md`, `docs/FORMAL_DEPLOYMENT_CANDIDATES.md` and `docs/FORMAL_DEPLOYMENT_APPROVAL.md`.

## In-game Phase 1 command

With the development client running and sufficient command permission:

```text
/industrialagent scan create 8
/industrialagent scan mekanism 8
/industrialagent scan all 8
```

The scan is read-only, server-thread-only, command-triggered, and capped at radius 16.

## Public disposable-world pilot

The release runtime now provides Forge configuration, an exact world-local
test marker, bounded region selection and preview, portable verified
backup/restore evidence, twenty-five-check readiness, visible
gravel and iron-sheet execution, status/cancel, journal-owned cleanup and
durable player-command recovery. The accepted live runs produced gravel 3/3
and iron sheets 2/2 in a disposable creative test world; the final iron-sheet run persisted at
BUILD, survived save-to-title/re-entry, resumed by exact rescan without repeated
placement/consumption/output and traversed CONNECT/FEED/PROCESS/VERIFY. Water
containment, Create belt cleanup, fault refusal and all recovery phases have
dedicated regressions. The configured important-instance root remains non-executable and recorded zero
launches and zero writes during the pilot. See
`INSTALLATION.md`, `QUICK_START.md`, `COMMAND_REFERENCE.md` and
`docs/PILOT_USER_GUIDE.md`.

## Continue with Codex

Open this repository root as the Codex project named **Steve Industrial Agent**, then continue from the existing task after reading `AGENTS.md`, `docs/PROJECT_STATE.md`, and `docs/BACKLOG.md`. Do not select the PCL2 directory or a Minecraft save as the project root.

## Upstream and license

The architecture was researched against [YuvDwi/Steve](https://github.com/YuvDwi/Steve) at commit `034afb53ed383efcef0084616022c57d6d2b8c02`. No upstream source has been copied at this milestone. See `NOTICE` and `docs/DECISIONS.md`. This project is licensed under MIT; see `LICENSE`.
