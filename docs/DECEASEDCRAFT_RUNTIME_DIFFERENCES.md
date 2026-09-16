# DeceasedCraft runtime-knowledge differences

## Status and boundary

R-08 is a read-only file audit of the existing DeceasedCraft Beta 5.10.16 client and server directories under `D:\PCL2`. It did not launch either instance, read a player save or server world, write a mod/config/KubeJS/datapack file, or install this project's JAR. The scoped file-metadata fingerprint was identical before and after the audit. Full evidence: `work/logs/deceasedcraft-runtime-readonly-audit-20260716-205323.log`.

This evidence describes visible pack inputs only. It is not a `RecipeManager` snapshot and must not be used to claim that the formal pack exposes the standard development profile's recipe counts, IDs, outputs, tags or runtime fingerprint.

## Observed differences

| Fact | Standard isolated Create profile | DeceasedCraft Beta 5.10.16 files | Consequence |
| --- | --- | --- | --- |
| Minecraft | 1.20.1 | 1.20.1 | Version matches. |
| Forge | 47.4.10 | 47.4.0 | The current v606 Adapter accepts Forge 47.4.x, but exact pack runtime still needs an isolated load. |
| Create | 6.0.6-150 development dependency | `create-1.20.1-6.0.6.jar` | Major target matches; the runtime fingerprint will still differ. |
| Mod set | Bounded Create-only development dependencies | 317 client JARs; 235 server JARs; 10 server Create-related JARs | Addons and integrations can add, replace or condition recipes and tag contents. |
| KubeJS | None in the isolated standard profile | 163 files on both sides; server-script hashes match | Formal runtime recipes can differ from Create defaults. |
| Generated datapacks | Isolated fixture data only | One generated pack metadata file; no visible generated milling/pressing recipe JSON | This does not exclude recipes injected by mods or KubeJS at reload time. |

The server's visible Create-related set includes Create Central Kitchen, Create Hypertube, Create Stuff Additions, Create Crafts & Additions, Create Big Cannons, Create Deco, Create Diesel Generators, KubeJS Create and Radars in addition to Create itself. Client-only differences do not change the matching server-script set.

## Relevant recipe inputs

The mirrored KubeJS server scripts contain:

- one direct milling declaration: coal coke to Immersive Engineering coke dust;
- one direct pressing declaration: an aluminum-plate tag to an Apocalypse Now can;
- 30 `createPressing` calls used as sequenced-assembly steps;
- no literal reference to `create:milling/cobblestone` or `create:pressing/iron_ingot`.

The absence of a literal removal does not prove either standard recipe survives. Other mods, tags, conditions and reload-time scripts can affect the actual manager. The pack's default Create server config also has `bulkPressing = false`; no audited config directly disables ordinary milling or pressing, but configuration inspection is not a substitute for a live server-thread recipe read.

## Verified and unverified claims

Verified in the standard isolated development environment:

- 2,609 total recipes were read from the real `RecipeManager`;
- 44 milling plus 7 pressing recipes were enumerated;
- 32 mapped and 19 returned typed `OUTPUT_UNSUPPORTED` limitations;
- real gravel and iron-sheet goals produced deterministic `VerifiedLogicalPlan` values;
- the catalog rebuilt after reload and the process remained world/session read-only.

The following were unverified at the R-08 checkpoint and are now closed by R-09:

- the formal pack's total, milling, pressing, mapped and rejected counts;
- survival of the two required recipe IDs and their exact resolved inputs/outputs;
- pack tag membership and deterministic Ingredient selections;
- KubeJS-generated recipe IDs and runtime conditions;
- the formal pack runtime fingerprint;
- absence of fatal mod-interaction/class-loading faults when this project is present, with 42 nonfatal diagnostics retained separately.

## R-09 isolated-pack-profile history

R-09A created the ignored disposable profile using read-only source inputs from the formal pack's dedicated-server distribution. The builder retains separate source/run trees, triple-hash-verifies every copied file, inventories JAR metadata, skips forbidden nested option/server-list data and proves formal client/server source fingerprints unchanged. Evidence is local and ignored under `work/isolated-pack/deceasedcraft-r09/evidence/`; the builder log is `work/logs/deceasedcraft-profile-builder-20260716-213258.log`.

At the R-09A checkpoint this was not yet a runtime result: Java/Forge had not started and no `RecipeManager` claim was made. R-09B through R-09F subsequently closed the fresh-world, exact-load, runtime accounting, typed limitation, sequence discovery, read-only planning, fingerprint and log/crash/external-write checks. No process ran against `D:\PCL2`, the formal client save or formal server world, and this project's JAR was never written back into either instance.

R-09B closed the startup boundary: the clean production JAR ran only inside the marked run copy, and the exact Forge 47.4.0/Create 6.0.6 profile reached ServerStarted with 288 mod containers and no filtered JAR. It saved the fresh isolated world, exited normally, left no process/crash report and preserved both formal-source fingerprints. The run retained 42 nonfatal optional-class/DISTXFORM/thread diagnostics, recorded in KI-027. R-09C through R-09F later supplied and repeated the RecipeManager, comparison and planning results below.

## R-09C authoritative runtime findings

