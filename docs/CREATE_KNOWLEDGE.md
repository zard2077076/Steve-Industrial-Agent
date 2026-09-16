# Create knowledge

## Verified target fingerprint

- Minecraft 1.20.1
- Forge runtime 47.4.0
- Create 6.0.6
- Git hash `338bfa0aec952fa51656e8f61bd621ca9b3b2e00`
- Development artifact `com.simibubi.create:create-1.20.1:6.0.6-150:slim`
- Ponder 1.0.80, Flywheel 1.0.4, Registrate MC1.20-1.3.3, MixinExtras 0.4.1

## Knowledge policy

Registry IDs, tags and recipes come from the active runtime after data packs load. Kinetic state should be read from the actual Create network/block entities through the version adapter. Versioned supplements may describe ports/placement rules only when runtime extraction is unreliable; each supplement needs a source, target version and GameTest.

The registry scan still knows only that `create` namespace blocks are candidate components. C-02 adds a separate strict 6.0.6 point reader for actual kinetic block entities; it does not infer gear meshing, belt flow, recipes or processing.

## Verified C-01 registry-scan behavior

`scripts/Test-CreateScan.ps1` is the repeatable acceptance entry point. It creates a new ignored dedicated-server world under `forge-create-1.20.1/run/create-scan-acceptance/`; it never reads or writes the external launcher instance or saves.

On Forge 47.4.10 / Minecraft 1.20.1, Create logs its 6.0.6 commit and exposes development mod version `6.0.6-150`. The authoritative server fixture resolves `create:shaft` from the active Forge block registry, places it with `axis=x`, and verifies that the bounded production adapter reports exactly:

- resource ID and namespace: `create:shaft` / `create`;
- the fixture's absolute server position;
- block state `axis=x`;
- one component within radius 1;
- a runtime fingerprint containing Create `6.0.6-150`.

The fixture then executes the registered `/industrialagent scan create 1` command at that position and requires return value 1 plus the Create summary message. A separate remote radius-1 request begins in an unloaded chunk, returns `CHUNK_NOT_LOADED`, and leaves that chunk unloaded. This confirms the adapter's chunk preflight prevents read-only scans from loading world data.

Evidence: `work/logs/create-scan-acceptance-20260713-121850.log`.

This acceptance proves registry identity, block-state capture, position, bounds, command wiring and no-load behavior only. `axis=x` is captured registry state; it is not kinetic direction telemetry. C-02 verifies kinetic point telemetry separately below; processing and output remain later work.

## Verified C-02 kinetic behavior

`scripts/Test-CreateKinetics.ps1` is the repeatable kinetic acceptance entry point. It creates a fresh ignored dedicated-server world under `forge-create-1.20.1/run/create-kinetics-acceptance/`; it never reads or writes the external launcher instance or saves.

The optional-mod-safe `ForgeCreateKineticAdapter` first rejects non-server threads, unsupported runtime versions and unloaded chunks. Only after those gates does it dispatch to `forge1201.adapter.create.internal.v606.Create606KineticReader`, the sole package importing Create internals. The reader accepts one loaded position and reads:

- actual signed component speed from `KineticBlockEntity.getSpeed()`;
- overstrain from `isOverStressed()`;
- Create's already synchronized `Network.Id`, `Network.Size`, `Network.Capacity`, and `Network.Stress` cache through the block entity's NBT writer.

The reader rejects dirty/unsettled kinetic state and never calls `getOrCreateNetwork`, `KineticNetwork.calculateCapacity`, `calculateStress`, or any member/source traversal. Capture is therefore O(1), cannot load a chunk, and returns an immutable loader-neutral snapshot carrying dimension, anchor block/position, actual speed magnitude, typed local signed direction, stress-enabled flag, network totals, runtime/schema identity and game tick.

In the isolated fixture, two `create:creative_motor` anchors both use the X axis. EAST produces `POSITIVE` at actual `16 RPM`; WEST produces `NEGATIVE` at actual `16 RPM`. This is direct evidence that block-state axis is not being used as direction. An isolated shaft reports `STOPPED`, no network, and zero totals. With the fixture-only motor capacity set to `1 SU/RPM`, a real EAST motor plus real encased fan settles through Create to capacity `16 SU`, load `32 SU`, actual speed `0`, `STATIONARY`, and `overstressed=true`. The capacity override is test-only and is not a claim about Create's production default.

The canonical bounded replay codec has a fixed magic/version/field order, sorted runtime-version map, explicit direction codes, encode/decode field and payload size limits, canonical re-encoding checks and trailing-data rejection. Connected network identities include their dimension. Pure JVM tests pin a golden payload, reject false overload when stress is disabled, and replay stopped/powered/overstressed diagnostics. The live Forge fixture round-trips all four authoritative captures before shutdown.

Evidence: `work/logs/create-kinetics-acceptance-20260713-125833.log`.

C-02 observes an anchor component's actual signed rotation plus network-wide cached stress totals. It does not claim that one speed/direction applies to every member of a geared network, and it does not observe processing inputs, progress, outputs, layouts, building or repair. C-03 adds one separately verified processing path below.

## Verified C-03 water-wheel/millstone behavior

