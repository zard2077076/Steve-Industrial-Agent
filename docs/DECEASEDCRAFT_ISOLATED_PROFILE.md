# DeceasedCraft isolated profile

## Scope and safety

R-09 reproduces DeceasedCraft Beta 5.10.16 only inside the repository's ignored `work/isolated-pack/deceasedcraft-r09` directory. The formal client and dedicated-server packages under `D:\PCL2` are read-only sources. Their saves/worlds, player and account data, options, logs, crash reports, screenshots, backups and resource packs are never copied or used as a game directory.

The profile has three parts:

- `source/` retains the approved source copy so later server-only filtering never deletes original evidence;
- `run/` is the only writable Forge game directory;
- `evidence/` stores local manifests, mod metadata and formal-source before/after snapshots.

All three are ignored by Git. Mod JARs, libraries, copied configuration, KubeJS content, generated data, worlds and machine-specific absolute paths must never be force-added or committed.

## Create or refresh

First validate paths without copying:

```powershell
.\scripts\New-DeceasedCraftIsolatedProfile.ps1 -PlanOnly
.\scripts\Test-DeceasedCraftProfileBuilder.ps1
```

Then create or deliberately recreate only the ignored profile:

```powershell
.\scripts\New-DeceasedCraftIsolatedProfile.ps1 -Refresh
```

The builder copies the server package's `mods`, `config`, `defaultconfigs`, `kubejs`, `generated_datapacks`, Forge `libraries`, and the bounded local resource directories `customnpcs`, `patchouli_books` and `tacz` when present. It creates fresh isolated `eula.txt`, `server.properties` and `user_jvm_args.txt`; it does not copy the formal server properties, identity lists, caches or world serverconfig.

Every copied file is hashed at source, retained copy and run copy. The ignored evidence directory records relative path, size, SHA-256, source path, copy time and whether the file can affect recipes or registries. `mod-inventory.csv` records each JAR's metadata without guessing that a filename is client-only. Actual Forge loading evidence, not a filename heuristic, controls any later exclusion.

If a forbidden private or generated filename is nested inside an otherwise approved scope, the builder does not copy it. It records only its source-relative path, size, matched forbidden name and exclusion time in ignored `copy-exclusions.jsonl`; this is an allowlist decision, not a client-mod filter.

## Start, stop and clean

R-09B's bounded launch driver is:

```powershell
.\scripts\Test-DeceasedCraftIsolatedServer.ps1
```

Before Java starts it prints the resolved game directory and proves it is the profile's `run/` child, outside `D:\PCL2`. The driver uses Java 17, a fresh clean build, the copied Forge 47.4.0 `win_args.txt`, startup/initialization/probe/shutdown timeouts, production-JAR hash/readback, normal server save/stop, forced termination only after the shutdown bound, residual-process detection and retained logs on every outcome. `-SkipBuild` is allowed only when the caller has just completed and retained the same-turn clean build, as in the first R-09B acceptance.

Do not run the formal package's launcher or pass a formal directory as Java's working directory. Do not manually install the Steve Industrial Agent JAR into `D:\PCL2`.

After reviewing retained evidence, the whole profile can be deleted and rebuilt. Cleanup must resolve and revalidate the exact ignored profile path before recursive deletion. Never aim cleanup at `work`, the repository root or any `D:\PCL2` path.

## Verify the formal source

The builder hashes only approved formal scopes (`mods`, `config`, `defaultconfigs`, `kubejs`, `generated_datapacks`) and records counts, sizes, timestamps and SHA-256-derived fingerprints before and after copying. It never enumerates a saves/world directory. A fingerprint mismatch is `EXTERNAL_WRITE_RISK`: all launches stop, evidence is retained, and no automatic repair is attempted.

R-09F repeats the external comparison after the final isolated run; the accepted final run found no change. Pack runtime counts are accepted only from the isolated authoritative `RecipeManager`; static scripts remain attribution clues, not runtime truth.

The first accepted R-09B run loaded 288 mod containers without filtering and left zero crash reports or Java processes. Its 42 matched optional-class/DISTXFORM/thread diagnostics remain documented in KI-027; startup success is not mislabeled as a zero-diagnostic log.

R-09C extends the same command with runtime evidence validation. The in-process exporter lives under the versioned Create 6.0.6 internal Adapter boundary and writes only `run/r09-evidence/runtime-knowledge.json`; the driver schema-checks it, verifies type/mapping conservation, requires KubeJS custom milling/pressing plus typed sequenced-assembly evidence, and retains a timestamped copy under ignored `evidence/`. Static script counts never satisfy these gates.

R-09D first reruns `scripts/Test-CreateRuntimeRecipeCatalog.ps1` to produce the standard-profile export, then runs `scripts/Compare-DeceasedCraftRuntimeKnowledge.ps1`. The comparison is external and read-only: it normalizes reload-sensitive tag fingerprints only for canonical Ingredient equality, retains runtime candidates, and verifies both standard and pack row counts before accepting added/removed/modified results.

R-09E is part of the same bounded isolated-server command. After RecipeManager evidence completes, a separate planning timeout begins. The versioned Adapter exporter runs standard milling/pressing, custom KubeJS milling/pressing, quantity, tag, missing-target, rejected-probabilistic, disabled-capability and repeatability scenarios; the PowerShell driver accepts successes only with all eight verifier checks and rejects any serialized physical/session/world authority. It retains `runtime-planning-latest.json` under ignored profile evidence.

## Full repeatable R-09 acceptance

Run the complete closeout from the repository root:

```powershell
.\scripts\Test-DeceasedCraftR09.ps1
```

It revalidates the safe path, refreshes only the ignored profile, runs JVM and clean-build tests, runs unchanged C-02/C-03/C-04 physical acceptance, exports the standard runtime, launches the exact pack runtime, compares both catalogs, verifies the formal snapshot again, scans accepted logs/crash directories and checks for a residual Java process. The final machine summary is ignored `evidence/r09-final-acceptance.json`. Do not commit that evidence, copied JARs/config/KubeJS data, run world or logs.
