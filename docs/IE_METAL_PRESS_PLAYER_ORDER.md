# IE Metal Press player order

Updated: 2026-08-01 (Asia/Shanghai)

## Ordinary player flow

1. Put the exact 16-item order BOM in one vanilla chest.
2. Hold the Steve Engineer Terminal and sneak-use that chest.
3. Sneak-use a clear target surface within 32 blocks.
4. Sneak-use in air to watch the dedicated order screen.
5. Let the visible courier and builder Bots finish; collect the tagged iron
   plate beside the source chest and review the completion report.

The terminal tooltip explains all gestures. The order screen shows stage and
progress, material source and site, planned/withdrawn/consumed/returned counts,
live FE, unique output, pause guidance and final duplicate/unaccounted/private
item counters. Safe cancel requires two clicks and is disabled after the iron
input has been admitted.

## Server-authoritative lifecycle

```text
select source -> rescan -> reserve 16 -> Bot withdraw/deliver
-> build 7 blocks -> IE hammer formation -> install retained plate mold
-> build thermoelectric generator + 2 LV connectors + copper wire
-> verify machine FE -> admit 1 iron ingot -> observe exactly 2400 FE
-> claim exactly 1 iron plate -> teardown -> restore baseline
-> return 14 items -> completion report
```

The BOM contains two steel scaffolds, heavy and redstone engineering blocks,
two basic conveyors, one piston, engineer hammer, plate mold, iron ingot,
thermoelectric generator, two LV connectors, one copper wire coil, blue ice and
magma block. Only the iron ingot and copper wire coil are consumed.

The client submits intent. The server rechecks the marked isolated world,
dimension, player permission, source type/snapshot/slots/NBT, site bounds,
distance, recipe/runtime pin and every reservation. It never searches another
container, reads player inventory, directly writes FE or treats nearby salvage
as stock.

## Durability and interruption handling

Each physical side effect is separately journaled and saved. The state reducer
reconciles physical and persisted evidence for these tested windows:

- iron withdrawn but not delivered;
- mold installed but not powered;
- input admitted but output absent;
- 2,400 FE settled but order not accounted;
- output claimed but report absent.

The write/read acceptance destroys the first server JVM, reloads from SavedData
in a second JVM and proves no duplicate withdrawal, input admission, energy
settlement or output. Source drift, missing Bot/container, full return slot or
physical mismatch pauses with a bounded diagnostic; it never silently switches
sources or guesses success.

## Acceptance evidence and boundary

`./gradlew :forge-create-1.20.1:runServer -PiePhysicalAcceptance --no-daemon`
passed with real IE formation/mold/wire/input/output, 2,400 FE, one output,
16 planned, 2 consumed, 14 returned, all duplicate/private/unaccounted counters
zero, balanced material ledger, zero direct energy writes and restored baseline.

The additive order channel is `metal-press-order-v1`; the frozen player/material
protocol remains `phase-iv-player-workflow-v3`. This is one reviewed vertical
slice, not generic IE execution or a physical central warehouse. A human-run
Mac click-through and Windows/PCL2 validation remain separate acceptance gates.