`scripts/Test-CreateProcessing.ps1` runs a fresh Forge GameTest world under `run/create-processing-gametest/` and uses deterministic empty structure template `minecraft:bastion/mobs/empty`. Forge 47.4.10 registered and ran the GameTest successfully, so no tick-driven dedicated-server fallback was needed.

The loader-neutral `WaterWheelMillstonePlan` accepts only a typed origin. It deterministically resolves ten ordered roles: a bounded water catch, `create:water_wheel`, `create:gearbox`, vertical `create:shaft`, `create:millstone`, and the final `minecraft:water` source. It fixes the recipe as one `minecraft:cobblestone` to at least one `minecraft:gravel`, and exposes a 5 x 7 x 5 (175-position) loaded-area preflight capped at 256. Coordinate overflow is rejected before an executor can access a world.

`ForgeCreatePlanAdapter` rejects the wrong thread, unsupported versions, build-height violations and unloaded chunks before dispatching. All direct Create calls are in `forge1201.adapter.create.internal.v606.Create606WaterWheelMillstoneExecutor`. Water wheels encode their horizontal rotation axis through `facing` in Create 6.0.6, while the gearbox and shaft use `axis`; that version-specific distinction does not leak into the loader-neutral plan.

The executor places at most one block per tick, sets water last, and then polls already-loaded block entities. The passing GameTest read back all ten blocks and axes, observed actual `8 RPM` on the water wheel, gearbox, shaft and millstone, resolved live recipe `create:milling/cobblestone` with type `create:milling` and duration `250`, inserted one cobblestone into the real millstone input inventory, observed that input consumed, and observed one gravel in the real output inventory. Completion evidence cannot be constructed without positive live speed at all four kinetic roles plus real inventory output.

The same GameTest verifies that a named worker returns `WRONG_THREAD`, a remote plan returns `CHUNK_NOT_LOADED`, and the remote chunk stays unloaded. GameTest reported 1/1 required tests passed and saved all dimensions without a forbidden error/thread marker or crash report.

Evidence: `work/logs/create-processing-gametest-20260713-133625.log`.

C-03 proves only this fixed water-wheel/gearbox/shaft/millstone topology and the cobblestone milling recipe. It does not prove belts, presses, item transport into or out of the layout, arbitrary recipes, repair, rollback, throughput, or cross-mod processing.

## Verified C-04 belt/press behavior

`scripts/Test-CreateBeltPress.ps1` runs a fresh Forge GameTest world under `run/create-belt-press-gametest/` with the same deterministic vanilla empty template as C-03, but registers and executes a separate C-04 test class. C-03 water-wheel/millstone success is not used as belt or press evidence.

The loader-neutral `BeltPressPlan` accepts only a typed origin. It fixes eight ordered build roles: two temporary Z-axis pulley shafts, a belt motor, a press motor, a mechanical press, a chest, an upward-facing insertion-mode andesite funnel, and one final belt-connection action. Its eight final roles replace the temporary shafts with a real three-segment horizontal belt. The plan fixes `create:pressing/iron_ingot`, `minecraft:iron_ingot`, `create:iron_sheet`, finite power/processing timeouts and a 6 x 5 x 5 (150-position) preflight capped at 256. All plan collections are immutable and coordinate overflow is rejected before world access.

`ForgeCreateBeltPressPlanAdapter` rejects the wrong thread, unsupported runtime, build-height violations and unloaded chunks before dispatching. All Create internals are confined to `forge1201.adapter.create.internal.v606.Create606BeltPressExecutor`. The finite executor places at most one typed block per tick; its single connect step calls Create's real bounded belt-connector operation between the planned shafts. It then polls the already-loaded final belt controller/index/length/state and six kinetic block entities without sleeping, blocking, network access, chunk loading or graph traversal.

In the passing world, all three belt segments shared the planned controller and moved east. The belt motor, each belt segment, press motor and mechanical press all reported actual `16 RPM`. The live recipe manager resolved `create:pressing/iron_ingot` with type `create:pressing` and deterministic iron-sheet output. One iron ingot entered the real belt item handler, the mechanical press exposed Create's `BELT` mode and real 240-tick press cycle, and one iron sheet was read from the planned chest.

Create 6.0.6 does not treat a plain chest at the horizontal belt ending as direct belt input: a development run proved the iron sheet was correctly pressed but ejected as an item entity. The final typed layout therefore places an unpowered, non-extracting `create:andesite_funnel` at the belt ending with its target chest directly below. The passing acceptance proves that exact funnel-to-chest transfer and does not substitute an in-memory output flag.

The same GameTest proves `WRONG_THREAD` for a named worker and `CHUNK_NOT_LOADED` without loading a remote chunk. Completion evidence cannot be constructed unless all eight final roles still match, every kinetic role has positive live speed, the belt input and real press cycle were observed, the input is gone from the belt and the iron sheet is present in the real chest.

Evidence: `work/logs/create-belt-press-gametest-20260713-143431.log`.

C-04 proves only this fixed three-segment horizontal belt, two creative-motor power sources, one mechanical press, one upward insertion funnel, one chest and the iron-ingot pressing recipe on Forge 47.4.10/Create 6.0.6-150. It does not prove arbitrary belts, slopes, depots, funnels, press recipes, throughput, repair, rollback, persistence or cross-mod transport.