The isolated authoritative RecipeManager contains 18,286 recipes. Its relevant Create type counts are: milling 66, pressing 37, sequenced assembly 91, mixing 67, compacting 26, crushing 103, cutting 488, deploying 113, splashing/washing 39, haunting 23, filling 30, emptying 8 and mechanical crafting 192. Existing R-02 mapping accounts exactly for all 103 milling/pressing entries: 45 milling plus all 37 pressing map, while 21 milling entries retain typed `OUTPUT_UNSUPPORTED`. There are 35 warnings: 31 probabilistic byproducts and four guaranteed secondary outputs. Runtime fingerprint is `sha256:fa8356e98e74445b354aa4430f42b38b52c0278a0c47a664c555b0206d47fcd1`.

KubeJS Create generates runtime IDs under `create:kjs/*`, not necessarily the `kubejs` namespace. The manager exposes 59 such recipes: 29 mechanical crafting, one milling, one mixing, one pressing and 27 sequenced assembly. The custom milling maps exact `immersiveengineering:coal_coke` to `immersiveengineering:dust_coke`; the custom pressing preserves the `forge:plates/aluminum` tag, its runtime candidate and `apocalypsenow:can` output. This is runtime confirmation of the static R-08 script clues.

Static R-08 found roughly 30 explicit sequenced pressing calls; the complete runtime truth is broader: 91 sequenced assemblies, 309 ordered steps and 38 pressing steps. The structured report retains every sequence/step ID, ingredient/output, loop count and transitional item. Current `RecipeCatalog` cannot safely preserve that ordered/looped/transitional structure, so all remain `RECIPE_TYPE_UNSUPPORTED`; no sequence is flattened and R-09 makes no sequenced-assembly planning claim.

## R-09D authoritative standard-vs-pack comparison

The comparison uses two runtime exports with the same schema, not KubeJS text counts: the standard isolated Create run contains 2,609 manager recipes and fingerprint `sha256:97b563c260f8c6528ca9a41e1e306a439a52beaf88418a510c97fd7e341a7704`; the pack run contains 18,286 and fingerprint `sha256:fa8356e98e74445b354aa4430f42b38b52c0278a0c47a664c555b0206d47fcd1`. Their complete recipe-type distributions remain in the source evidence. Recipe-level comparison is deliberately bounded to the existing R-02 milling/pressing mapping surface.

Within that surface, all 51 standard rows survive unchanged. No standard recipe ID is removed, and no common recipe changes type, canonical Ingredient/candidate set, output/byproduct, mapping status/limitation or processing duration. DeceasedCraft adds 52 rows: 50 map and two retain typed `OUTPUT_UNSUPPORTED`. The additions include `create:kjs/4w2pibcjt4pwqhn60e2n67l1l` (exact `immersiveengineering:coal_coke` to `immersiveengineering:dust_coke`) and `create:kjs/af7rthcw104gweqz8mzvmcrw7` (`forge:plates/aluminum`, resolved to `immersiveengineering:plate_aluminum`, to `apocalypsenow:can`). Thus the pack extends rather than substitutes the standard supported-boundary recipes in this observed runtime.

The ignored machine report is `work/isolated-pack/deceasedcraft-r09/evidence/runtime-difference.json`; regenerate it with `scripts/Compare-DeceasedCraftRuntimeKnowledge.ps1` after both runtime exports exist. It asserts both the 51-row standard and 103-row pack sets are fully accounted for. This result does not extend support to sequenced assembly or any other Create recipe type.

## R-09E pack-backed read-only planning

The isolated runtime produced four verified plans through the existing unchanged planning chain: `minecraft:gravel@3` uses the live two-step andesite/cobblestone milling chain; `create:iron_sheet@2` retains `forge:ingots/iron`; `immersiveengineering:dust_coke@4` uses KubeJS recipe `create:kjs/4w2pibcjt4pwqhn60e2n67l1l`; and `apocalypsenow:can@3` uses KubeJS pressing `create:kjs/af7rthcw104gweqz8mzvmcrw7` after the live IE aluminum-plate recipe. The can plan preserves `forge:plates/aluminum` and its deterministic candidate. Each result contains exactly the existing eight verifier checks and identifies its output type as `VerifiedLogicalPlan`.

Three negative scenarios remain typed: an unknown target is `RECIPE_NOT_FOUND`; probabilistic `create:milling/bone` remains catalog `OUTPUT_UNSUPPORTED`, so its otherwise unique bone-meal target cannot be planned and returns `RECIPE_NOT_FOUND`; and disabling the Create capability snapshot returns `CAPABILITY_CATALOG_MISSING`. Repeating the custom coke-dust goal preserves candidate order and verified-plan ID. The evidence exports no coordinates, orientation, implementation ID, physical ports/graph or execution session and performs no world mutation. Machine report: ignored `work/isolated-pack/deceasedcraft-r09/evidence/runtime-planning-latest.json`.

## R-09F closeout

The final full run reproduced all counts and fingerprints after rebuilding the disposable profile. It passed the complete JVM/clean-build/C-02/C-03/C-04/standard-catalog/pack-catalog chain, retained 51 unchanged standard mapped-or-rejected rows and 52 pack additions, and re-proved the four successful plus three typed-failure planning scenarios. There were zero strict standard markers, zero pack-fatal markers, zero crash reports and zero residual isolated Java processes. The known 42 nonfatal pack diagnostics remain visible and are not reclassified.

Formal client/server scoped fingerprints were identical before and after every final launch; `externalMutation=false` and `savesOrFormalWorldRead=false`. The ignored final summary is `work/isolated-pack/deceasedcraft-r09/evidence/r09-final-acceptance.json`. This closes evidence discovery and read-only planning only; sequenced assembly remains `RECIPE_TYPE_UNSUPPORTED`, and no implementation/layout/construction capability follows from this result.
